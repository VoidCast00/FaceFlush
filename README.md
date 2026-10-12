# FaceFlush

> [!WARNING]
> **This is a vibe-coded app.** It was built largely by an AI coding assistant (Claude Code)
> under one person's direction, not by a security team. It hasn't been independently audited, and
> it can contain bugs or give a false sense of safety.
>
> No protection against face recognition is guaranteed. The "invisible" techniques especially are
> an arms race, and newer models can learn to see through them. For photos that really matter,
> use strong pixelation or a solid mask, or don't share the photo.

Android app that hides faces from AI and facial recognition before you share a photo.
Pick a photo, choose a technique, adjust the strength, then save or share the result.

- **Solid mask**, **Pixelate** (Strong): visible obscuring, the most reliable protection we measured
- **Blur**: only good at Strong, and even then the strongest model we tested still recognised most
  faces (see [docs/DIARY.md](docs/DIARY.md))
- **Dead pixels & noise**: stuck pixels, colour noise and scanlines (weakest on its own)
- **Feature bars**: bars across the eyes, nose and mouth that break face detectors
- **Invisible cloak** (in development, its own half of the app): trained brightness changes, optimised per
  face by Max privacy (visible smudges at the default strength; see [docs/DIARY.md](docs/DIARY.md)). Rainbow
  style and face shape change are "fun extras" that change the look but add no protection.

**Remove metadata only** saves clean copies of photos (location, camera, date, comments, thumbnails,
motion-photo video removed) without re-compressing JPEG or PNG, so the picture stays exactly the same.

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

## How it was made

[docs/DIARY.md](docs/DIARY.md) is a running log of what was built, tried, trained and measured,
including what didn't work. [lab/](lab/) holds the scripts that train and benchmark the invisible
techniques on a PC with a GPU.

## Roadmap

1. Photos with visible techniques (done)
2. Invisible cloak: quick pattern, face morph, on-device "Max privacy" (in progress)

Video is not planned: running all of this on every frame is a project of its own.

## Credits

Home screen background: ASCII rendering of Johannes Vermeer's *Girl with a Pearl Earring* (c. 1665, public domain),
generated with [tools/make_ascii_portrait.py](tools/make_ascii_portrait.py); the same portrait animates the
technique cards, progress and loading screens. The pictures in the "How well does it work?" sheet are the same
painting run through Max privacy in the lab. Visual style inspired by [Curbox](https://github.com/nethical6/curbox).

## License

GPL-3.0-or-later, see [LICENSE](LICENSE). The YuNet model is MIT-licensed, see
[licenses/YuNet-MIT-LICENSE.txt](licenses/YuNet-MIT-LICENSE.txt).
