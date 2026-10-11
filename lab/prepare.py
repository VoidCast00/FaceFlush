"""Detects the main face in every LFW photo and splits identities into train and test sets.

Writes $LAB/data/index.npz with: path, identity, landmarks (5x2), split ("train" / "test").
Test identities all have at least two photos, so a protected photo can be compared against a
*different* clean photo of the same person, which is how face search engines work.
"""
from __future__ import annotations

import numpy as np
import cv2
from tqdm import tqdm

from faceflush_lab.align import largest_face
from faceflush_lab.paths import DATA

TEST_IDENTITIES = 600
SEED = 1234


def main():
    root = DATA / "lfw"
    paths, ids, lms = [], [], []
    for person in tqdm(sorted(root.iterdir()), desc="detect"):
        for img_path in sorted(person.glob("*.jpg")):
            img = cv2.imread(str(img_path))
            face = largest_face(img) if img is not None else None
            if face is None:
                continue
            paths.append(str(img_path.relative_to(DATA)))
            ids.append(person.name)
            lms.append(face.landmarks)

    ids_arr = np.array(ids)
    names, counts = np.unique(ids_arr, return_counts=True)
    multi = names[counts >= 2]
    rng = np.random.default_rng(SEED)
    test_names = set(rng.choice(multi, size=min(TEST_IDENTITIES, len(multi)), replace=False))
    split = np.array(["test" if i in test_names else "train" for i in ids])

    np.savez(
        DATA / "index.npz",
        path=np.array(paths),
        identity=ids_arr,
        landmarks=np.array(lms, dtype=np.float32),
        split=split,
    )
    print(f"{len(paths)} faces, {len(names)} identities, {len(test_names)} test identities, "
          f"{(split == 'train').sum()} train photos")


if __name__ == "__main__":
    main()
