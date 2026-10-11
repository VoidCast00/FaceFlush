"""Generates app/src/main/res/raw/ascii_portrait.txt, the home screen background.

Source: Johannes Vermeer, Girl with a Pearl Earring (c. 1665), public domain:
https://commons.wikimedia.org/wiki/File:1665_Girl_with_a_Pearl_Earring.jpg
Brighter pixels become denser characters. YuNet finds the eyes, and a band of block glyphs is
placed over them as the "disruption" mark.

Usage (needs opencv-python and numpy):
  python make_ascii_portrait.py vermeer.jpg ../app/src/main/assets/face_detection_yunet_2023mar.onnx ascii_portrait.txt 80
"""
import cv2, numpy as np, sys, random
src, model, out, cols = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
img = cv2.imread(src); h, w = img.shape[:2]
det = cv2.FaceDetectorYN.create(model, "", (w, h), 0.5, 0.3, 50)
_, faces = det.detect(img)
f = faces[0]; print("face", f[:8])
gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
gray = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8)).apply(gray).astype(np.float32)
rows = int(cols * h / w * 0.5)
small = cv2.resize(gray, (cols, rows), interpolation=cv2.INTER_AREA)
lo, hi = np.percentile(small, 30), np.percentile(small, 99.5)
v = np.clip((small - lo) / (hi - lo), 0, 1) ** 0.8
ramp = " .:-=+*o#%@"
random.seed(11)
eye_y = (f[5] + f[7]) / 2 / h * rows
x0 = (min(f[4], f[6]) - f[2] * 0.3) / w * cols
x1 = (max(f[4], f[6]) + f[2] * 0.3) / w * cols
lines = []
for r in range(rows):
    line = []
    for c in range(cols):
        ch = ramp[int(v[r, c] * (len(ramp) - 1))]
        if abs(r - eye_y) <= 1.6 and x0 <= c <= x1:
            ch = random.choice("█▓▒░")
        line.append(ch)
    lines.append("".join(line).rstrip())
open(out, "w").write("\n".join(lines) + "\n")
print(rows, "rows x", cols)
