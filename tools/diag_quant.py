# -*- coding: utf-8 -*-
"""诊断量化模型的输出分布，并列出检测头附近的节点名（用于 nodes_to_exclude）。"""

import sys

import cv2
import numpy as np
import onnx
import onnxruntime as ort


def letterbox(img, s=640):
    h, w = img.shape[:2]
    scale = min(s / w, s / h)
    nw, nh = int(round(w * scale)), int(round(h * scale))
    canvas = np.full((s, s, 3), 114, np.uint8)
    canvas[(s - nh) // 2:(s - nh) // 2 + nh, (s - nw) // 2:(s - nw) // 2 + nw] = \
        cv2.resize(img, (nw, nh))
    rgb = cv2.cvtColor(canvas, cv2.COLOR_BGR2RGB).transpose(2, 0, 1).astype(np.float32) / 255.0
    return rgb[None]


def stats(model_path, img_path):
    img = cv2.imread(img_path)
    ten = letterbox(img)
    sess = ort.InferenceSession(model_path, providers=["CPUExecutionProvider"])
    name = sess.get_inputs()[0].name
    out = sess.run(None, {name: ten})[0]
    print("\n%s" % model_path)
    print("  output shape:", out.shape, "dtype:", out.dtype)
    o = out[0]
    print("  min/max/mean: %.3f / %.3f / %.3f" % (o.min(), o.max(), o.mean()))
    if o.shape[0] > 4:
        scores = o[4:]
        print("  score max: %.3f  (class %d)" % (scores.max(), scores.argmax() // o.shape[1]))
    return out


def dump_tail(model_path, n=18):
    m = onnx.load(model_path)
    print("\n=== 末尾 %d 个节点 ===" % n)
    for node in m.graph.node[-n:]:
        print("  %-28s %-18s -> %s" % (node.op_type, node.name[:26], ",".join(node.output)[:40]))
    print("\n=== graph output ===")
    for o in m.graph.output:
        print("  ", o.name)


if __name__ == "__main__":
    img = sys.argv[1]
    stats("yolov8n.onnx", img)
    stats("yolov8n_int8.onnx", img)
    dump_tail("yolov8n_int8.onnx")
