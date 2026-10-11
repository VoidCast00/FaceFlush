"""Renders Max privacy on one image at several strengths, for judging how visible it is.

Usage: python demo_max.py image.jpg [--eps 8,12,16] -> $LAB/results/max_demo.jpg
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
        out = make_protect(attack, e / 255.0, 150, 8, start, "cuda")(img.copy(), lm, np.random.default_rng(0))
        tiles.append(tile(out, f"max eps{e:g}"))
    cv2.imwrite(str(RESULTS / "max_demo.jpg"), np.hstack(tiles), [cv2.IMWRITE_JPEG_QUALITY, 92])
    print("wrote", RESULTS / "max_demo.jpg")


if __name__ == "__main__":
    main()
