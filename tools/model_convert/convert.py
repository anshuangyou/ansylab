#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 Ultralytics 训练出来的权重（.pt）转换成手机端可用的 ONNX。

为什么需要这个脚本
------------------
手机端跑的是 ONNX + ONNX Runtime（QNN EP），而训练产物是 PyTorch 的 .pt。
两者之间必须做一次导出，导出参数如果不对，手机上会直接跑不起来：

  * dynamic=True（默认可能带动态轴）→ QNN 无法编译
  * opset 太新或太老 → ORT 加载报错
  * imgsz 与训练/部署不一致 → 精度下降
  * BatchNorm 未折叠 → 图被分段，HTP 执行崩溃

所以这里把参数固定成一套已验证可用的组合，并在导出后做静态校验。

用法
----
    python convert.py best.pt
    python convert.py best.pt -o out/hand.onnx
    python convert.py best.pt --imgsz 640 --opset 17 --no-verify
"""

from __future__ import annotations

import argparse
import os
import shutil
import sys
import time

DEFAULT_IMGSZ = 640
DEFAULT_OPSET = 17


def _require(name: str, hint: str) -> None:
    try:
        __import__(name)
    except ImportError:
        sys.exit("[错误] 缺少依赖 %s\n        %s" % (name, hint))


def export(weights: str, imgsz: int, opset: int, simplify: bool, batch: int) -> str:
    """调用 ultralytics 导出 ONNX，返回导出的文件路径。"""
    from ultralytics import YOLO

    if not os.path.isfile(weights):
        sys.exit("[错误] 权重文件不存在: %s" % weights)

    print("[1/3] 导出 %s（imgsz=%d opset=%d simplify=%s batch=%d）"
          % (os.path.basename(weights), imgsz, opset, simplify, batch))
    t0 = time.time()
    model = YOLO(weights)
    out = model.export(
        format="onnx",
        imgsz=imgsz,
        opset=opset,
        simplify=simplify,
        dynamic=False,          # 手机端必须是静态形状
        batch=batch,
    )
    print("      完成 %.1fs -> %s" % (time.time() - t0, out))
    return out


def inspect(path: str, imgsz: int) -> dict:
    """静态校验：形状、opset、动态维度、BatchNorm、算子分布。"""
    import onnx

    print("[2/3] 静态校验")
    m = onnx.load(path)
    meta = {k.key: k.value for k in m.metadata_props}
    ops = [o.version for o in m.opset_import if o.domain in ("", "ai.onnx")]
    opset = ops[0] if ops else -1

    node_types = {}
    for n in m.graph.node:
        node_types[n.op_type] = node_types.get(n.op_type, 0) + 1

    dyn = []
    in_shape, out_shape = None, None
    for i in m.graph.input:
        dims = [d.dim_value or d.dim_param for d in i.type.tensor_type.shape.dim]
        in_shape = dims
        if any(isinstance(d, str) and d for d in dims):
            dyn.append("input")
    for o in m.graph.output:
        dims = [d.dim_value or d.dim_param for d in o.type.tensor_type.shape.dim]
        out_shape = dims
        if any(isinstance(d, str) and d for d in dims):
            dyn.append("output")

    bn = node_types.get("BatchNormalization", 0)
    problems = []

    if not in_shape or len(in_shape) != 4 or in_shape[1] != 3:
        problems.append("输入不是 NCHW 四维（当前 %s）" % (in_shape,))
    if in_shape and in_shape[2] not in (None, imgsz):
        problems.append("输入高 %s != imgsz %d" % (in_shape[2], imgsz))
    if dyn:
        problems.append("存在动态维度（%s），QNN 无法编译" % ",".join(dyn))
    if bn:
        problems.append("存在 %d 个未折叠的 BatchNorm，QNN 会把图分段" % bn)
    if opset > 20:
        problems.append("opset %d 偏新，QNN EP 可能不支持" % opset)

    print("      opset        %s" % opset)
    print("      input        %s" % (in_shape,))
    print("      output       %s" % (out_shape,))
    print("      节点数       %d（%d 类算子）" % (len(m.graph.node), len(node_types)))
    print("      BatchNorm    %d" % bn)
    print("      动态维度     %s" % ("有 " + ",".join(dyn) if dyn else "无"))
    for k in ("version", "date", "license"):
        if k in meta:
            print("      %-12s %s" % (k, str(meta[k])[:60]))
    print("      大小         %.1f MB" % (os.path.getsize(path) / 1e6))

    for p in problems:
        print("      [警告] %s" % p)
    return {"ok": not problems, "problems": problems, "in_shape": in_shape,
            "out_shape": out_shape, "opset": opset}


def verify_run(path: str, info: dict) -> bool:
    """用 ONNX Runtime（CPU）跑一次随机输入，确认图能执行。"""
    try:
        import numpy as np
        import onnxruntime as ort
    except ImportError:
        print("[3/3] 未安装 onnxruntime/numpy，跳过运行时校验")
        return True

    print("[3/3] 运行时校验（CPU）")
    try:
        sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
        name = sess.get_inputs()[0].name
        shape = [d if isinstance(d, int) and d > 0 else 1
                 for d in (info["in_shape"] or [1, 3, 640, 640])]
        x = np.random.rand(*shape).astype(np.float32)
        t0 = time.time()
        outs = sess.run(None, {name: x})
        dt = (time.time() - t0) * 1000
        print("      执行成功 %.1f ms，输出 %s" % (dt, [o.shape for o in outs]))
        return True
    except Exception as e:
        print("      [警告] 执行失败: %s" % str(e)[:200])
        return False


def main() -> int:
    ap = argparse.ArgumentParser(
        description="把 .pt 权重转换为手机端可用的 .onnx",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    ap.add_argument("weights", help="输入权重，例如 best.pt")
    ap.add_argument("-o", "--out", help="输出路径，默认与权重同目录同名 .onnx")
    ap.add_argument("--imgsz", type=int, default=DEFAULT_IMGSZ, help="输入边长")
    ap.add_argument("--opset", type=int, default=DEFAULT_OPSET, help="ONNX opset")
    ap.add_argument("--batch", type=int, default=1, help="batch（手机端固定 1）")
    ap.add_argument("--no-simplify", action="store_true", help="不做图简化")
    ap.add_argument("--no-verify", action="store_true", help="跳过运行时校验")
    a = ap.parse_args()

    _require("ultralytics", "pip install ultralytics")
    _require("onnx", "pip install onnx")

    out = a.out or os.path.splitext(a.weights)[0] + ".onnx"
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)

    produced = export(a.weights, a.imgsz, a.opset, not a.no_simplify, a.batch)
    if os.path.abspath(produced) != os.path.abspath(out):
        shutil.move(produced, out)
        print("      重命名 -> %s" % out)

    info = inspect(out, a.imgsz)
    if not a.no_verify:
        verify_run(out, info)

    print()
    if info["ok"]:
        print("结论：可用于手机端。推到设备后跑自检：")
        print('   ansylab.probe_model("%s")' % out)
        return 0
    print("结论：存在风险，先在手机上跑自检确认：")
    print("   ansylab.probe_model(\"%s\")" % out)
    return 2


if __name__ == "__main__":
    sys.exit(main())
