"""Trains the universal "quick cloak" pattern and benchmarks it.

One pattern in canonical face space is optimised over thousands of training faces so that adding
it pushes every face's embedding away from that same face's clean embedding, for an ensemble of
recognition models. Random transforms in every step (re-alignment jitter, downscaling, blur,
noise, brightness) make it survive what happens to photos after they're shared. SFace stays out
of training, so its numbers show how well the pattern carries over to a model it has never seen.

Usage: python train_cloak.py [--eps 10] [--steps 4000] [--models arcface_r50,facenet]
Writes $LAB/results/cloak_pattern.npy and cloak.md.
"""
from __future__ import annotations

import argparse
import math
import time

import cv2
import numpy as np
import torch
import torch.nn.functional as F

from faceflush_lab import models as M
from faceflush_lab.align import align
from faceflush_lab.bench import Bench, table
from faceflush_lab.cloak import apply_crop, apply_image, edge_mask
from faceflush_lab.paths import DATA, RESULTS


def load_train_crops(limit: int | None) -> torch.Tensor:
    idx = np.load(DATA / "index.npz")
    sel = np.where(idx["split"] == "train")[0]
    if limit:
        sel = sel[:limit]
    crops = []
    for i in sel:
        crop, _ = align(cv2.imread(str(DATA / idx["path"][i])), idx["landmarks"][i].astype(np.float64))
        crops.append(crop[:, :, ::-1])
    arr = np.stack(crops).astype(np.float32) / 255.0
    return torch.from_numpy(arr).permute(0, 3, 1, 2).contiguous()


def random_transform(x: torch.Tensor) -> torch.Tensor:
    """What can happen between our phone and the attacker's model (all differentiable)."""
    n = x.shape[0]
    dev = x.device
    # Re-detection and re-alignment by the attacker: small rotation, scale and shift
    ang = (torch.rand(n, device=dev) * 2 - 1) * math.radians(5)
    scale = 1 + (torch.rand(n, device=dev) * 2 - 1) * 0.06
    shift = (torch.rand(n, 2, device=dev) * 2 - 1) * 0.06
    cos, sin = torch.cos(ang) / scale, torch.sin(ang) / scale
    theta = torch.stack([torch.stack([cos, -sin, shift[:, 0]], 1), torch.stack([sin, cos, shift[:, 1]], 1)], 1)
    grid = F.affine_grid(theta, x.shape, align_corners=False)
    x = F.grid_sample(x, grid, mode="bilinear", padding_mode="reflection", align_corners=False)
    # Downscaling by the platform, then back up
    s = float(np.random.uniform(0.45, 1.0))
    if s < 0.98:
        size = max(24, int(112 * s))
        x = F.interpolate(F.interpolate(x, size=(size, size), mode="bilinear", antialias=True), size=(112, 112), mode="bilinear")
    # Blur (JPEG and resampling soften fine detail)
    sigma = float(np.random.uniform(0, 1.0))
    if sigma > 0.2:
        k = torch.arange(-2, 3, device=dev, dtype=x.dtype)
        g = torch.exp(-k**2 / (2 * sigma**2))
        g = (g / g.sum()).view(1, 1, 1, 5).repeat(3, 1, 1, 1)
        x = F.conv2d(F.pad(x, (2, 2, 0, 0), mode="replicate"), g, groups=3)
        x = F.conv2d(F.pad(x, (0, 0, 2, 2), mode="replicate"), g.transpose(2, 3), groups=3)
    # Compression noise and exposure changes
    x = x + torch.randn_like(x) * float(np.random.uniform(0, 0.012))
    x = x * float(np.random.uniform(0.9, 1.1)) + float(np.random.uniform(-0.04, 0.04))
    return x.clamp(0, 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--eps", type=float, default=10.0, help="strength in 1/255 units used for training")
    ap.add_argument("--steps", type=int, default=4000)
    ap.add_argument("--batch", type=int, default=48)
    ap.add_argument("--models", default="arcface_r50,facenet")
    ap.add_argument("--limit", type=int, default=None, help="use only this many training faces")
    ap.add_argument("--out", default="cloak_pattern")
    args = ap.parse_args()

    dev = "cuda"
    torch.manual_seed(0)
    np.random.seed(0)
    nets = M.load(args.models.split(","), dev)
    data = load_train_crops(args.limit)
    print("train faces:", len(data), flush=True)
    mask = torch.from_numpy(edge_mask()).to(dev)[None, None]
    eps = args.eps / 255.0

    w = torch.zeros(1, 3, 112, 112, device=dev, requires_grad=True)
    opt = torch.optim.Adam([w], lr=0.03)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, args.steps)
    t0 = time.time()
    for step in range(args.steps):
        x = data[torch.randint(len(data), (args.batch,))].to(dev, non_blocking=True)
        x = torch.where(torch.rand(args.batch, 1, 1, 1, device=dev) < 0.5, x, x.flip(3))
        pattern = torch.tanh(w)[0]
        adv = random_transform(apply_crop(x, pattern, eps, mask))
        loss = 0.0
        for net in nets.values():
            with torch.no_grad():
                ref = net(x)
            loss = loss + (net(adv) * ref).sum(1).mean()
        loss = loss / len(nets)
        opt.zero_grad()
        loss.backward()
        opt.step()
        sched.step()
        if step % 200 == 0 or step == args.steps - 1:
            print(f"step {step:5d}  mean cos to own clean face {loss.item():.3f}  {time.time() - t0:.0f}s", flush=True)

    pattern = torch.tanh(w)[0].detach().cpu().numpy().astype(np.float32)
    RESULTS.mkdir(parents=True, exist_ok=True)
    np.save(RESULTS / f"{args.out}.npy", pattern)
    vis = ((pattern.transpose(1, 2, 0)[:, :, ::-1] * 0.5 + 0.5) * 255).astype(np.uint8)
    cv2.imwrite(str(RESULTS / f"{args.out}.png"), cv2.resize(vis, (336, 336), interpolation=cv2.INTER_NEAREST))

    del data, nets
    torch.cuda.empty_cache()
    bench = Bench(["arcface_r50", "sface", "facenet"])
    rows = bench.run("none", lambda img, lm, rng: img)
    for e in (4, 8, 12, 16):
        rows += bench.run(f"cloak ε{e}", lambda img, lm, rng, e=e: apply_image(img, lm, pattern, e / 255.0))
    report = (f"# Quick cloak `{args.out}`\n\ntrained on {args.models}, ε{args.eps:g}, {args.steps} steps; "
              f"sface is held out\n\n" + table(rows) + "\n")
    (RESULTS / f"{args.out}.md").write_text(report)
    print(report)


if __name__ == "__main__":
    main()
