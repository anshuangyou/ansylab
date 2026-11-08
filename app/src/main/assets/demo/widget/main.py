"""控件总览 demo：每个控件在固定区和折叠区各放一份。

固定区（main）和折叠区（panel）内容完全一样，方便对比两边渲染效果。
"""

import math
import time

import ansylab
import cv2
import numpy as np


def make_image(w=280, h=120):
    img = np.zeros((h, w, 3), dtype=np.uint8)
    for y in range(h):
        img[y, :] = (int(255 * y / h), 70, int(255 * (h - y) / h))
    cv2.putText(img, "IMG", (100, 72), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (255, 255, 255), 3)
    return img


def build(region, tag):
    """在指定区域把所有控件各建一份，返回一个字典方便主循环更新。"""
    w = {}

    w["status"] = ansylab.label("就绪", region=region)
    w["log"] = ansylab.text_output("", max_height=80, region=region)

    w["h_text"] = ansylab.header("文本输入 %s" % tag, region=region)
    w["tf"] = ansylab.text_field("单行输入", "hello", region=region)
    w["ti"] = ansylab.text_input("多行输入", "", max_height=70, region=region)

    w["div1"] = ansylab.divider(region=region)
    w["h_sel"] = ansylab.header("选择类 %s" % tag, region=region)
    w["cb"] = ansylab.checkbox("勾选框", True, region=region)
    w["rg"] = ansylab.radio_group("单选组", ["选项 A", "选项 B", "选项 C"], 1, region=region)
    w["cbx"] = ansylab.combo_box("下拉框", ["下拉 1", "下拉 2"], 0, region=region)

    w["h_prog"] = ansylab.header("进度 %s" % tag, region=region)
    w["prog"] = ansylab.progress_bar("确定进度", 0, 100, False, region=region)
    w["prog2"] = ansylab.progress_bar("不确定（转圈）", 0, 100, True, region=region)

    w["h_img"] = ansylab.header("图片 %s" % tag, region=region)
    w["img"] = ansylab.image("静态图片", 130, region=region)
    w["img"].show(make_image())

    w["h_btn"] = ansylab.header("开关与按钮 %s" % tag, region=region)
    w["tg"] = ansylab.toggle("开关", False, region=region)
    w["btn"] = ansylab.button("普通按钮", region=region)

    w["h_plot"] = ansylab.header("折线图 %s" % tag, region=region)
    w["plt"] = ansylab.plot("正弦波", 150, 2, region=region)

    w["h_stick"] = ansylab.header("拖动与摇杆 %s" % tag, region=region)
    w["sld"] = ansylab.slider("拖动条", 0, 100, 1, 30, region=region)
    w["stick"] = ansylab.joystick("单摇杆", region=region)
    w["dual"] = ansylab.double_joystick("双摇杆", region=region)

    return w


def main():
    fixed = build("main", "固定区")
    panel = build("panel", "折叠区")
    both = [fixed, panel]

    progress = 0.0
    t = 0.0
    clicks = {"fixed": 0, "panel": 0}
    lines = []
    last_status = None

    def append(text):
        lines.append(text)
        if len(lines) > 10:
            del lines[:len(lines) - 10]
        for g in both:
            g["log"].set("\n".join(lines))

    def set_status(text):
        nonlocal last_status
        if text == last_status:
            return
        last_status = text
        for g in both:
            g["status"].set(text)

    set_status("就绪")

    while ansylab.running():
        progress = (progress + 1.5) % 100
        for g in both:
            g["prog"].value = progress

        for name, g in zip(("fixed", "panel"), both):
            if g["tf"].submitted:
                append("%s 单行：%s" % (name, g["tf"].value))
            if g["ti"].submitted:
                append("%s 多行：%s" % (name, g["ti"].value))
                g["ti"].set_text("")
            if g["btn"].clicked:
                clicks[name] += 1
                append("%s 按钮第 %d 次" % (name, clicks[name]))
            g["plt"].push(math.sin(t * 2.0), math.cos(t * 2.0))

        t += 0.05

        g = fixed
        set_status("tf=%s cb=%s radio=%s combo=%s tg=%s sld=%.0f 摇杆=%+.2f,%+.2f"
                   % (g["tf"].value, "开" if g["cb"].value else "关", g["rg"].value,
                      g["cbx"].value, "开" if g["tg"].value else "关",
                      g["sld"].value, g["stick"].x, g["stick"].y))
        ansylab.sleep(0.05)


if __name__ == "__main__":
    main()
