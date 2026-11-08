# -*- coding: utf-8 -*-
"""把 YOLOv8n 量化成 INT8（QDQ），供 ONNX Runtime 的 QNN EP 在 HTP 上跑。

QNN EP 只有在模型是 QDQ 量化格式时才会把 Conv 放到 Hexagon NPU 上做 INT8 卷积；
fp32 模型它只能按 fp16 跑，速度差 2~3 倍。

用法（WSL，ai311 环境）：
    python quantize_yolo.py --model yolov8n.onnx --calib calib --out yolov8n_int8.onnx
"""

import argparse
import glob
import os

import cv2
import numpy as np
import onnx
from onnxruntime.quantization import CalibrationDataReader, QuantFormat, QuantType, quantize_static

MEAN = 0.0
STD = 255.0

# YOLOv8 的检测头输出把「框坐标(0~640)」和「类别分数(0~1)」拼在同一个张量里。
# 如果连输出也量化，两者共用一个 scale，分辨率约 2.5，所有分数都会被压成 0
#（实测 int8 模型 dets=0）。所以默认把检测头整体排除在量化之外。
DEFAULT_EXCLUDE = ["/model.22/"]


def letterbox(img, size=640):
    h, w = img.shape[:2]
    scale = min(size / w, size / h)
    nw, nh = int(round(w * scale)), int(round(h * scale))
    resized = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_LINEAR)
    canvas = np.full((size, size, 3), 114, dtype=np.uint8)
    dx, dy = (size - nw) // 2, (size - nh) // 2
    canvas[dy:dy + nh, dx:dx + nw] = resized
    rgb = cv2.cvtColor(canvas, cv2.COLOR_BGR2RGB)
    chw = rgb.transpose(2, 0, 1).astype(np.float32) / STD
    return chw[None, ...]


class Reader(CalibrationDataReader):
    def __init__(self, files, input_name="images", size=640):
        self.files = list(files)
        self.input_name = input_name
        self.size = size
        self.i = 0

    def get_next(self):
        while self.i < len(self.files):
            path = self.files[self.i]
            self.i += 1
            img = cv2.imread(path)
            if img is None:
                continue
            return {self.input_name: letterbox(img, self.size)}
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--calib", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--size", type=int, default=640)
    ap.add_argument("--input", default="images")
    ap.add_argument("--per-channel", action="store_true", default=True)
    ap.add_argument("--exclude-prefix", action="append", default=None,
                    help="要排除量化的节点名前缀，默认 /model.22/（检测头）")
    args = ap.parse_args()

    files = sorted(glob.glob(os.path.join(args.calib, "*.jpg")))
    if not files:
        raise SystemExit("没有校准图: %s" % args.calib)
    print("校准图 %d 张" % len(files))

    prefixes = args.exclude_prefix or DEFAULT_EXCLUDE
    m = onnx.load(args.model)
    exclude = [n.name for n in m.graph.node if any(n.name.startswith(p) for p in prefixes)]
    print("排除量化节点 %d 个（前缀 %s）" % (len(exclude), prefixes))

    reader = Reader(files, args.input, args.size)
    quantize_static(
        args.model,
        args.out,
        reader,
        quant_format=QuantFormat.QDQ,
        per_channel=args.per_channel,
        activation_type=QuantType.QUInt8,
        weight_type=QuantType.QInt8,
        reduce_range=False,
        nodes_to_exclude=exclude,
    )

    print("输出 %s  %.2f MB" % (args.out, os.path.getsize(args.out) / 1048576.0))


if __name__ == "__main__":
    main()
