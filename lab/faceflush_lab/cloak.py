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


def apply_crop(x: torch.Tensor, pattern: torch.Tensor, eps: float, mask: torch.Tensor) -> torch.Tensor:
    """Training-time version on aligned crops. pattern: (3, H, W) in [-1, 1]."""
    return (x + eps * mask * texture_weight_t(x) * pattern).clamp(0, 1)


def apply_image(img_bgr: np.ndarray, landmarks: np.ndarray, pattern: np.ndarray, eps: float) -> np.ndarray:
    """App-equivalent: warp the canonical pattern onto the face in the full photo and add it.

    pattern: (3, 112, 112) RGB in [-1, 1]. Returns a new uint8 BGR image.
    """
    h, w = img_bgr.shape[:2]
    m = similarity_transform(landmarks, TEMPLATE_112)  # image -> canonical
    flags = cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP
    layers = np.dstack([pattern.transpose(1, 2, 0), edge_mask()[:, :, None]]).astype(np.float32)
    warped = cv2.warpAffine(layers, m, (w, h), flags=flags, borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    p_rgb, mask = warped[:, :, :3], warped[:, :, 3:4]

    scale = np.sqrt(abs(np.linalg.det(m[:, :2])))  # canonical px per image px
    window = max(3, int(round(TEXTURE_WINDOW / scale)) | 1)
    rgb = img_bgr[:, :, ::-1].astype(np.float32) / 255.0
    weight = texture_weight_np(rgb, window)[:, :, None]
    out = np.clip(rgb + eps * mask * weight * p_rgb, 0, 1)
    return (out[:, :, ::-1] * 255.0 + 0.5).astype(np.uint8)
