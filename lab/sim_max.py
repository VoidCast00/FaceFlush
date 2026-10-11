"""Simulates "Max privacy": a cloak optimised for each photo, as the phone would do it.

Only the models that may ship in the app (SFace, FaceNet) are attacked; ArcFace is held out, so
its numbers show how well a per-photo cloak carries over to a stronger model the phone never saw.
The optimisation mirrors the planned on-device loop: start from the quick cloak (if present),
then signed-gradient steps with random transforms, inside the user's visibility limit.

Usage: python sim_max.py [--eps 8] [--steps 150] [--probes 150]
Writes $LAB/results/max_sim.md.
"""
from __future__ import annotations

import argparse
import time

import numpy as np
import torch

from faceflush_lab import models as M
from faceflush_lab.align import align
from faceflush_lab.bench import Bench, table
from faceflush_lab.cloak import apply_crop, apply_image, edge_mask
from faceflush_lab.paths import RESULTS
from train_cloak import random_transform


def make_protect(attack: dict, eps: float, steps: int, eot: int, start: np.ndarray | None, dev: str):
    mask = torch.from_numpy(edge_mask()).to(dev)[None, None]

    def protect(img, lm, rng):
        crop, _ = align(img, lm)
        x = torch.from_numpy(crop[:, :, ::-1].astype(np.float32) / 255.0).permute(2, 0, 1)[None].to(dev)
        with torch.no_grad():
            refs = {n: m(x) for n, m in attack.items()}
        init = torch.from_numpy(start).to(dev)[None] if start is not None else torch.zeros(1, 3, 112, 112, device=dev)
        pattern = init.clone().requires_grad_(True)
        step_size = 10.0 / steps  # lets the pattern sweep its whole range a few times
        for _ in range(steps):
            adv = apply_crop(x.expand(eot, -1, -1, -1), pattern[0].clamp(-1, 1), eps, mask)
            adv = random_transform(adv)
            loss = sum((m(adv) * refs[n]).sum(1).mean() for n, m in attack.items())
            grad, = torch.autograd.grad(loss, pattern)
            with torch.no_grad():
                pattern -= step_size * grad.sign()
                pattern.clamp_(-1, 1)
        return apply_image(img, lm, pattern[0].detach().cpu().numpy(), eps)

    return protect


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--eps", type=float, default=8.0)
    ap.add_argument("--steps", type=int, default=150)
    ap.add_argument("--eot", type=int, default=8)
    ap.add_argument("--probes", type=int, default=150)
    ap.add_argument("--attack", default="sface,facenet", help="models the phone would carry")
    args = ap.parse_args()

    dev = "cuda"
    attack = M.load(args.attack.split(","), dev)
    start_file = RESULTS / "cloak_pattern.npy"
    start = np.load(start_file) if start_file.exists() else None
    bench = Bench(["arcface_r50", "sface", "facenet"], probes=args.probes)
    t0 = time.time()
    rows = bench.run(f"max ε{args.eps:g} ({args.steps} steps)",
                     make_protect(attack, args.eps / 255.0, args.steps, args.eot, start, dev))
    took = (time.time() - t0) / args.probes
    report = (f"# Max privacy simulation\n\nattacked: {args.attack} (arcface held out), ε{args.eps:g}, "
              f"{args.steps} steps × {args.eot} transforms, warm start: {start is not None}, "
              f"{args.probes} probes, {took:.1f} s/photo on the RX 6600\n\n" + table(rows) + "\n")
    (RESULTS / f"max_sim_{args.attack.replace(',', '+')}_eps{args.eps:g}.md").write_text(report)
    print(report)


if __name__ == "__main__":
    main()
