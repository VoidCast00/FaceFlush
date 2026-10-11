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
