"""Baseline numbers: no protection, plus rough Python versions of the app's visible techniques.

Writes $LAB/results/baseline.md.
"""
from __future__ import annotations

import cv2
import numpy as np

from faceflush_lab.bench import Bench, table
from faceflush_lab.paths import RESULTS


def face_box(lm: np.ndarray, h: int, w: int, grow: float = 1.25):
    """Approximate the app's expanded face box from the landmarks."""
    eye_dist = np.linalg.norm(lm[1] - lm[0])
    cx, cy = lm[:, 0].mean(), lm[:, 1].mean()
    half = eye_dist * 1.25 * grow
    x0, x1 = int(max(0, cx - half)), int(min(w, cx + half))
    y0, y1 = int(max(0, cy - half * 1.3)), int(min(h, cy + half * 1.2))
    return x0, y0, x1, y1


def pixelate(blocks: int):
    def run(img, lm, rng):
        x0, y0, x1, y1 = face_box(lm, *img.shape[:2])
        roi = img[y0:y1, x0:x1]
        small = cv2.resize(roi, (blocks, max(1, int(blocks * roi.shape[0] / roi.shape[1]))), interpolation=cv2.INTER_AREA)
        img[y0:y1, x0:x1] = cv2.resize(small, (roi.shape[1], roi.shape[0]), interpolation=cv2.INTER_NEAREST)
        return img
    return run


def blur(radius_frac: float):
    def run(img, lm, rng):
        x0, y0, x1, y1 = face_box(lm, *img.shape[:2])
        k = int((x1 - x0) * radius_frac) * 2 + 1
        img[y0:y1, x0:x1] = cv2.GaussianBlur(img[y0:y1, x0:x1], (k, k), 0)
        return img
    return run


def noise(sigma: float):
    def run(img, lm, rng):
        x0, y0, x1, y1 = face_box(lm, *img.shape[:2])
        roi = img[y0:y1, x0:x1].astype(np.float32)
        img[y0:y1, x0:x1] = np.clip(roi + rng.normal(0, sigma, roi.shape), 0, 255).astype(np.uint8)
        return img
    return run


def main():
    bench = Bench(["arcface_r50", "sface", "facenet"])
    print("thresholds @0.1% FAR:", {k: round(v, 3) for k, v in bench.thresholds.items()})
    methods = {
        "none": lambda img, lm, rng: img,
        "noise σ8 (≈ invisible random noise)": noise(8),
        "noise σ20": noise(20),
        "blur light": blur(0.05),
        "blur strong": blur(0.15),
        "pixelate 16": pixelate(16),
        "pixelate 6": pixelate(6),
    }
    rows = []
    for name, fn in methods.items():
        rows += bench.run(name, fn)
        print(table([r for r in rows if r.method == name]), flush=True)
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "baseline.md").write_text(
        "# Baseline\n\nThresholds at 0.1% FAR: " + str({k: round(v, 3) for k, v in bench.thresholds.items()})
        + "\n\n" + table(rows) + "\n"
    )


if __name__ == "__main__":
    main()
