# -*- coding: utf-8 -*-
"""在 WSL 里用 CPU 对比 fp32 / int8 的检测结果，确认量化掉点。"""

import sys

import cv2
import numpy as np
import onnxruntime as ort

NAMES = ["person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
         "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
         "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
         "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
         "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
         "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup",
         "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
         "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
         "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
         "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
         "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
         "hair drier", "toothbrush"]


def letterbox(img, s=640):
    h, w = img.shape[:2]
    scale = min(s / w, s / h)
    nw, nh = int(round(w * scale)), int(round(h * scale))
    canvas = np.full((s, s, 3), 114, np.uint8)
    canvas[(s - nh) // 2:(s - nh) // 2 + nh, (s - nw) // 2:(s - nw) // 2 + nw] = \
        cv2.resize(img, (nw, nh))
    rgb = cv2.cvtColor(canvas, cv2.COLOR_BGR2RGB).transpose(2, 0, 1).astype(np.float32) / 255.0
    return rgb[None], scale, (s - nw) // 2, (s - nh) // 2


def nms(d, thr=0.45):
    if not len(d):
        return []
    out = []
    for c in np.unique(d[:, 5]):
        g = d[d[:, 5] == c][np.argsort(-d[d[:, 5] == c][:, 4])]
        while len(g):
            out.append(g[0])
            if len(g) == 1:
                break
            r = g[1:]
            xx1 = np.maximum(g[0, 0], r[:, 0]); yy1 = np.maximum(g[0, 1], r[:, 1])
            xx2 = np.minimum(g[0, 2], r[:, 2]); yy2 = np.minimum(g[0, 3], r[:, 3])
            inter = np.maximum(0, xx2 - xx1) * np.maximum(0, yy2 - yy1)
            a0 = (g[0, 2] - g[0, 0]) * (g[0, 3] - g[0, 1])
            ar = (r[:, 2] - r[:, 0]) * (r[:, 3] - r[:, 1])
            iou = inter / np.maximum(1e-6, a0 + ar - inter)
            g = r[iou <= thr]
    return out


def detect(sess, img, conf=0.25):
    ten, scale, dx, dy = letterbox(img)
    name = sess.get_inputs()[0].name
    out = sess.run(None, {name: ten})[0][0].T
    scores = out[:, 4:]
    cls = scores.argmax(1)
    sc = scores[np.arange(len(scores)), cls]
    m = sc > conf
    if not m.any():
        return []
    b = out[m, :4]; c = cls[m]; s = sc[m]
    x1 = (b[:, 0] - b[:, 2] / 2 - dx) / scale
    y1 = (b[:, 1] - b[:, 3] / 2 - dy) / scale
    x2 = (b[:, 0] + b[:, 2] / 2 - dx) / scale
    y2 = (b[:, 1] + b[:, 3] / 2 - dy) / scale
    h, w = img.shape[:2]
    d = np.stack([np.clip(x1, 0, w), np.clip(y1, 0, h),
                  np.clip(x2, 0, w), np.clip(y2, 0, h), s, c], 1)
    return nms(d)


def main(model_path, image_path):
    img = cv2.imread(image_path)
    sess = ort.InferenceSession(model_path, providers=["CPUExecutionProvider"])
    dets = detect(sess, img)
    print("%s -> %d 个目标" % (model_path, len(dets)))
    for d in sorted(dets, key=lambda x: -x[4])[:10]:
        print("   %-14s %.3f [%d,%d,%d,%d]" % (NAMES[int(d[5])], d[4], d[0], d[1], d[2], d[3]))
    return dets


if __name__ == "__main__":
    a = main(sys.argv[1], sys.argv[2])
    b = main(sys.argv[3], sys.argv[2])
    print("\nfp32 %d 个, int8 %d 个" % (len(a), len(b)))
