"""Builds max_cloak.tflite: the on-device engine behind "Max privacy".

The phone optimises a cloak pattern for each face. It needs, for the current pattern, the loss
(how much the cloaked face still looks like itself to the recognition models) and the gradient of
that loss with respect to the pattern. TensorFlow's GradientTape records that computation and the
TFLite converter keeps it as an ordinary model, so the app just feeds tensors in and reads them out.

Models inside (both permissively licensed): SFace (Apache-2.0) and FaceNet VGGFace2 (MIT).

One signature (a second one would store every weight twice), images NHWC, RGB in [0, 1],
aligned 112x112 crops:
  step(x, pattern, weight, eps, ref_sface, ref_facenet, angle, scale, shift, down)
     -> loss (1,), grad (1,112,112,3), sface (1,128), facenet (1,512)
     weight = edge mask * texture weight (computed in Kotlin, as in cloak.py)
     angle/scale/shift/down = random transform for this step (re-alignment jitter, downscale)
     sface/facenet = L2-normalised embeddings of the transformed, cloaked face. Called once with
     eps=0 and the identity transform, they are the reference embeddings of the clean face.
Weights are stored as float16 to halve the size; maths still runs in float32.

Run with the TensorFlow venv: python export_max.py  -> $LAB/results/max_cloak.tflite
"""
from __future__ import annotations

import math
import shutil

import numpy as np
import onnx2tf
import tensorflow as tf

from faceflush_lab.paths import MODELS, RESULTS

SIZE = 112


def _sample_images():
    # onnx2tf downloads sample images to check its conversion; that download breaks with current
    # NumPy (pickled file), so hand it random images of the same shape instead
    return np.random.default_rng(0).random((20, 128, 128, 3), dtype=np.float32)


onnx2tf.onnx2tf.download_test_image_data = _sample_images
onnx2tf.utils.common_functions.download_test_image_data = _sample_images


def convert_onnx(name: str, onnx_file: str):
    out = MODELS / f"{name}_tf"
    if out.exists():
        shutil.rmtree(out)
    onnx2tf.convert(input_onnx_file_path=str(MODELS / onnx_file), output_folder_path=str(out),
                    output_signaturedefs=True, non_verbose=True)
    loaded = tf.saved_model.load(str(out))
    return loaded, loaded.signatures["serving_default"]


def l2(x):
    return x / tf.norm(x, axis=1, keepdims=True)


def transform(x, angle, scale, shift, down):
    """Rotate/scale/shift about the centre, then downscale by `down` and back up (differentiable)."""
    c, s = tf.cos(angle[0]) / scale[0], tf.sin(angle[0]) / scale[0]
    half = SIZE / 2.0
    # Projective transform maps output pixel -> input pixel
    tx = half - c * half + s * half + shift[0] * half
    ty = half - s * half - c * half + shift[1] * half
    m = tf.stack([c, -s, tx, s, c, ty, 0.0, 0.0])[None]
    x = tf.raw_ops.ImageProjectiveTransformV3(images=x, transforms=m, output_shape=[SIZE, SIZE],
                                              interpolation="BILINEAR", fill_mode="REFLECT", fill_value=0.0)
    small = tf.cast(tf.round(SIZE * down[0]), tf.int32)
    # Plain bilinear: the antialiased resize (ScaleAndTranslate) has no TFLite or Flex kernel
    x = tf.image.resize(tf.image.resize(x, [small, small]), [SIZE, SIZE])
    return tf.clip_by_value(x, 0.0, 1.0)


