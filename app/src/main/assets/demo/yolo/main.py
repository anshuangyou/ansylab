"""YOLO 实时检测 demo（NPU），带摄像头选择与闪光灯控制。

程序结构：
    yolo_demo/
        icon.png
        main.py             入口脚本
        yolov8n_int8.onnx   模型

固定区：画面 + 状态行 + 摄像头下拉框 + 闪光灯开关
折叠区：置信度滑块、显示类别名、重置统计
"""

import os
import time

import cv2

import ansylab

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL = os.path.join(HERE, "yolov8n_int8.onnx")
if not os.path.exists(MODEL):
    MODEL = os.path.join(HERE, "yolov8n.onnx")

COLORS = [
    (255, 56, 56), (255, 157, 151), (255, 112, 31), (255, 178, 29),
    (207, 210, 49), (72, 249, 10), (146, 204, 23), (61, 219, 134),
    (26, 147, 52), (0, 212, 187), (44, 153, 168), (0, 194, 255),
]


def cam_label(c):
    facing = "前摄" if c.get("facing") == "front" else "后摄"
    flash = " 闪光" if c.get("flash") else ""
    return "#%s %s %.1fmm%s" % (c.get("id"), facing, float(c.get("focal", 0.0)), flash)


def draw(frame, dets, names, fps, show_names):
    for x1, y1, x2, y2, cls, score in dets:
        color = COLORS[int(cls) % len(COLORS)]
        cv2.rectangle(frame, (int(x1), int(y1)), (int(x2), int(y2)), color, 2)
        if show_names:
            name = names[cls] if cls < len(names) else str(cls)
            label = "%s %.2f" % (name, score)
            cv2.putText(frame, label, (int(x1) + 2, int(y1) - 6),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 0, 0), 4)
            cv2.putText(frame, label, (int(x1) + 2, int(y1) - 6),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.45, color, 1)

    cv2.putText(frame, "NPU %.0f fps" % fps, (10, 26),
                cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 0, 0), 5)
    cv2.putText(frame, "NPU %.0f fps" % fps, (10, 26),
                cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 40, 40), 2)
    return frame


def main():
    cams = ansylab.Camera.list()
    labels = [cam_label(c) for c in cams] or ["（没有找到相机）"]

    model = ansylab.YOLO(MODEL, conf=0.25)
    view = ansylab.display()

    # ---- 固定区 -----------------------------------------------------------
    info = ansylab.label("等待第一帧 ...", region="main")
    cam_box = ansylab.combo_box("摄像头", labels, 0, region="main")
    torch_sw = ansylab.toggle("闪光灯", False, region="main")

    # ---- 折叠区 -----------------------------------------------------------
    res_box = ansylab.combo_box("分辨率", ["640x480", "1280x720", "1920x1080"], 0)
    fps_box = ansylab.combo_box("相机帧率", ["15", "30"], 1)
    conf_slider = ansylab.slider("置信度阈值", 0.10, 0.90, 0.05, 0.25)
    class_box = ansylab.combo_box("只看某个类别", ["（全部）"], 0)
    show_names = ansylab.toggle("显示类别名", True)
    reset_btn = ansylab.button("重置统计")

    cam = None
    current_label = None
    current_format = ""
    torch_on = False

    def parse_size(text):
        try:
            w, h = text.lower().split("x")
            return int(w), int(h)
        except Exception:
            return 640, 480

    def open_camera(index):
        cid = cams[index].get("id") if 0 <= index < len(cams) else None
        w, h = parse_size(res_box.value)
        c = ansylab.Camera(w, h, fps=int(fps_box.value), camera_id=cid)
        c.open_timeout_ms = 5000
        return c if c.open() else None

    if cams:
        cam = open_camera(0)
        current_label = labels[0]
        current_format = res_box.value + "@" + fps_box.value

    # 类别下拉：按「模型里有哪些类」初始化，跑起来不重建控件
    names_all = list(getattr(model, "names", [])) or []
    if names_all:
        class_box.set_items(["（全部）"] + ["%d %s" % (i, n) for i, n in enumerate(names_all)], 0)

    buf = None
    counter = 0
    total = 0
    frames = 0
    fps = 0.0
    last = time.time()
    last_report = last

    while ansylab.running():
        # ---- 切换摄像头
        if cam_box.value != current_label:
            if cam is not None:
                cam.set_torch(False)
                cam.release()
                cam = None
            torch_on = False
            torch_sw.value = False
            cam = open_camera(cam_box.index)
            current_label = cam_box.value
            if cam is None:
                info.set("打开失败：%s" % cam_box.value)
                time.sleep(0.5)
                continue
            info.set("%s  已打开" % cam_box.value)

        # ---- 分辨率 / 帧率变化：重开相机
        want = res_box.value + "@" + fps_box.value
        if cam is not None and want != current_format:
            current_format = want
            cam.set_torch(False)
            cam.release()
            cam = open_camera(cam_box.index)
            torch_on = False
            torch_sw.value = False
            if cam is None:
                info.set("重开失败：%s" % want)
                time.sleep(0.5)

        # ---- 闪光灯
        if bool(torch_sw.value) != torch_on:
            torch_on = bool(torch_sw.value)
            if cam is None:
                info.set("没有相机，无法开闪光灯")
            else:
                err = cam.set_torch(torch_on)
                if err:
                    info.set("闪光灯：%s" % err)
                    torch_sw.value = False
                    torch_on = False

        if cam is None:
            time.sleep(0.2)
            continue

        frame = cam.read_into(buf)
        if frame is None:
            print("[demo] 读帧超时")
            time.sleep(0.05)
            continue
        buf = frame
        frames += 1

        if reset_btn.clicked:
            total = 0
            counter = 0
            last = time.time()

        dets = model.detect(frame, conf=conf_slider.value)

        # 类别过滤：下拉选了某一类就只画那一类
        pick = class_box.index
        if pick > 0:
            want_cls = pick - 1
            dets = [d for d in dets if int(d[4]) == want_cls]

        total += len(dets)

        counter += 1
        now = time.time()
        if now - last >= 1.0:
            fps = counter / (now - last)
            counter = 0
            last = now

        info.set("fps %.1f | 本帧 %d 个 | 累计 %d 个 | %s"
                 % (fps, len(dets), total, cam.current_id or "默认"))

        if now - last_report >= 5.0:
            print("[demo] heartbeat fps=%.1f frames=%d total=%d cam=%s"
                  % (fps, frames, total, cam.current_id))
            last_report = now

        draw(frame, dets, model.names, fps, show_names.value)
        view.show(frame)
        cv2.waitKey(1)


if __name__ == "__main__":
    main()
