# FaceFlush development diary

A running log of what was built, tried, trained and measured, including what didn't work.
Newest entries go at the bottom. Numbers come from the lab bench described in
[lab/README.md](../lab/README.md) unless stated otherwise.

---

## 2026-10-11: Phase 1, visible protection for photos

**Goal:** an Android app that takes a photo and makes the faces in it useless to face recognition,
all on the phone and without internet access.

**Built:**
- Kotlin + Jetpack Compose app, minimum Android 10. Package `io.github.voidcast00.faceflush`, GPL-3.0.
- Face detection on the phone with OpenCV's **YuNet** model (MIT), plus Android's built-in
  detector as a lightweight fallback.
- Five techniques, each with Light / Medium / Strong presets and fine-tuning sliders: **Pixelate, Blur,
  Solid mask, Dead pixels & noise, Feature bars** (bars over the eyes, nose bridge and mouth).
- Faces can be skipped by tapping them, and you can draw extra areas by hand.
- **Privacy:**
  - no `INTERNET` permission
  - output is re-encoded from pixels, so no EXIF/GPS metadata survives
  - the original photo is never modified
- Effects are plain Kotlin on pixel arrays, so they're unit-tested on the JVM.

**Checked:** ran every technique on a sample photo, then re-ran YuNet on the result to see if it
could still find the face. Light blur, light dead pixels and light feature bars still got found;
Medium and Strong of every technique hid the face from the detector.

**Hiccups:**
- The Xiaomi phone (HyperOS) blocks app installs over USB, so installs go through root
  (`su -c pm install`).
- A hidden "overwrite?" prompt from an interactive `cp` alias hung a build step for 7 minutes.

## 2026-10-11: UI redesign

- The preview was too big. It's now a card taking about 40% of the screen, with a fullscreen view
  (pinch to zoom). The floating "hold to compare" and "show boxes" pills became a proper toolbar.
- Visual style inspired by [Curbox](https://github.com/nethical6/curbox): near-black background,
  olive and teal accents, bold type, rounded cards.
- **Home screen background:** ASCII art generated from public-domain paintings. Ready-made ASCII
  art online has unclear copyright, so we made our own.
  - Leonardo's red-chalk self-portrait came out too faint and line-based to read as a face.
  - Vermeer's *Girl with a Pearl Earring* (lit face, dark background) works well.
  - YuNet finds her eyes and a flickering block band is drawn over them as the "disruption" mark.
  - First version misaligned the band: block glyphs come from a fallback font with a different
    width. It's now drawn on a fixed character grid with the glitch cells as squares.

## 2026-10-11: Phase 2 begins, invisible protection

**Question:** can we protect a face *without* visibly ruining the photo?

**Background, and our honest stance:**
- Recognition models turn a face into a vector ("embedding") and compare vectors.
- A small, carefully *computed* change to the pixels (an adversarial perturbation) can push that
  vector away from the person while people see almost no difference.
- This is an **arms race**. Fawkes (2020) was largely defeated by newer models within about a year.
  These techniques are a speed bump, not a guarantee, and the app has to say so.

**Decisions:**
- **F-Droid dropped** as the only target, so a real on-device ML runtime is allowed.
- Three tools:
  1. **Quick cloak:** one universal pattern trained on a PC, aligned onto each face on the phone.
     Instant.
  2. **Face morph:** a tiny trained change to face geometry. Instant.
  3. **Max privacy:** a pattern optimised for *this* photo on the phone. Minutes, runs in the
     background.
- The user chooses how visible it may be.
- Research-only models are used only on the PC, never shipped in the app.

**Lab setup:**
- Hardware: a separate PC (Ryzen 5 5500, RX 6600, CachyOS) reached over SSH through Tailscale.
- Software: PyTorch 2.9.1 on ROCm 6.4. The RX 6600 (gfx1032) needs `HSA_OVERRIDE_GFX_VERSION=10.3.0`.
- Everything the lab touches lives in one folder on that PC.
- **Data:** LFW (13,233 photos, 5,749 people). 600 people are held out for testing.
- **Models:**
  - ArcFace R50 (InsightFace): strongest, research-only, used on the PC only
  - FaceNet (MIT)
  - SFace (Apache-2.0): never used for training, so it measures how well a cloak carries over to
    an unseen model
- **The bench:**
  1. protect one photo of each test person
  2. optionally recompress it like a social network (JPEG, or a 75% downscale + JPEG)
  3. re-detect and re-align the face from scratch
  4. check whether it still matches that person's *other* clean photos at a 0.1% false-match
     threshold

**Baseline results** (match rate, lower = better protected):

| what was applied | ArcFace | SFace | FaceNet |
|---|---|---|---|
| nothing | 99% | 98% | 96% |
| faint random noise (σ8) | 99% | 98% | 96% |
| strong random noise (σ20, clearly visible) | 97% | 93% | 91% |
| light blur | 99% | 95% | 89% |
| strong blur | **74–82%** | 13–15% | 1–2% |
| pixelate, 16 blocks | 40% → **73% after social recompression** | 4–21% | 3–6% |
| pixelate, 6 blocks | 0.5% | 0.3% | 0.5% |

**Lessons:**
- Random noise does nothing. Invisible protection has to be *trained* against the models.
- The strongest model sees through heavy blur, and through light pixelation once the photo is
  downscaled (downscaling smooths the blocks back out).
- Only strong pixelation or a solid mask are near-certain. The app's blur description overpromised
  and needs correcting.

**Quick cloak training (in progress):**
- One 3×112×112 pattern in canonical face space, strength ε=10/255.
- Trained against ArcFace + FaceNet over 9,448 faces.
- Random transforms during training: alignment jitter, downscaling, blur, noise, exposure.
- Weaker on smooth skin, stronger on textured areas, to stay less visible.
- The average similarity of a cloaked face to its clean self drops from 0.94 to about 0.65, then
  plateaus. That's typical of a single universal pattern, and is why Max mode exists.

**Max privacy engine:**
- The models and their gradient were exported to one TensorFlow Lite file the phone can run
  (`lab/export_max.py`). It needs TFLite's "select TF ops", so it can't be pure LiteRT builtins.
- The TFLite gradient matches TensorFlow's (cosine 0.9966 with float16 weights, 0.9999991 with
  float32).
