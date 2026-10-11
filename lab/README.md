# FaceFlush lab

Offline experiments that train and measure the "Invisible" technique. They run on a PC with a GPU,
not on the phone. Everything (venv, caches, models, data, results) lives in `$LAB`
(default `~/AI_Lab-FaceTraining`).

## Setup (Linux, AMD GPU via ROCm)

```sh
cd $LAB
uv venv --python 3.12 .venv
uv pip install --python .venv/bin/python torch==2.9.1 torchvision --index-url https://download.pytorch.org/whl/rocm6.4
uv pip install --python .venv/bin/python numpy opencv-python-headless pillow scipy scikit-learn tqdm onnx onnxruntime lpips onnx2torch requests
uv pip install --python .venv/bin/python --no-deps facenet-pytorch   # it pins an old torch
export HSA_OVERRIDE_GFX_VERSION=10.3.0   # RX 6600 (gfx1032) runs the gfx1030 kernels
```

Copy `app/src/main/assets/face_detection_yunet_2023mar.onnx` to `$LAB/models/`, then:

| step | command | output |
|---|---|---|
| download models + LFW | `python fetch.py` | `models/`, `data/lfw/` |
| detect faces, split identities | `python prepare.py` | `data/index.npz` |
| baseline numbers | `python baseline.py` | `results/baseline.md` |
| train quick cloak | `python train_cloak.py` | `results/cloak_pattern.{npy,png,md}` |
| train face morph | `python train_morph.py` | `results/morph_field.{npy,md}` |
| export for the app | `python export_field.py results/cloak_pattern.npy cloak_pattern.bin` | copy to `app/src/main/assets/` |

## How results are measured

See `faceflush_lab/bench.py`. One photo per test person is protected, optionally recompressed
(`jpeg75`, `social` = 75% downscale + JPEG 80), re-detected and re-aligned, then compared with that
person's other, clean photos. `match` is the share still verified at a 0.1% false-match
threshold; `rank-1` is the share whose closest gallery photo is still the right person.
SFace is never used for training, so its numbers show how well a pattern carries over to a model
it has never seen, which is the closest we can get to an unknown commercial system.

## Models and data

| model | licence | use |
|---|---|---|
| YuNet face detector | MIT | alignment (same as the app) |
| InsightFace w600k_r50 (buffalo_l) | non-commercial research only | training/eval on the PC only, never shipped |
| FaceNet InceptionResnetV1 VGGFace2 (facenet-pytorch) | MIT | training/eval |
| OpenCV SFace 2021dec | Apache-2.0 | held-out evaluation |

LFW (Labeled Faces in the Wild) is used for training and evaluation and is never shipped.
