"""Converts a lab result (.npy, shape C x 112 x 112) to the app's FFPT asset format.

Usage: python export_field.py results/cloak_pattern.npy cloak_pattern.bin
Format (read by CanonicalField.read in effects/Align.kt): b"FFPT", int32 version=1, int32 channels,
int32 size, then float32 values channel-major, all little-endian.
"""
import struct
import sys

import numpy as np


def main():
    arr = np.load(sys.argv[1]).astype("<f4")
    c, h, w = arr.shape
    assert h == w
    with open(sys.argv[2], "wb") as f:
        f.write(b"FFPT" + struct.pack("<iii", 1, c, h))
        f.write(np.ascontiguousarray(arr).tobytes())
    print(f"wrote {sys.argv[2]}: {c} x {h} x {w}")


if __name__ == "__main__":
    main()
