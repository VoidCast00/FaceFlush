"""Face detection and 5-point alignment, mirroring what the app does on the phone.

The app finds faces with the same YuNet model and maps its 5 landmarks onto the ArcFace 112x112
template with the same least-squares similarity transform (Umeyama), so a pattern made here in
"canonical face space" lands on the same spot when the phone warps it back onto a face.
"""
from __future__ import annotations

from dataclasses import dataclass

import cv2
import numpy as np

from .paths import MODELS

# ArcFace / InsightFace 112x112 template. Order matches YuNet: eye, eye, nose, mouth, mouth,
# each pair listed left-to-right as seen in the image.
TEMPLATE_112 = np.array(
    [
        [38.2946, 51.6963],
        [73.5318, 51.5014],
        [56.0252, 71.7366],
        [41.5493, 92.3655],
        [70.7299, 92.2041],
    ],
    dtype=np.float64,
)
SIZE = 112

YUNET = MODELS / "face_detection_yunet_2023mar.onnx"


@dataclass
class Face:
    box: np.ndarray  # x, y, w, h
    landmarks: np.ndarray  # (5, 2)
    score: float


def detect(image_bgr: np.ndarray, score_threshold: float = 0.6) -> list[Face]:
    h, w = image_bgr.shape[:2]
    det = cv2.FaceDetectorYN.create(str(YUNET), "", (w, h), score_threshold, 0.3, 5000)
    _, faces = det.detect(image_bgr)
    if faces is None:
        return []
    return [Face(f[:4].copy(), f[4:14].reshape(5, 2).astype(np.float64), float(f[14])) for f in faces]


def similarity_transform(src: np.ndarray, dst: np.ndarray) -> np.ndarray:
    """Least-squares similarity (rotation, uniform scale, translation) mapping src -> dst.

    Closed form of Umeyama (1991) in 2D, no reflection. Returns a 2x3 matrix.
    Kotlin twin: effects/Align.kt (keep the two identical).
    """
    src = np.asarray(src, dtype=np.float64)
    dst = np.asarray(dst, dtype=np.float64)
    mu_s, mu_d = src.mean(0), dst.mean(0)
    s, d = src - mu_s, dst - mu_d
    norm = (s**2).sum()
    a = (s * d).sum() / norm
    b = (s[:, 0] * d[:, 1] - s[:, 1] * d[:, 0]).sum() / norm
    rs = np.array([[a, -b], [b, a]])
    t = mu_d - rs @ mu_s
    return np.hstack([rs, t[:, None]])


def align(image_bgr: np.ndarray, landmarks: np.ndarray, size: int = SIZE) -> tuple[np.ndarray, np.ndarray]:
    """Warps the face into the canonical crop. Returns (crop_bgr, 2x3 matrix image->crop)."""
    m = similarity_transform(landmarks, TEMPLATE_112 * (size / SIZE))
    crop = cv2.warpAffine(image_bgr, m, (size, size), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REFLECT)
    return crop, m


def largest_face(image_bgr: np.ndarray) -> Face | None:
    faces = detect(image_bgr)
    return max(faces, key=lambda f: f.box[2] * f.box[3]) if faces else None
