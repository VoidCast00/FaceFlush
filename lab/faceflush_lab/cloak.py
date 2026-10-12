"""Applying a cloak pattern, in the canonical crop (for training) and in the full photo (like the app).

A pattern p lives in canonical 112x112 face space with values in [-1, 1]. It is added as

    x' = clip(x + eps * edge_mask * texture_weight(x) * p)

edge_mask fades the pattern out towards the crop border so no square edge shows. texture_weight
lowers the strength on smooth areas (skin, background) where noise is easy to see, and keeps it at
full strength in busy areas (hair, eyes, beard). The app implements the same formula in Cloak.kt.
"""
from __future__ import annotations

import cv2
import numpy as np
import torch
import torch.nn.functional as F

from .align import SIZE, similarity_transform, TEMPLATE_112

EDGE_FEATHER = 10.0  # px in canonical space
TEXTURE_WINDOW = 5  # px in canonical space (about 1/7 of the eye distance)
TEXTURE_FLOOR = 0.35
TEXTURE_FULL = 0.04  # local luminance std (0..1) at which the pattern reaches full strength


def edge_mask(size: int = SIZE) -> np.ndarray:
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32) + 0.5
    d = np.minimum.reduce([xx, yy, size - xx, size - yy])
    return np.clip(d / EDGE_FEATHER, 0, 1)


def texture_weight_t(x: torch.Tensor, window: int = TEXTURE_WINDOW) -> torch.Tensor:
    """x: (N, 3, H, W) RGB in [0, 1] -> (N, 1, H, W) weights in [TEXTURE_FLOOR, 1]."""
    lum = (0.299 * x[:, 0:1] + 0.587 * x[:, 1:2] + 0.114 * x[:, 2:3])
    pad = window // 2
    mean = F.avg_pool2d(F.pad(lum, (pad,) * 4, mode="replicate"), window, 1)
    mean_sq = F.avg_pool2d(F.pad(lum * lum, (pad,) * 4, mode="replicate"), window, 1)
    std = (mean_sq - mean * mean).clamp_min(0).sqrt()
    return (TEXTURE_FLOOR + (1 - TEXTURE_FLOOR) * std / TEXTURE_FULL).clamp(max=1.0)


def texture_weight_np(img_rgb01: np.ndarray, window: int) -> np.ndarray:
    lum = img_rgb01 @ np.array([0.299, 0.587, 0.114], dtype=np.float32)
    mean = cv2.blur(lum, (window, window), borderType=cv2.BORDER_REPLICATE)
    mean_sq = cv2.blur(lum * lum, (window, window), borderType=cv2.BORDER_REPLICATE)
    std = np.sqrt(np.clip(mean_sq - mean * mean, 0, None))
    return np.clip(TEXTURE_FLOOR + (1 - TEXTURE_FLOOR) * std / TEXTURE_FULL, None, 1.0)


def jnd_t(x: torch.Tensor) -> torch.Tensor:
    """Just-noticeable brightness change per pixel, in 0-255 levels (classic pixel-domain model).

    x: (N, 3, H, W) RGB in [0, 1] -> (N, 1, H, W). Luminance adaptation (Chou & Li: changes hide
    better in dark and very bright areas than in mid-grey) combined with contrast masking (Yang et
    al.: changes hide next to edges and texture), with the usual overlap term (0.3).
    """
    lum = 255 * (0.299 * x[:, 0:1] + 0.587 * x[:, 1:2] + 0.114 * x[:, 2:3])
    k = torch.tensor([1.0, 4.0, 6.0, 4.0, 1.0], device=x.device, dtype=x.dtype) / 16
    bg = F.conv2d(F.pad(lum, (2, 2, 0, 0), mode="replicate"), k.view(1, 1, 1, 5))
    bg = F.conv2d(F.pad(bg, (0, 0, 2, 2), mode="replicate"), k.view(1, 1, 5, 1))
    la = torch.where(bg <= 127, 17 * (1 - torch.sqrt(bg.clamp_min(0) / 127)) + 3, 3 / 128 * (bg - 127) + 3)
    sobel = torch.tensor([[-1.0, 0, 1], [-2, 0, 2], [-1, 0, 1]], device=x.device, dtype=x.dtype)
    padded = F.pad(lum, (1, 1, 1, 1), mode="replicate")
    gx = F.conv2d(padded, sobel.view(1, 1, 3, 3)).abs()
    gy = F.conv2d(padded, sobel.t().reshape(1, 1, 3, 3)).abs()
    cm = 0.117 * torch.maximum(gx, gy) / 4  # Sobel / 4 = step height in levels
    return la + cm - 0.3 * torch.minimum(la, cm)


def apply_crop(x: torch.Tensor, pattern: torch.Tensor, eps: float, mask: torch.Tensor,
               weight: torch.Tensor | None = None) -> torch.Tensor:
    """Training-time version on aligned crops. pattern: (3, H, W) in [-1, 1].
    weight: optional per-pixel strength (1, 1, H, W) replacing the texture weight."""
    w = texture_weight_t(x) if weight is None else weight
    return (x + eps * mask * w * pattern).clamp(0, 1)


def apply_image(img_bgr: np.ndarray, landmarks: np.ndarray, pattern: np.ndarray, eps: float,
                weight: np.ndarray | None = None) -> np.ndarray:
    """App-equivalent: warp the canonical pattern onto the face in the full photo and add it.

    pattern: (3, 112, 112) RGB in [-1, 1]. weight: optional canonical (112, 112) strength map that
    replaces the texture weight (warped along with the pattern). Returns a new uint8 BGR image.
    """
    h, w = img_bgr.shape[:2]
    m = similarity_transform(landmarks, TEMPLATE_112)  # image -> canonical
    flags = cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP
    mask_c = edge_mask() if weight is None else edge_mask() * weight
    layers = np.dstack([pattern.transpose(1, 2, 0), mask_c[:, :, None]]).astype(np.float32)
    warped = cv2.warpAffine(layers, m, (w, h), flags=flags, borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    p_rgb, mask = warped[:, :, :3], warped[:, :, 3:4]
    rgb = img_bgr[:, :, ::-1].astype(np.float32) / 255.0
    if weight is not None:
        out = np.clip(rgb + eps * mask * p_rgb, 0, 1)
        return (out[:, :, ::-1] * 255.0 + 0.5).astype(np.uint8)

    scale = np.sqrt(abs(np.linalg.det(m[:, :2])))  # canonical px per image px
    window = max(3, int(round(TEXTURE_WINDOW / scale)) | 1)
    weight = texture_weight_np(rgb, window)[:, :, None]
    out = np.clip(rgb + eps * mask * weight * p_rgb, 0, 1)
    return (out[:, :, ::-1] * 255.0 + 0.5).astype(np.uint8)
