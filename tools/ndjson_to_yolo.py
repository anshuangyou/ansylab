#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 Ultralytics Platform 导出的 NDJSON 转成标准 YOLO 数据集。

平台导出的 .ndjson 里只有标注和图片 URL（图片还在它的 CDN 上），
不能直接喂给 `yolo train`。这个脚本负责：

  1. 解析 NDJSON（dataset 头 + 每张图一条记录）
  2. 按记录里的 url 下载图片
  3. 把 `annotations.boxes` 写成 YOLO txt（class cx cy w h，已归一化）
  4. 按 split 分到 images/train、images/val
  5. 生成 data.yaml

用法：
    python tools/ndjson_to_yolo.py mouse.ndjson -o ~/dataset/mouse
    python tools/ndjson_to_yolo.py mouse.ndjson -o ~/dataset/mouse --check-only
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.request


def load(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    if not rows or rows[0].get("type") != "dataset":
        sys.exit("[错误] 不是 Ultralytics NDJSON（首行缺少 dataset 头）")
    meta = rows[0]
    images = [r for r in rows if r.get("type") == "image"]
    return meta, images


def download(url, dest, timeout=60):
    if os.path.exists(dest) and os.path.getsize(dest) > 1024:
        return True
    tmp = dest + ".part"
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "ansylab-tools"})
        with urllib.request.urlopen(req, timeout=timeout) as r, open(tmp, "wb") as f:
            while True:
                chunk = r.read(1 << 16)
                if not chunk:
                    break
                f.write(chunk)
        os.replace(tmp, dest)
        return True
    except Exception as e:
        print("   下载失败 %s: %s" % (os.path.basename(dest), str(e)[:120]))
        if os.path.exists(tmp):
            os.remove(tmp)
        return False


def write_label(path, boxes):
    """boxes: [[cls, cx, cy, w, h], ...]，平台已经归一化，直接用。"""
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        for b in boxes:
            cls, cx, cy, w, h = b[0], b[1], b[2], b[3], b[4]
            f.write("%d %.6f %.6f %.6f %.6f\n" % (int(cls), cx, cy, w, h))


def main() -> int:
    ap = argparse.ArgumentParser(description="NDJSON → YOLO 数据集")
    ap.add_argument("ndjson", help="平台导出的 .ndjson")
    ap.add_argument("-o", "--out", required=True, help="输出目录")
    ap.add_argument("--check-only", action="store_true", help="只统计，不下载")
    a = ap.parse_args()

    meta, images = load(a.ndjson)
    names = meta.get("class_names") or {}
    task = meta.get("task", "detect")
    print("数据集 %s：%d 张，类别 %s，任务 %s"
          % (meta.get("name"), len(images), names, task))
    if task != "detect":
        print("[警告] 当前脚本只处理 detect（矩形框）")

    splits = {}
    for im in images:
        splits.setdefault(im.get("split", "train"), 0)
        splits[im.get("split", "train")] += 1
    print("划分：%s" % splits)
    if a.check_only:
        return 0

    for split in splits:
        for sub in ("images", "labels"):
            os.makedirs(os.path.join(a.out, sub, split), exist_ok=True)

    ok = fail = 0
    for i, im in enumerate(images, 1):
        split = im.get("split", "train")
        fname = im["file"]
        img_path = os.path.join(a.out, "images", split, fname)
        lbl_path = os.path.join(a.out, "labels", split, os.path.splitext(fname)[0] + ".txt")
        if not download(im["url"], img_path):
            fail += 1
            continue
        boxes = im.get("annotations", {}).get("boxes") or []
        write_label(lbl_path, boxes)
        ok += 1
        if i % 20 == 0:
            print("  已处理 %d/%d" % (i, len(images)))

    # data.yaml
    keys = sorted(int(k) for k in names) if names else [0]
    lines = ["path: %s" % os.path.abspath(a.out),
             "train: images/train",
             "val: images/val" if os.path.isdir(os.path.join(a.out, "images", "val"))
             else "val: images/train",
             "", "names:"]
    for k in keys:
        lines.append("  %d: %s" % (k, names.get(str(k), "class_%d" % k)))
    with open(os.path.join(a.out, "data.yaml"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")

    print("完成：成功 %d，失败 %d" % (ok, fail))
    print("数据集目录：%s" % os.path.abspath(a.out))
    print("data.yaml：%s" % os.path.join(os.path.abspath(a.out), "data.yaml"))
    return 0 if fail == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
