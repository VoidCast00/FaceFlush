"""Downloads the models and the LFW dataset into $LAB (models/, data/)."""
from __future__ import annotations

import shutil
import tarfile
import urllib.request
import zipfile

from faceflush_lab.paths import DATA, MODELS

FILES = {
    MODELS / "face_recognition_sface_2021dec.onnx":
        "https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/face_recognition_sface/"
        "face_recognition_sface_2021dec.onnx",
}
BUFFALO_ZIP = "https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_l.zip"
LFW_TGZ = "https://ndownloader.figshare.com/files/5976018"


def download(url: str, dest):
    if dest.exists():
        print("have", dest.name)
        return
    print("downloading", url)
    tmp = dest.with_suffix(dest.suffix + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": "faceflush-lab"})
    with urllib.request.urlopen(req) as r, open(tmp, "wb") as f:
        shutil.copyfileobj(r, f)
    tmp.rename(dest)


def main():
    MODELS.mkdir(parents=True, exist_ok=True)
    DATA.mkdir(parents=True, exist_ok=True)
    for dest, url in FILES.items():
        download(url, dest)

    arcface = MODELS / "w600k_r50.onnx"
    if not arcface.exists():
        z = MODELS / "buffalo_l.zip"
        download(BUFFALO_ZIP, z)
        with zipfile.ZipFile(z) as zf:
            name = next(n for n in zf.namelist() if n.endswith("w600k_r50.onnx"))
            arcface.write_bytes(zf.read(name))
        z.unlink()

    lfw = DATA / "lfw"
    if not lfw.exists():
        tgz = DATA / "lfw.tgz"
        download(LFW_TGZ, tgz)
        with tarfile.open(tgz) as t:
            t.extractall(DATA, filter="data")
        tgz.unlink()
    print("lfw identities:", sum(1 for _ in lfw.iterdir()))

    # Facenet weights download themselves on first use into $TORCH_HOME
    from faceflush_lab.models import facenet
    facenet()
    print("done")


if __name__ == "__main__":
    main()
