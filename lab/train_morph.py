"""Trains the universal face-shape morph and benchmarks it.

Each movable control point may move at most --budget canonical pixels (the eye distance is about
35 px, so 3 px is roughly 8%). The moves are optimised over the training faces so the morphed
face drifts away from its own clean embedding, with the same random transforms as the cloak.

Usage: python train_morph.py [--budget 3] [--steps 1500]
Writes $LAB/results/morph_field.npy and morph.md.
"""
from __future__ import annotations

import argparse
import time

import numpy as np
import torch

from faceflush_lab import models as M
from faceflush_lab import morph
from faceflush_lab.bench import Bench, table
from faceflush_lab.paths import RESULTS
from train_cloak import load_train_crops, random_transform


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--budget", type=float, default=3.0)
    ap.add_argument("--steps", type=int, default=1500)
    ap.add_argument("--batch", type=int, default=48)
    ap.add_argument("--models", default="arcface_r50,facenet")
    ap.add_argument("--out", default="morph_field")
    args = ap.parse_args()

    dev = "cuda"
    torch.manual_seed(0)
    np.random.seed(0)
    nets = M.load(args.models.split(","), dev)
    data = load_train_crops(None)
    basis = torch.from_numpy(morph.basis_fields()).to(dev)
    w = torch.zeros(len(morph.MOVABLE), 2, device=dev, requires_grad=True)
    opt = torch.optim.Adam([w], lr=0.05)
    t0 = time.time()
    for step in range(args.steps):
        x = data[torch.randint(len(data), (args.batch,))].to(dev)
        moves = args.budget * torch.tanh(w)
        adv = random_transform(morph.apply_crop(x, morph.field_from_moves(basis, moves)))
        loss = 0.0
        for net in nets.values():
            with torch.no_grad():
                ref = net(x)
            loss = loss + (net(adv) * ref).sum(1).mean()
        loss = loss / len(nets)
        opt.zero_grad()
        loss.backward()
        opt.step()
        if step % 200 == 0 or step == args.steps - 1:
            print(f"step {step:5d}  mean cos {loss.item():.3f}  {time.time() - t0:.0f}s", flush=True)

    moves = (args.budget * torch.tanh(w)).detach()
    field = morph.field_from_moves(basis, moves).cpu().numpy().astype(np.float32)
    RESULTS.mkdir(parents=True, exist_ok=True)
    np.save(RESULTS / f"{args.out}.npy", field)
    np.savetxt(RESULTS / f"{args.out}_moves.txt", moves.cpu().numpy(), fmt="%.2f")

    del data, nets
    torch.cuda.empty_cache()
    bench = Bench(["arcface_r50", "sface", "facenet"])
    rows = []
    for amount in (0.5, 1.0, 1.5):
        rows += bench.run(f"morph ×{amount:g}", lambda img, lm, rng, a=amount: morph.apply_image(img, lm, field, a))
    report = (f"# Face morph `{args.out}`\n\ntrained on {args.models}, budget {args.budget:g} px; sface is held out\n\n"
              f"Point moves (px, canonical):\n```\n{np.array2string(moves.cpu().numpy(), precision=2)}\n```\n\n"
              + table(rows) + "\n")
    (RESULTS / f"{args.out}.md").write_text(report)
    print(report)


if __name__ == "__main__":
    main()