- **Problems hit:**
  - onnx2tf's own test-image download broke with current NumPy; we patched in synthetic images
    instead.
  - The antialiased resize (`ScaleAndTranslate`) has no TFLite kernel; replaced with a plain
    bilinear resize.
  - The file was 258 MB: two signatures stored the weights twice. Merged into one signature,
    129 MB.
  - Float16 weights didn't shrink it, because the gradient ops need float32.
  - FaceNet alone is 89 MB. Next: test whether attacking SFace alone carries over to ArcFace
    well enough to drop FaceNet.
- The phone-side optimisation loop (`MaxCloak.kt`) is written and unit-tested with a fake model.

## 2026-10-11: Quick cloak results

Trained for 4,000 steps (about 32 min on the RX 6600). The similarity to the clean face plateaued
around 0.65 after 1,000 steps. Bench, match rate (lower = better), "social" = after a 75% downscale
+ JPEG 80:

| cloak strength | PSNR | ArcFace (trained against) | SFace (held out) | FaceNet (trained against) |
|---|---|---|---|---|
| none | — | 99% | 98% | 96% |
| ε4 | 44.9 dB | 99% | 98% | 94% |
| ε8 | 38.9 dB | 99% | 95% | 77% |
| ε12 | 35.4 dB | 94% | 80% | 43% |
| ε16 | 32.9 dB | 78% | 58% | 18% |

Recompression barely changes the numbers (e.g. ε16 ArcFace 78.8% → 78.0% after "social"), so the
robustness training worked.

**Verdict:** a single universal pattern is too weak against a strong model. ArcFace still matches
78% of faces at the most visible strength, even though it was one of the models the pattern was
trained against. It's a light extra layer, not protection on its own. Per-photo optimisation
(Max privacy) is where real invisible protection has to come from. Next: simulate it.

## 2026-10-11: Max privacy simulation

Simulated the phone's per-photo optimisation on the PC: start from the quick cloak, then 150
signed-gradient steps, each averaged over 8 random transforms, at ε8 (PSNR about 39.5 dB, hard to
see). ArcFace is **never** attacked: the phone can't ship it, so it stands in for "a strong model
we don't have". 150 test photos; match rate, lower = better:

| models attacked on the phone | ArcFace (never seen) | SFace | FaceNet | time per photo (RX 6600) |
|---|---|---|---|---|
| nothing (clean photo) | 99% | 98% | 96% | — |
| SFace only | 85% (90% social) | **1%** (1% social) | 42% (51% social) | 6.8 s |
| SFace + FaceNet | 85% (89% social) | **3%** (13% social) | **1%** (1% social) | 10.6 s |

**What this means:**
- Against the models it optimises against, per-photo cloaking works almost perfectly and survives
  recompression.
- It barely carries over to a strong model it has never seen (ArcFace 99% → 85%). This is the
  core weakness of every invisible cloak, and it is exactly how Fawkes was beaten.
- Adding FaceNet beats FaceNet too, but does **nothing** extra against ArcFace. So FaceNet (89 MB,
  about 50% slower) is not worth shipping: the phone engine will be SFace only.
- Still better than the quick cloak, which at ε8 left ArcFace at 99%.

