"""Exports FaceNet (facenet-pytorch, MIT) to ONNX so export_max.py can convert it to TensorFlow.

Run with the PyTorch venv: python export_facenet_onnx.py  -> $LAB/models/facenet_vggface2.onnx
Input: (1, 3, 160, 160) RGB standardised as (x * 255 - 127.5) / 128. Output: (1, 512), not normalised.
"""
import torch
from facenet_pytorch import InceptionResnetV1

from faceflush_lab.paths import MODELS


def main():
    net = InceptionResnetV1(pretrained="vggface2").eval()
    dummy = torch.randn(1, 3, 160, 160)
    torch.onnx.export(net, dummy, str(MODELS / "facenet_vggface2.onnx"), input_names=["input"],
                      output_names=["embedding"], opset_version=17, dynamo=False)
    print("wrote", MODELS / "facenet_vggface2.onnx")


if __name__ == "__main__":
    main()
