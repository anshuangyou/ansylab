# -*- coding: utf-8 -*-
"""下载 wheels/ 里的全部 Android 依赖。

两类来源：
  - 原生包（numpy/opencv/scipy/pillow/pyyaml）→ Chaquopy 索引，cp310 + arm64_v8a
  - 纯 Python 包（requests 等）→ PyPI，py3-none-any

App 侧解释器为 CPython 3.10，minSdk 30，仅 arm64-v8a。

用法：
    python wheels/fetch_wheels.py
"""

import os
import re
import ssl
import urllib.request

BASE = "https://chaquo.com/pypi-13.1/"
CTX = ssl.create_default_context()
DEST = os.path.dirname(os.path.abspath(__file__))

# 原生包：直接给出 wheel 文件名，避免版本漂移
NATIVE = [
    "numpy-1.23.3-0-cp310-cp310-android_21_arm64_v8a.whl",
    "opencv_python-4.5.1.48-2-cp310-cp310-android_24_arm64_v8a.whl",
    "scipy-1.8.1-1-cp310-cp310-android_21_arm64_v8a.whl",
    "pillow-11.0.0-0-cp310-cp310-android_24_arm64_v8a.whl",
    "pyyaml-6.0.3-0-cp310-cp310-android_24_arm64_v8a.whl",
    # 运行时依赖
    "chaquopy_libcxx-180000-0-py3-none-android_24_arm64_v8a.whl",
    "chaquopy_openblas-0.2.20-5-py3-none-android_21_arm64_v8a.whl",
    "chaquopy_libpng-1.6.34-1-py3-none-android_21_arm64_v8a.whl",
    "chaquopy_libjpeg-1.5.3-1-py3-none-android_21_arm64_v8a.whl",
    "chaquopy_libgfortran-4.9-0-py3-none-android_21_arm64_v8a.whl",
    "chaquopy_libyaml-0.2.5-0-py3-none-android_24_arm64_v8a.whl",
    "chaquopy_freetype-2.9.1-2-py3-none-android_21_arm64_v8a.whl",
]

# 纯 Python 包：PyPI 上取 py3-none-any / py2.py3-none-any
PURE = [
    ("requests", "2.32.3"),
    ("urllib3", "2.2.3"),
    ("idna", "3.10"),
    ("certifi", "2024.8.30"),
    ("charset-normalizer", "3.4.0"),
    ("python-dateutil", "2.9.0.post0"),
    ("six", "1.16.0"),
    ("transforms3d", "0.4.2"),
    ("tqdm", "4.67.1"),
]


def pkg_dir(fname):
    stem = fname[:-4]
    if stem.startswith("chaquopy_"):
        return "chaquopy-" + stem.split("_", 1)[1].split("-")[0]
    if stem.startswith("opencv_python-"):
        return "opencv-python"
    if stem.startswith("Pillow-") or stem.startswith("pillow-"):
        return "pillow"
    return stem.split("-")[0].lower()


def fetch_native(fname):
    dest = os.path.join(DEST, fname)
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        print("  exists  %s" % fname)
        return
    url = "%s%s/%s" % (BASE, pkg_dir(fname), fname)
    with urllib.request.urlopen(url, timeout=180, context=CTX) as r, open(dest, "wb") as f:
        f.write(r.read())
    print("  GET     %-58s %5.1f MB" % (fname, os.path.getsize(dest) / 1048576))


def fetch_pure(name, version):
    index = "https://pypi.org/pypi/%s/json" % name
    with urllib.request.urlopen(index, timeout=60, context=CTX) as r:
        import json
        data = json.load(r)
    files = [u for u in data["releases"].get(version, [])
             if u["filename"].endswith(".whl") and "none-any" in u["filename"]]
    if not files:
        print("  MISS    %s==%s（无 py3-none-any wheel）" % (name, version))
        return
    fname = files[0]["filename"]
    dest = os.path.join(DEST, fname)
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        print("  exists  %s" % fname)
        return
    with urllib.request.urlopen(files[0]["url"], timeout=120, context=CTX) as r, open(dest, "wb") as f:
        f.write(r.read())
    print("  GET     %-58s %5.2f MB" % (fname, os.path.getsize(dest) / 1048576))


def main():
    print("=== 原生包（Chaquopy）===")
    for f in NATIVE:
        try:
            fetch_native(f)
        except Exception as e:
            print("  FAIL    %s: %s" % (f, str(e)[:60]))
    print("=== 纯 Python 包（PyPI）===")
    for name, ver in PURE:
        try:
            fetch_pure(name, ver)
        except Exception as e:
            print("  FAIL    %s==%s: %s" % (name, ver, str(e)[:60]))

    total = 0
    print("=== 结果 ===")
    for f in sorted(os.listdir(DEST)):
        if f.endswith(".whl"):
            size = os.path.getsize(os.path.join(DEST, f))
            total += size
    print("  %d 个 wheel，合计 %.1f MB" % (
        sum(1 for f in os.listdir(DEST) if f.endswith(".whl")), total / 1048576))


if __name__ == "__main__":
    main()
