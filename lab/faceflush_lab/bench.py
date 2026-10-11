"""Measures how well a protection hides people from face recognition.

Threat model (face search engines, social media matching): the attacker holds *other*, clean
photos of the person and checks whether the protected photo matches them.

For every test identity, one photo is the probe. It gets protected, optionally recompressed the
way social networks do, then re-detected and re-aligned from scratch (the attacker runs their own
detector). The remaining photos of every test identity form the clean gallery.

Reported per recognition model:
  match   share of probes that still verify against their own gallery photos at a threshold
          set for a 0.1% false match rate on clean photos (lower = better protection)
  rank1   share of probes whose nearest gallery photo is the right person (search-engine hit)
  found   share of protected probes in which a face was still detected at all
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Callable

import cv2
import numpy as np
import torch

from . import models as M
from .align import align, largest_face
from .paths import DATA

Protect = Callable[[np.ndarray, np.ndarray, np.random.Generator], np.ndarray]

GALLERY_PER_ID = 10
FAR = 1e-3


def jpeg(img: np.ndarray, q: int) -> np.ndarray:
    ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, q])
    return cv2.imdecode(buf, cv2.IMREAD_COLOR)


def social(img: np.ndarray) -> np.ndarray:
    """Roughly what a social network does: downscale, then JPEG at moderate quality."""
    h, w = img.shape[:2]
    small = cv2.resize(img, (int(w * 0.75), int(h * 0.75)), interpolation=cv2.INTER_AREA)
    return jpeg(small, 80)


POSTS: dict[str, Callable[[np.ndarray], np.ndarray]] = {
    "none": lambda img: img,
    "jpeg75": lambda img: jpeg(img, 75),
    "social": social,
}


def to_tensor(crops_bgr: list[np.ndarray], device: str) -> torch.Tensor:
    arr = np.stack([c[:, :, ::-1] for c in crops_bgr]).astype(np.float32) / 255.0
    return torch.from_numpy(arr).permute(0, 3, 1, 2).contiguous().to(device)


@torch.no_grad()
def embed(model: M.Embedder, crops_bgr: list[np.ndarray], device: str, batch: int = 128) -> torch.Tensor:
    out = [model(to_tensor(crops_bgr[i:i + batch], device)) for i in range(0, len(crops_bgr), batch)]
    return torch.cat(out)


def psnr(a: np.ndarray, b: np.ndarray) -> float:
    mse = np.mean((a.astype(np.float32) - b.astype(np.float32)) ** 2)
    return 99.0 if mse == 0 else float(10 * np.log10(255.0**2 / mse))


@dataclass
class Row:
    method: str
    post: str
    model: str
    match: float
    rank1: float
    found: float
    psnr: float


class Bench:
    def __init__(self, model_names: list[str], device: str = "cuda", probes: int | None = None):
        self.device = device
        self.models = M.load(model_names, device)
        idx = np.load(DATA / "index.npz")
        test = idx["split"] == "test"
        paths, ids, lms = idx["path"][test], idx["identity"][test], idx["landmarks"][test]

        self.probe_paths, self.probe_lms, self.probe_ids = [], [], []
        gal_paths, gal_lms, gal_ids = [], [], []
        for name in np.unique(ids):
            sel = np.where(ids == name)[0]
            self.probe_paths.append(paths[sel[0]])
            self.probe_lms.append(lms[sel[0]])
            self.probe_ids.append(name)
            for j in sel[1:1 + GALLERY_PER_ID]:
                gal_paths.append(paths[j])
                gal_lms.append(lms[j])
                gal_ids.append(name)
        if probes:
            self.probe_paths = self.probe_paths[:probes]
            self.probe_lms = self.probe_lms[:probes]
            self.probe_ids = self.probe_ids[:probes]
        self.probe_ids = np.array(self.probe_ids)
        self.gal_ids = np.array(gal_ids)

        gal_crops = [align(cv2.imread(str(DATA / p)), lm)[0] for p, lm in zip(gal_paths, gal_lms)]
        self.gallery = {n: embed(m, gal_crops, device) for n, m in self.models.items()}

        # Thresholds for a 0.1% false match rate, from clean probes against other people's photos
        clean_probe = [align(cv2.imread(str(DATA / p)), lm)[0] for p, lm in zip(self.probe_paths, self.probe_lms)]
        self.thresholds = {}
        impostor = torch.from_numpy(self.probe_ids[:, None] != self.gal_ids[None, :]).to(device)
        for n, m in self.models.items():
            sims = embed(m, clean_probe, device) @ self.gallery[n].T
            self.thresholds[n] = float(torch.quantile(sims[impostor].float()[:2_000_000], 1 - FAR))

    def run(self, method: str, protect: Protect, posts: list[str] = list(POSTS), seed: int = 0) -> list[Row]:
        rng = np.random.default_rng(seed)
        crops: dict[str, list] = {p: [] for p in posts}
        found: dict[str, list] = {p: [] for p in posts}
        psnrs = []
        for path, lm in zip(self.probe_paths, self.probe_lms):
            img = cv2.imread(str(DATA / path))
            out = protect(img.copy(), lm.astype(np.float64), rng)
            psnrs.append(psnr(img, out))
            for p in posts:
                shown = POSTS[p](out)
                face = largest_face(shown)
                found[p].append(face is not None)
                crops[p].append(align(shown, face.landmarks)[0] if face is not None else None)

        rows = []
        same = torch.from_numpy(self.probe_ids[:, None] == self.gal_ids[None, :]).to(self.device)
        for p in posts:
            ok = np.array(found[p])
            for n, m in self.models.items():
                match = np.zeros(len(ok), bool)
                rank1 = np.zeros(len(ok), bool)
                if ok.any():
                    e = embed(m, [c for c in crops[p] if c is not None], self.device)
                    sims = e @ self.gallery[n].T
                    s = same[torch.from_numpy(ok).to(self.device)]
                    best_same = torch.where(s, sims, torch.full_like(sims, -2)).max(1).values
                    match[ok] = (best_same >= self.thresholds[n]).cpu().numpy()
                    rank1[ok] = s.gather(1, sims.argmax(1, keepdim=True)).squeeze(1).cpu().numpy()
                rows.append(Row(method, p, n, match.mean(), rank1.mean(), ok.mean(), float(np.mean(psnrs))))
        return rows


def table(rows: list[Row]) -> str:
    lines = ["| method | post | model | match ↓ | rank-1 ↓ | face found | PSNR dB ↑ |", "|---|---|---|---|---|---|---|"]
    for r in rows:
        lines.append(f"| {r.method} | {r.post} | {r.model} | {r.match:.1%} | {r.rank1:.1%} | {r.found:.1%} | {r.psnr:.1f} |")
    return "\n".join(lines)
