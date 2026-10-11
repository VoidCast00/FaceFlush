# FaceFlush

Android app that hides faces from AI and facial recognition before you share a photo.
Pick a photo, choose a technique, adjust the strength, then save or share the result.

- **Pixelate**, **Blur**, **Solid mask**: visible obscuring, the most reliable protection
- **Dead pixels & noise**: stuck pixels, colour noise and scanlines (weakest on its own)
- **Feature bars**: bars across the eyes, nose and mouth that break face detectors

Faces are found on-device with OpenCV's [YuNet](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet)
detector, or Android's built-in detector as a lightweight fallback. You can skip detected faces or draw your own areas.

## Privacy

- No internet permission: nothing can leave the device.
- Output is re-encoded from pixels, so it carries no EXIF metadata (GPS, camera model, timestamps).
- The original photo is never modified.

## Building

Requires JDK 17 and the Android SDK (platform 35).

```sh
./gradlew assembleDebug        # APKs per ABI in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # effect unit tests
```

## Roadmap

1. Photos (done)
2. Video
3. Adversarial "cloaking" (near-invisible perturbations)
4. F-Droid release

## Credits

Home screen background: ASCII rendering of Johannes Vermeer's *Girl with a Pearl Earring* (c. 1665, public domain),
generated with [tools/make_ascii_portrait.py](tools/make_ascii_portrait.py). Visual style inspired by
[Curbox](https://github.com/nethical6/curbox).

## License

GPL-3.0-or-later, see [LICENSE](LICENSE). The YuNet model is MIT-licensed, see
[licenses/YuNet-MIT-LICENSE.txt](licenses/YuNet-MIT-LICENSE.txt).