Next: try a higher visibility (ε16) to see whether transfer improves, and look at
transfer-boosting tricks (momentum, input diversity) before building the phone side.

## 2026-10-11: App fixes

- Blur and Pixelate descriptions rewritten to match the lab: blur fools weaker AI, but a strong
  model still matched about 3 in 4 heavily blurred faces.
- The quick-cloak pattern now ships in the app (`assets/cloak_pattern.bin`, 147 KB).
- Fullscreen preview: on HyperOS the dialog's bottom edge sat under the navigation bar, so the
  compare and boxes buttons couldn't be reached. They moved into a top bar next to the close button.

## 2026-10-11: Max privacy at higher visibility, and a broken morph

**Max privacy at ε16** (SFace only, same setup as above):

| | ArcFace (never seen) | SFace (attacked) | FaceNet (never seen) | PSNR |
|---|---|---|---|---|
| ε8 | 85% (90% social) | 1% | 42% | 39.5 dB |
| ε16 | **31%** (39% social) | 0% | **2%** | 34.4 dB |

Doubling the strength makes the cloak carry over to models it never saw far better: ArcFace drops
from 85% to 31%, and FaceNet to 2% without ever being attacked. The price is visibility: a pastel
colour shimmer over the face and the background around it. ε8 is faint at phone size; ε16 is
obvious when you look for it. Rendered on Vermeer's *Girl with a Pearl Earring* (public domain)
with `lab/demo_max.py`:

![Max privacy at ε8, 12 and 16](img/max_demo.jpg)

**Face morph, first attempt: failed.** Training "worked" (similarity to the clean face fell to
0.30), but rendering it showed why:

![First morph: grotesque deformation](img/morph_v1_overshoot.jpg)

Each of the 12 control points was limited to 3 px, but Gaussian RBF interpolation between close
points (eyes, brows, mouth corners) overshoots, so skin between them moved up to 14 px. The
optimiser happily used that. Fix: cap the displacement of *every pixel* (`morph.limit`), not just
the control points. Retraining. (The bench at the end of the run also crashed on float64 maps
passed to `cv2.remap`; fixed.)

## 2026-10-11: Max privacy on the phone

- The engine is now SFace only: `max_cloak.tflite`, 75 MB (was 129 MB with FaceNet). Its gradient
  matches TensorFlow's (cosine 0.999999).
- Runs on the Redmi Note 13 Pro+ through TFLite 2.16.1 with the Flex delegate (72 of 445 ops need
  TensorFlow kernels). One model call takes about 0.25 s, so a face (150 steps × 8 transforms,
  same as the PC simulation) takes about 5 minutes.
- Runs as a WorkManager foreground job with a progress notification and a Cancel button; the
  result comes back into the editor even if the app was closed in between.
- An explainer sheet with the measured numbers appears the first time Invisible is picked, and
  before every Max run the user has to tick "I understand this is not a guarantee".
- Permissions added: notifications and a data-sync foreground service. WorkManager also tried to add
  network-state and boot permissions; both are removed. Still no internet access.
- **Size problem:** the TensorFlow Flex runtime is 68 MB and the model 75 MB, so the arm64 APK is
  about 170 MB in release. A custom Flex build with only the 72 ops used should shrink the runtime
  a lot.
- The model file isn't in git (75 MB); build it with `lab/export_max.py`.

## 2026-10-11: Face morph, second attempt: works on paper, not worth it

Retrained with every pixel capped at 3 canonical px (about 8% of the eye distance). Similarity to
the clean face plateaued at 0.64, about the same as the quick cloak. Bench (match rate, lower =
better):

| morph amount | PSNR | ArcFace (trained against) | SFace (held out) | FaceNet (trained against) |
|---|---|---|---|---|
| ×0.5 | 34.6 dB | 99% | 97% | 92% |
| ×1 | 29.7 dB | 97% | 95% | 70% |
| ×1.5 | 27.3 dB | 88% | 85% | 40% |

![Second morph at ×0.5, ×1 and ×1.5](img/morph_v2.jpg)

**Verdict:** the morph is the worst trade we've measured. It visibly changes the face (fuller mouth,
shifted eyes) while ArcFace still recognises 97% at ×1. Strong recognition models are trained to be
robust to exactly this kind of variation (expressions, angles, lens distortion). Max privacy at ε16
does far more for a comparable visual cost.

**Decision:** the morph stays in the app but is clearly marked as practically useless: off by
default, its slider is labelled "barely works", and the explainer quotes the numbers above. The
Strong preset of the cloak now uses ε16, the strength measured in the lab.

## 2026-10-11: No video

Video support is dropped from the plan. Processing every frame (and keeping faces tracked between
frames) would be a project of its own; the effort goes into making photo protection better instead.
The disabled "Choose a video (coming soon)" button is gone from the home screen.
