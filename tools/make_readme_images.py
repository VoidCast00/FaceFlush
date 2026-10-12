"""Draws the README pictures in the app's style (olive and teal on near-black, glitch blocks).

Usage: python3 tools/make_readme_images.py
Writes docs/img/readme_banner.png, readme_journey.png and readme_strengths.jpg. The numbers are the
lab results from docs/DIARY.md: matches by ArcFace (never used to make the cloak) out of 100 test
faces, after Instagram-style recompression.
"""
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "img"
BG = (18, 18, 16)
CARD = (29, 29, 25)
EMPTY = (48, 48, 41)
TEXT = (231, 229, 217)
MUTED = (169, 167, 150)
OLIVE = (216, 212, 142)
TEAL = (156, 201, 184)
FONTS = "/usr/share/fonts/TTF/"
bold = lambda s: ImageFont.truetype(FONTS + "DejaVuSans-Bold.ttf", s)
regular = lambda s: ImageFont.truetype(FONTS + "DejaVuSans.ttf", s)
mono = lambda s: ImageFont.truetype(FONTS + "DejaVuSansMono.ttf", s)
GLITCH = "█▓▒░"


def mix(c, a, bg=BG):
    return tuple(round(bg[i] + (c[i] - bg[i]) * a) for i in range(3))


def banner():
    w, h = 1280, 440
    img = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(img)
    lines = (ROOT / "app/src/main/res/raw/ascii_portrait.txt").read_text().splitlines()
    cw, ch = 6, 12
    font = mono(10)
    rng = random.Random(3)
    x0, y0 = 30, -10
    for r, line in enumerate(lines):
        fade = max(0.0, min(1.0, 1.6 - r / len(lines) * 1.6))  # fades out towards the bottom
        for c, char in enumerate(line):
            x, y = x0 + c * cw, y0 + r * ch
            if char in GLITCH:
                a = rng.choice([1.0, 0.7, 0.45, 0.2]) * fade
                d.rectangle([x, y, x + cw, y + ch], fill=mix(OLIVE, 0.85 * a))
            elif char != " ":
                d.text((x, y), char, font=font, fill=mix(TEXT, 0.3 * fade))
    tx = 560
    d.text((tx, 92), "FaceFlush", font=bold(84), fill=TEXT)
    d.text((tx + 4, 200), "Your face is data.", font=bold(34), fill=OLIVE)
    d.text((tx + 4, 256), "Hide faces from recognition AI and strip\nmetadata before a photo leaves your phone.",
           font=regular(24), fill=MUTED, spacing=8)
    x = tx + 4
    for badge in ["Offline", "On-device", "No metadata", "Open source"]:
        bw = d.textlength(badge.upper(), font=regular(15)) + 28
        d.rounded_rectangle([x, 350, x + bw, 384], 17, outline=mix(TEXT, 0.35), width=2)
        d.text((x + 14, 358), badge.upper(), font=regular(15), fill=MUTED)
        x += bw + 12
    img.save(OUT / "readme_banner.png", optimize=True)


def squares(d, x, y, count, total, size, gap, lit):
    rng = random.Random(count * 31 + total)
    for i in range(total):
        on = i < count
        col = mix(lit, rng.choice([1.0, 1.0, 1.0, 0.6])) if on else EMPTY
        d.rounded_rectangle([x + i * (size + gap), y, x + i * (size + gap) + size, y + size], 3, fill=col)


def journey():
    steps = [
        ("One pattern for everyone (quick cloak)", 99, OLIVE, False),
        ("Tuned to each photo, strength 8", 90, OLIVE, False),
        ("+ tricks from research papers", 91, OLIVE, False),
        ("Strength 16, with colour", 39, OLIVE, False),
        ("Strength 16, brightness only", 14, TEAL, True),
        ("Strength 24, brightness only", 3, TEAL, True),
    ]
    w, row = 1280, 76
    h = 170 + row * len(steps) + 30
    img = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(img)
    d.text((48, 40), "How many faces a strong AI still recognises", font=bold(36), fill=TEXT)
    d.text((48, 92), "out of 100 test faces, after Instagram-style compression. Lower is better. That AI was never used\n"
                     "to make the cloak, so it stands in for the unknown services out there.",
           font=regular(20), fill=MUTED, spacing=6)
    for k, (label, n, colour, star) in enumerate(steps):
        y = 170 + k * row
        d.rounded_rectangle([40, y, w - 40, y + row - 12], 16, fill=CARD)
        d.text((64, y + 20), f"{k + 1}", font=bold(24), fill=colour)
        d.text((100, y + 22), label, font=bold(21) if star else regular(21), fill=TEXT if star else MUTED)
        squares(d, 545, y + 23, round(n / 5), 20, 21, 4, colour)
        d.text((w - 150, y + 14), f"{n}", font=bold(34), fill=colour)
        d.text((w - 150 + d.textlength(f"{n}", font=bold(34)) + 4, y + 30), "/100", font=regular(16), fill=MUTED)
    img.save(OUT / "readme_journey.png", optimize=True)


def strengths():
    pics = ROOT / "app/src/main/res/drawable-nodpi"
    results = {("b", 8): 87, ("b", 16): 14, ("b", 24): 3, ("r", 8): 90, ("r", 16): 39, ("r", 24): 18}
    size, gap, left, top = 240, 24, 210, 70
    w = left + 3 * size + 2 * gap + 40
    h = top + 2 * (size + 70) + 10
    img = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(img)
    for c, s in enumerate((8, 16, 24)):
        d.text((left + c * (size + gap), 28), f"STRENGTH {s}", font=regular(17), fill=MUTED)
    for r, (kind, name) in enumerate((("b", "Brightness\nonly"), ("r", "Rainbow\n(colour)"))):
        y = top + r * (size + 70)
        colour = TEAL if kind == "b" else OLIVE
        d.text((40, y + size // 2 - 30), name, font=bold(24), fill=colour, spacing=6)
        for c, s in enumerate((8, 16, 24)):
            x = left + c * (size + gap)
            pic = Image.open(pics / f"stat_{kind}{s}.webp").convert("RGB").resize((size, size))
            mask = Image.new("L", (size, size), 0)
            ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], 18, fill=255)
            img.paste(pic, (x, y), mask)
            n = results[(kind, s)]
            d.text((x, y + size + 10), f"{n}", font=bold(30), fill=colour)
            d.text((x + d.textlength(f"{n}", font=bold(30)) + 4, y + size + 24), "/100 recognised", font=regular(15), fill=MUTED)
    img.save(OUT / "readme_strengths.jpg", quality=88)


if __name__ == "__main__":
    banner()
    journey()
    strengths()