def main():
    sface_mod, sface_fn = convert_onnx("sface", "face_recognition_sface_2021dec.onnx")
    facenet_mod, facenet_fn = convert_onnx("facenet", "facenet_vggface2.onnx")
    sface_in = list(sface_fn.structured_input_signature[1].keys())[0]
    facenet_in = list(facenet_fn.structured_input_signature[1].keys())[0]
    print("inputs:", sface_fn.structured_input_signature, facenet_fn.structured_input_signature)

    def embed_both(x):
        es = list(sface_fn(**{sface_in: x * 255.0}).values())[0]
        f = tf.image.resize(x, [160, 160])
        ef = list(facenet_fn(**{facenet_in: (f * 255.0 - 127.5) / 128.0}).values())[0]
        return l2(tf.reshape(es, [1, -1])), l2(tf.reshape(ef, [1, -1]))

    img = tf.TensorSpec([1, SIZE, SIZE, 3], tf.float32)

    class MaxCloak(tf.Module):
        def __init__(self):
            super().__init__()
            self.keep = [sface_mod, facenet_mod]

        @tf.function(input_signature=[
            img, img, tf.TensorSpec([1, SIZE, SIZE, 1], tf.float32), tf.TensorSpec([1], tf.float32),
            tf.TensorSpec([1, 128], tf.float32), tf.TensorSpec([1, 512], tf.float32),
            tf.TensorSpec([1], tf.float32), tf.TensorSpec([1], tf.float32), tf.TensorSpec([2], tf.float32),
            tf.TensorSpec([1], tf.float32),
        ])
        def step(self, x, pattern, weight, eps, ref_sface, ref_facenet, angle, scale, shift, down):
            with tf.GradientTape() as tape:
                tape.watch(pattern)
                adv = tf.clip_by_value(x + eps[0] * weight * pattern, 0.0, 1.0)
                s, f = embed_both(transform(adv, angle, scale, shift, down))
                loss = tf.reduce_sum(s * ref_sface) + tf.reduce_sum(f * ref_facenet)
            return {"loss": tf.reshape(loss, [1]), "grad": tape.gradient(loss, pattern), "sface": s, "facenet": f}

    module = MaxCloak()
    saved = RESULTS / "max_cloak_saved"
    if saved.exists():
        shutil.rmtree(saved)
    tf.saved_model.save(module, str(saved), signatures={"step": module.step})

    conv = tf.lite.TFLiteConverter.from_saved_model(str(saved), signature_keys=["step"])
    conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS, tf.lite.OpsSet.SELECT_TF_OPS]
    conv.experimental_enable_resource_variables = True
    conv.optimizations = [tf.lite.Optimize.DEFAULT]
    conv.target_spec.supported_types = [tf.float16]
    out = RESULTS / "max_cloak.tflite"
    out.write_bytes(conv.convert())
    print("wrote", out, out.stat().st_size // 1024, "KiB")

    # Sanity check: the TFLite gradient should agree with TensorFlow's
    interp = tf.lite.Interpreter(model_path=str(out))
    step = interp.get_signature_runner("step")
    rng = np.random.default_rng(0)
    x = rng.random((1, SIZE, SIZE, 3), dtype=np.float32)
    pattern = rng.uniform(-1, 1, (1, SIZE, SIZE, 3)).astype(np.float32)
    ident = dict(angle=np.zeros(1, np.float32), scale=np.ones(1, np.float32), shift=np.zeros(2, np.float32),
                 down=np.ones(1, np.float32))
    clean = step(x=x, pattern=pattern, weight=np.ones((1, SIZE, SIZE, 1), np.float32), eps=np.zeros(1, np.float32),
                 ref_sface=np.zeros((1, 128), np.float32), ref_facenet=np.zeros((1, 512), np.float32), **ident)
    args = dict(x=x, pattern=pattern, weight=np.ones((1, SIZE, SIZE, 1), np.float32), eps=np.array([8 / 255], np.float32),
                ref_sface=clean["sface"], ref_facenet=clean["facenet"],
                angle=np.array([math.radians(3)], np.float32), scale=np.array([1.02], np.float32),
                shift=np.array([0.01, -0.02], np.float32), down=np.array([0.7], np.float32))
    lite = step(**args)
    ref = module.step(**{k: tf.constant(v) for k, v in args.items()})
    print("loss tflite / tf:", lite["loss"], ref["loss"].numpy())
    g1, g2 = lite["grad"].ravel(), ref["grad"].numpy().ravel()
    print("grad cosine tflite vs tf:", float(g1 @ g2 / (np.linalg.norm(g1) * np.linalg.norm(g2))))


if __name__ == "__main__":
    main()
