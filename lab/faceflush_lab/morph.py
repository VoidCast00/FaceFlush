"""Subtle face-shape morph as a smooth displacement field in canonical face space.

A handful of control points (eyes, nose, mouth corners, cheeks, jaw, brows) can move; a ring of
anchors around the crop border stays put. Gaussian RBF interpolation turns the point moves into a
smooth field, so the warp only touches the face and fades out before the crop border.

The field is linear in the point moves, so it is precomputed as one basis field per point. The
trained result is shipped to the app as a ready-made field (2 x 112 x 112, canonical pixels) that
Morph.kt scales by the user's amount, rotates into the photo and resamples.
"""
from __future__ import annotations

import cv2
import numpy as np
import torch
import torch.nn.functional as F

from .align import SIZE, TEMPLATE_112, similarity_transform

MOVABLE = np.vstack([
    TEMPLATE_112,  # eyes, nose, mouth corners
    [[24.0, 78.0], [88.0, 78.0]],  # cheeks
    [[34.0, 102.0], [78.0, 102.0], [56.0, 110.0]],  # jaw and chin
    [[36.0, 40.0], [76.0, 40.0]],  # brows
])


def _anchors(n_side: int = 8) -> np.ndarray:
    t = np.linspace(0, SIZE, n_side, endpoint=False)
    return np.vstack([
        np.stack([t, np.zeros_like(t)], 1), np.stack([np.full_like(t, SIZE), t], 1),
        np.stack([SIZE - t, np.full_like(t, SIZE)], 1), np.stack([np.zeros_like(t), SIZE - t], 1),
    ])


ANCHORS = _anchors()
SIGMA = 16.0  # px in canonical space


def basis_fields() -> np.ndarray:
    """(n_movable, H, W): weight of each movable point's displacement at every pixel."""
    pts = np.vstack([MOVABLE, ANCHORS])
    d2 = ((pts[:, None] - pts[None]) ** 2).sum(-1)
    k = np.exp(-d2 / (2 * SIGMA**2)) + 1e-6 * np.eye(len(pts))
    yy, xx = np.mgrid[0:SIZE, 0:SIZE].astype(np.float64) + 0.5
    grid = np.stack([xx.ravel(), yy.ravel()], 1)
    phi = np.exp(-((grid[:, None] - pts[None]) ** 2).sum(-1) / (2 * SIGMA**2))  # (HW, n)
    coef = phi @ np.linalg.inv(k)  # interpolation weights: field = coef @ point_moves
    return coef[:, : len(MOVABLE)].T.reshape(len(MOVABLE), SIZE, SIZE).astype(np.float32)


def field_from_moves(basis: torch.Tensor, moves: torch.Tensor) -> torch.Tensor:
    """basis (n, H, W), moves (n, 2) in px -> field (2, H, W): displacement of content at each pixel."""
    return torch.einsum("nhw,nc->chw", basis, moves)


def apply_crop(x: torch.Tensor, field: torch.Tensor) -> torch.Tensor:
    """Moves image content by `field` (canonical px). Backward sampling: out(v) = x(v - field(v))."""
    n = x.shape[0]
    yy, xx = torch.meshgrid(
        torch.arange(SIZE, device=x.device, dtype=x.dtype) + 0.5,
        torch.arange(SIZE, device=x.device, dtype=x.dtype) + 0.5, indexing="ij")
    sx = (xx - field[0]) / SIZE * 2 - 1
    sy = (yy - field[1]) / SIZE * 2 - 1
    grid = torch.stack([sx, sy], -1)[None].expand(n, -1, -1, -1)
    return F.grid_sample(x, grid, mode="bilinear", padding_mode="border", align_corners=False)


def apply_image(img_bgr: np.ndarray, landmarks: np.ndarray, field: np.ndarray, amount: float) -> np.ndarray:
    """App-equivalent: warp the photo around the face with the canonical field scaled by `amount`."""
    h, w = img_bgr.shape[:2]
    m = similarity_transform(landmarks, TEMPLATE_112)  # image -> canonical
    a = m[:, :2]
    a_inv = np.linalg.inv(a)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    cx = m[0, 0] * xx + m[0, 1] * yy + m[0, 2]
    cy = m[1, 0] * xx + m[1, 1] * yy + m[1, 2]
    fx = cv2.remap(field[0], cx - 0.5, cy - 0.5, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    fy = cv2.remap(field[1], cx - 0.5, cy - 0.5, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    # Displacement from canonical px back to image px
    dx = (a_inv[0, 0] * fx + a_inv[0, 1] * fy) * amount
    dy = (a_inv[1, 0] * fx + a_inv[1, 1] * fy) * amount
    return cv2.remap(img_bgr, xx - dx, yy - dy, cv2.INTER_LINEAR, borderMode=cv2.BORDER_REFLECT)
