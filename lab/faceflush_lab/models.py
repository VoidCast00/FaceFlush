"""Face recognition models wrapped as differentiable PyTorch modules.

Every embedder takes RGB images in [0, 1], shaped (N, 3, 112, 112) and aligned to the ArcFace
template, and returns L2-normalised embeddings. Each model's own preprocessing happens inside, so
gradients flow straight back to the pixels the app will change.

Licences:
  arcface_r50  InsightFace buffalo_l w600k_r50: non-commercial research only, used here on the PC only
  sface        OpenCV zoo SFace 2021dec: Apache-2.0
  facenet      facenet-pytorch InceptionResnetV1 (VGGFace2): MIT
"""
from __future__ import annotations

import torch
import torch.nn as nn
import torch.nn.functional as F

from .paths import MODELS


class Embedder(nn.Module):
    name = "base"

    def preprocess(self, x: torch.Tensor) -> torch.Tensor:
        raise NotImplementedError

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return F.normalize(self.net(self.preprocess(x)), dim=1)


class OnnxEmbedder(Embedder):
    def __init__(self, file: str, name: str, scale: float, mean: float, std: float):
        super().__init__()
        from onnx2torch import convert

        self.name = name
        self.net = convert(str(MODELS / file))
        self.scale, self.mean, self.std = scale, mean, std

    def preprocess(self, x):
        return (x * self.scale - self.mean) / self.std


def arcface_r50() -> Embedder:
    # InsightFace recognition models expect RGB, (x - 127.5) / 127.5
    return OnnxEmbedder("w600k_r50.onnx", "arcface_r50", 255.0, 127.5, 127.5)


def sface() -> Embedder:
    # OpenCV's FaceRecognizerSF feeds raw RGB 0..255 (blobFromImage with swapRB, no mean/scale)
    return OnnxEmbedder("face_recognition_sface_2021dec.onnx", "sface", 255.0, 0.0, 1.0)


class FaceNet(Embedder):
    name = "facenet"

    def __init__(self):
        super().__init__()
        from facenet_pytorch import InceptionResnetV1

        self.net = InceptionResnetV1(pretrained="vggface2")

    def preprocess(self, x):
        # Trained on 160x160 crops, standardised as (x - 127.5) / 128
        x = F.interpolate(x, size=(160, 160), mode="bilinear", align_corners=False)
        return (x * 255.0 - 127.5) / 128.0


def facenet() -> Embedder:
    return FaceNet()


ALL = {"arcface_r50": arcface_r50, "sface": sface, "facenet": facenet}


def load(names: list[str], device: str) -> dict[str, Embedder]:
    out = {}
    for n in names:
        m = ALL[n]().to(device).eval()
        for p in m.parameters():
            p.requires_grad_(False)
        out[n] = m
    return out
