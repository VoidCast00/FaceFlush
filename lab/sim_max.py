"""Simulates "Max privacy": a cloak optimised for each photo, as the phone would do it.

Only the models that may ship in the app (SFace, FaceNet) are attacked; ArcFace is held out, so
its numbers show how well a per-photo cloak carries over to a stronger model the phone never saw.
The optimisation mirrors the planned on-device loop: start from the quick cloak (if present),
then signed-gradient steps with random transforms, inside the user's visibility limit.

Optional tricks from the transfer literature (all off by default = the original loop):
  --momentum 0.9   MI-FGSM: steps follow a running average of normalised gradients
  --ti 2           TI: blur each gradient (Gaussian sigma in canonical px) before using it
  --chroma 0.3     colour limit: the pattern lives in brightness + two colour-difference channels;
                   colour may only use this fraction of the budget (0 = brightness only)
  --jnd 1.5        per-pixel limit = this many just-noticeable differences (capped by eps), instead
                   of the texture weight: keeps smooth mid-tone skin below what the eye notices

Usage: python sim_max.py [--eps 8] [--steps 150] [--probes 150] [--momentum 0.9] [--ti 2] [--chroma 0.3]
Writes $LAB/results/max_sim_<attack>_eps<eps>[_<tricks>].md.
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
from faceflush_lab.bench import CLEAN_POSTS, DEFAULT_POSTS, Bench, table
from faceflush_lab.cloak import apply_crop, apply_image, edge_mask, jnd_t
from faceflush_lab.paths import RESULTS
from train_cloak import random_transform


# Pattern channels (brightness, blue difference, red difference) -> RGB, from the JPEG YCbCr
# inverse with the colour channels scaled to [-1, 1]. Brightness alone moves R, G and B together.
YCC_TO_RGB = torch.tensor([
    [1.0, 0.0, 0.701],
    [1.0, -0.172, -0.357],
    [1.0, 0.886, 0.0],
])


def to_rgb(pattern: torch.Tensor, chroma: float | None) -> torch.Tensor:
    """pattern (3, H, W) in [-1, 1] -> RGB pattern in [-1, 1]. chroma None = pattern is already RGB."""
    if chroma is None:
        return pattern.clamp(-1, 1)
    scale = torch.tensor([1.0, chroma, chroma], device=pattern.device)[:, None, None]
    rgb = torch.einsum("rc,chw->rhw", YCC_TO_RGB.to(pattern.device), pattern.clamp(-1, 1) * scale)
    return rgb.clamp(-1, 1)  # keeps every RGB channel inside the user's visibility limit


def gaussian_blur(g: torch.Tensor, sigma: float) -> torch.Tensor:
    r = max(1, math.ceil(2 * sigma))
    k = torch.arange(-r, r + 1, device=g.device, dtype=g.dtype)
    k = torch.exp(-k**2 / (2 * sigma**2))
    k = (k / k.sum()).view(1, 1, 1, -1).repeat(g.shape[1], 1, 1, 1)
    g = F.conv2d(F.pad(g, (r, r, 0, 0), mode="replicate"), k, groups=g.shape[1])
    return F.conv2d(F.pad(g, (0, 0, r, r), mode="replicate"), k.transpose(2, 3), groups=g.shape[1])


def rgb_start(start: np.ndarray, chroma: float | None) -> np.ndarray:
    """The quick-cloak warm start is RGB; in colour-limited mode use its brightness part only."""
    if chroma is None:
        return start
    y = start.mean(0, keepdims=True)
    return np.concatenate([y, np.zeros_like(start[:2])]).astype(np.float32)


def make_protect(attack: dict, eps: float, steps: int, eot: int, start: np.ndarray | None, dev: str,
                 momentum: float = 0.0, ti: float = 0.0, chroma: float | None = None,
                 jnd: float | None = None):
    mask = torch.from_numpy(edge_mask()).to(dev)[None, None]

    def protect(img, lm, rng):
        crop, _ = align(img, lm)
        x = torch.from_numpy(crop[:, :, ::-1].astype(np.float32) / 255.0).permute(2, 0, 1)[None].to(dev)
        with torch.no_grad():
            refs = {n: m(x) for n, m in attack.items()}
            # From the clean face only, so the optimiser can't earn extra room by adding texture
            weight = (jnd * jnd_t(x) / (255 * eps)).clamp(max=1) if jnd else None
        init = (torch.from_numpy(rgb_start(start, chroma)).to(dev)[None] if start is not None
                else torch.zeros(1, 3, 112, 112, device=dev))
        pattern = init.clone().requires_grad_(True)
        velocity = torch.zeros_like(pattern)
        step_size = 10.0 / steps  # lets the pattern sweep its whole range a few times
        for _ in range(steps):
            adv = apply_crop(x.expand(eot, -1, -1, -1), to_rgb(pattern[0], chroma), eps, mask, weight)
            adv = random_transform(adv)
            loss = sum((m(adv) * refs[n]).sum(1).mean() for n, m in attack.items())
            grad, = torch.autograd.grad(loss, pattern)
            with torch.no_grad():
                if ti > 0:
                    grad = gaussian_blur(grad, ti)
                if momentum > 0:
                    velocity = momentum * velocity + grad / grad.abs().mean().clamp_min(1e-12)
                    grad = velocity
                pattern -= step_size * grad.sign()
                pattern.clamp_(-1, 1)
        rgb = to_rgb(pattern[0].detach(), chroma).cpu().numpy()
        return apply_image(img, lm, rgb, eps, None if weight is None else weight[0, 0].cpu().numpy())

    return protect


def colour_change(before: np.ndarray, after: np.ndarray) -> tuple[float, float]:
    """Mean brightness and colour change (0-255 levels) over the pixels the cloak touched."""
    a = cv2.cvtColor(before, cv2.COLOR_BGR2YCrCb).astype(np.float32)
    b = cv2.cvtColor(after, cv2.COLOR_BGR2YCrCb).astype(np.float32)
    d = np.abs(b - a)
    touched = (np.abs(after.astype(np.int16) - before.astype(np.int16)).max(2) > 0)
    if not touched.any():
        return 0.0, 0.0
    return float(d[..., 0][touched].mean()), float(np.hypot(d[..., 1], d[..., 2])[touched].mean())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--eps", type=float, default=8.0)
    ap.add_argument("--steps", type=int, default=150)
    ap.add_argument("--eot", type=int, default=8)
    ap.add_argument("--probes", type=int, default=150)
    ap.add_argument("--attack", default="sface,facenet", help="models the phone would carry")
    ap.add_argument("--momentum", type=float, default=0.0)
    ap.add_argument("--ti", type=float, default=0.0, help="gradient blur sigma, canonical px")
    ap.add_argument("--chroma", type=float, default=None, help="colour share of the budget (0..1)")
    ap.add_argument("--jnd", type=float, default=None, help="per-pixel limit in just-noticeable differences")
    ap.add_argument("--clean", action="store_true", help="also test cloak-removal tricks (blur, WebP, ...)")
    args = ap.parse_args()

    dev = "cuda"
    attack = M.load(args.attack.split(","), dev)
    start_file = RESULTS / "cloak_pattern.npy"
    start = np.load(start_file) if start_file.exists() else None
    tricks = "".join([
        f"_mi{args.momentum:g}" if args.momentum else "",
        f"_ti{args.ti:g}" if args.ti else "",
        f"_c{args.chroma:g}" if args.chroma is not None else "",
        f"_jnd{args.jnd:g}" if args.jnd else "",
        "_clean" if args.clean else "",
    ])
    protect = make_protect(attack, args.eps / 255.0, args.steps, args.eot, start, dev,
                           momentum=args.momentum, ti=args.ti, chroma=args.chroma, jnd=args.jnd)
    changes = []

    def measured(img, lm, rng):
        out = protect(img, lm, rng)
        changes.append(colour_change(img, out))
        return out

    bench = Bench(["arcface_r50", "sface", "facenet"], probes=args.probes)
    # With cleaning tricks, also show what they do to unprotected photos: a cleaning that "works"
    # only because it ruins every face is no real threat
    rows = bench.run("unprotected", lambda img, lm, rng: img, posts=CLEAN_POSTS) if args.clean else []
    t0 = time.time()
    rows += bench.run(f"max ε{args.eps:g}{tricks or ''} ({args.steps} steps)", measured,
                     posts=CLEAN_POSTS if args.clean else DEFAULT_POSTS)
    took = (time.time() - t0) / args.probes
    luma, colour = np.mean(changes, 0)
    report = (f"# Max privacy simulation\n\nattacked: {args.attack} (arcface held out), ε{args.eps:g}, "
              f"{args.steps} steps × {args.eot} transforms, warm start: {start is not None}, "
              f"momentum {args.momentum:g}, TI sigma {args.ti:g}, chroma {args.chroma}, JND {args.jnd}, "
              f"{args.probes} probes, {took:.1f} s/photo on the RX 6600\n\n"
              f"average change on touched pixels: brightness {luma:.2f}, colour {colour:.2f} (0-255 levels)\n\n"
              + table(rows) + "\n")
    (RESULTS / f"max_sim_{args.attack.replace(',', '+')}_eps{args.eps:g}{tricks}.md").write_text(report)
    print(report)


if __name__ == "__main__":
    main()
