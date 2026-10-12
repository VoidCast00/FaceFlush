"""Renders Max privacy on one image at several strengths, for judging how visible it is.

Usage: python demo_max.py image.jpg [--eps 8,12,16] [--chroma rgb,0,0.3] [--momentum 0.9] [--ti 2]
        [--out max_demo.jpg] -> $LAB/results/<out>
One tile per strength and colour setting ("rgb" = no colour limit).
Use a public-domain picture (e.g. Vermeer), never a photo of a real person from the dataset.
"""
from __future__ import annotations

import argparse

import cv2
import numpy as np

from faceflush_lab import models as M
from faceflush_lab.align import largest_face
from faceflush_lab.paths import RESULTS
from sim_max import make_protect


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("image")
    ap.add_argument("--eps", default="8,12,16")
    ap.add_argument("--attack", default="sface")
    ap.add_argument("--chroma", default="rgb", help="comma list; rgb = no colour limit")
    ap.add_argument("--momentum", type=float, default=0.0)
    ap.add_argument("--ti", type=float, default=0.0)
    ap.add_argument("--jnd", default="", help="comma list of JND multipliers; one tile each (overrides --chroma columns)")
    ap.add_argument("--out", default="max_demo.jpg")
    args = ap.parse_args()

    img = cv2.imread(args.image)
    face = largest_face(img)
    lm = face.landmarks.astype(np.float64)
    attack = M.load(args.attack.split(","), "cuda")
    start = np.load(RESULTS / "cloak_pattern.npy")
    x, y, w, h = face.box
    pad = 0.35 * w
    x0, y0 = int(max(0, x - pad)), int(max(0, y - pad))
    x1, y1 = int(min(img.shape[1], x + w + pad)), int(min(img.shape[0], y + h + pad))

    def tile(im, label):
        t = im[y0:y1, x0:x1]
        t = cv2.resize(t, (400, int(400 * t.shape[0] / t.shape[1])), interpolation=cv2.INTER_AREA)
        cv2.putText(t, label, (10, 32), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (255, 255, 255), 2)
        return t

    tiles = [tile(img, "original")]
    for e in [float(v) for v in args.eps.split(",")]:
        variants = ([(None if args.chroma == "rgb" else float(args.chroma), float(j)) for j in args.jnd.split(",")]
                    if args.jnd else [(None if c == "rgb" else float(c), None) for c in args.chroma.split(",")])
        for chroma, jnd in variants:
            protect = make_protect(attack, e / 255.0, 150, 8, start, "cuda",
                                   momentum=args.momentum, ti=args.ti, chroma=chroma, jnd=jnd)
            out = protect(img.copy(), lm, np.random.default_rng(0))
            label = f"eps{e:g} " + ("rgb" if chroma is None else f"colour {chroma:g}") + (f" jnd{jnd:g}" if jnd else "")
            tiles.append(tile(out, label))
    cols = len(variants)
    blank = np.zeros_like(tiles[0])
    rows = [np.hstack([tiles[0]] + [blank] * (cols - 1))] if cols > 1 else []
    body = tiles[1:] if cols > 1 else tiles
    rows += [np.hstack(body[i:i + cols]) for i in range(0, len(body), cols)] if cols > 1 else [np.hstack(body)]
    cv2.imwrite(str(RESULTS / args.out), np.vstack(rows), [cv2.IMWRITE_JPEG_QUALITY, 92])
    print("wrote", RESULTS / args.out)


if __name__ == "__main__":
    main()
