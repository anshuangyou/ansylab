"""串口助手。

固定区：状态 / 接收（文本）/ 接收（HEX）/ 发送框 / 自定义指令区
折叠区：串口参数、HEX 发送、时间戳、自动发送、DTR/RTS、导出日志
"""

import json
import os
import time

import ansylab

BAUDS = ["1200", "2400", "4800", "9600", "19200", "38400",
         "57600", "115200", "230400", "460800", "921600"]
DATA_BITS = ["8", "7", "6", "5"]
STOP_BITS = ["1", "1.5", "2"]
PARITIES = ["无", "奇", "偶", "标记", "空格"]
FLOWS = ["无", "RTS/CTS", "DSR/DTR", "XON/XOFF"]
ENDINGS = ["无", "\\n", "\\r", "\\r\\n"]

MAX_CMDS = 8          # 固定区里最多放几条自定义指令


def hexdump(data):
    return " ".join("%02X" % b for b in data)


def app_dir():
    return os.path.dirname(os.path.abspath(__file__))


def cmd_file():
    return os.path.join(app_dir(), "commands.json")


def settings_file():
    return os.path.join(app_dir(), "settings.json")


def load_settings():
    """读上次用的串口参数；读不到就用空字典（走默认值）。"""
    try:
        with open(settings_file(), encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def save_settings(data):
    try:
        with open(settings_file(), "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=1)
    except Exception as e:
        ansylab.log("保存设置失败: %r" % e)


def pick_index(labels, value, default=0):
    """在选项列表里找 value 的下标，找不到用 default。"""
    try:
        return labels.index(str(value))
    except (ValueError, TypeError):
        return default


def load_commands():
    try:
        with open(cmd_file(), encoding="utf-8") as f:
            data = json.load(f)
        return [str(x) for x in data][:MAX_CMDS]
    except Exception:
        return []


def save_commands(cmds):
    try:
        with open(cmd_file(), "w", encoding="utf-8") as f:
            json.dump(cmds, f, ensure_ascii=False, indent=1)
    except Exception as e:
        ansylab.log("保存指令失败: %r" % e)


def main():
    # ---------------- 固定区 ----------------
    status = ansylab.label("正在扫描串口 ...", region="main")

    hdr_text = ansylab.header("文本", region="main")
    rx_text = ansylab.text_output("", max_height=120, region="main")

    hdr_hex = ansylab.header("HEX", region="main")
    rx_hex = ansylab.text_output("", max_height=90, region="main")

    # 折线图（默认关闭，开关在折叠区）
    plot = ansylab.plot("接收数据", 2000, 5, region="main", window=30.0)
    plot.hide()

    send_box = ansylab.text_input("发送内容（点确定或回车）", "", max_height=64,
                                  region="main")

    # ---- 自定义指令：按钮放固定区，添加/编辑放折叠区 ----
    ansylab.header("自定义指令", region="main")
    cmd_empty = ansylab.label("（还没有指令，去下方折叠区添加）", region="main")
    cmd_btns = [ansylab.button("指令%d" % (i + 1), region="main")
                for i in range(MAX_CMDS)]
    for b in cmd_btns:
        b.hide()

    # ---------------- 折叠区：串口参数 ----------------
    st = load_settings()            # 上次的参数

    ports = ansylab.Serial.list()
    port_labels = [p.get("name", "?") for p in ports] or ["（没有找到设备）"]

    # 按上次用的端口名字找下标（换了设备就退回第一个）
    port_idx = 0
    want_port = str(st.get("port", ""))
    for i, lb in enumerate(port_labels):
        if lb == want_port:
            port_idx = i
            break

    port_sw = ansylab.toggle("串口开关", False)
    port_box = ansylab.combo_box("串口", port_labels, port_idx)
    baud_box = ansylab.combo_box(
        "波特率", BAUDS, pick_index(BAUDS, st.get("baud", "115200"), BAUDS.index("115200")))
    data_box = ansylab.combo_box("数据位", DATA_BITS, pick_index(DATA_BITS, st.get("data_bits", "8")))
    stop_box = ansylab.combo_box("停止位", STOP_BITS, pick_index(STOP_BITS, st.get("stop_bits", "1")))
    parity_box = ansylab.combo_box("校验位", PARITIES, pick_index(PARITIES, st.get("parity", "无")))
    flow_box = ansylab.combo_box("流控", FLOWS, pick_index(FLOWS, st.get("flow", "无")))
    ending_box = ansylab.combo_box("发送行尾", ENDINGS, pick_index(ENDINGS, st.get("ending", "\\n"), 1))
    hex_send = ansylab.toggle("HEX 发送", bool(st.get("hex_send", False)))
    stamp = ansylab.toggle("时间戳", bool(st.get("timestamp", True)))
    auto_ms = ansylab.slider("自动发送间隔 (ms，0=关)", 0, 3000, 100,
                             float(st.get("auto_ms", 0)))
    dtr_sw = ansylab.toggle("DTR", False)
    rts_sw = ansylab.toggle("RTS", False)

    ansylab.header("折线图", region="panel")
    plot_sw = ansylab.toggle('启用折线图("%f,%f,...\\n")', bool(st.get("plot", False)))
    show_rx_sw = ansylab.toggle("显示接收", bool(st.get("show_rx", True)))
    plot_clear_btn = ansylab.button("清空曲线")

    ansylab.header("自定义指令", region="panel")
    cmd_input = ansylab.text_field("新指令", "", region="panel")
    edit_sw = ansylab.toggle("编辑模式（点指令=删除）", False, region="panel")

    ansylab.header("命令序列", region="panel")
    seq_input = ansylab.text_input(
        "每行一条，按顺序发（可写 200:AT 表示等 200ms 再发 AT）",
        "", max_height=90, region="panel")
    seq_gap = ansylab.slider("序列间隔 (ms)", 0, 5000, 100, 300, region="panel")
    seq_btn = ansylab.button("发送序列", region="panel")

    ansylab.header("日志", region="panel")
    clear_btn = ansylab.button("清空接收")
    export_btn = ansylab.button("导出日志到文件")
    autosave_sw = ansylab.toggle("接收自动保存到文件", False, region="panel")

    # ---------------- 状态 ----------------
    serial = None
    last_cfg = None
    rx_total = 0
    tx_total = 0
    auto_last = 0.0
    dtr_last = False
    rts_last = False
    last_status = None
    rx_lines = []
    rx_hex_lines = []
    last_saved = None               # 上次写盘的设置快照
    last_save_time = 0.0

    cmds = load_commands()          # 当前指令（与按钮槽位按顺序对应）
    shown = 0                       # 有几个槽位正在用
    plot_on = False                 # 折线图是否启用
    show_rx = True                  # 是否把收到的数据写进接收区（关掉只留系统/发送）
    seq_queue = []                  # [(delay_ms, text)]
    seq_at = 0.0                    # 下一条的发送时刻
    autosave_path = ""
    autosave_fp = None
    line_buf = ""                   # 接收文本的行缓冲（按 \n 切分后解析）

    def set_status(text):
        nonlocal last_status
        if text == last_status:
            return
        last_status = text
        status.set(text)

    def ts():
        return ("[%s] " % time.strftime("%H:%M:%S")) if stamp.value else ""

    def append(buf, line, limit=300):
        buf.append(line)
        if len(buf) > limit:
            del buf[:len(buf) - limit]
        return "\n".join(buf)

    def append_rx_text(line):
        rx_text.set(append(rx_lines, ts() + line))

    def append_rx_hex(line):
        rx_hex.set(append(rx_hex_lines, ts() + line))

    def refresh_status(note=""):
        state = "已连接" if (serial is not None and serial.is_open) else "未连接"
        set_status("%s %s@%s  收 %d / 发 %d 字节%s" % (
            state, port_box.value, baud_box.value, rx_total, tx_total,
            ("  " + note) if note else ""))

    def cfg():
        return (port_box.value, baud_box.value, data_box.value, stop_box.value,
                parity_box.value, flow_box.value)

    def render_cmds():
        """把 cmds 映射到按钮槽位上，多余的隐藏。"""
        nonlocal shown
        if cmds:
            cmd_empty.hide()
        else:
            cmd_empty.show()
        for i, b in enumerate(cmd_btns):
            if i < len(cmds):
                b.label = cmds[i]
                b.show()
            else:
                b.hide()
        shown = len(cmds)

    def add_cmd(text):
        text = text.strip()
        if not text:
            append_rx_text("[系统] 指令为空")
            return
        if text in cmds:
            append_rx_text("[系统] 指令已存在")
            return
        if len(cmds) >= MAX_CMDS:
            append_rx_text("[系统] 最多 %d 条，先删一条" % MAX_CMDS)
            return
        cmds.append(text)
        save_commands(cmds)
        render_cmds()
        append_rx_text("[系统] 已添加指令：%s" % text)

    def del_cmd(index):
        if 0 <= index < len(cmds):
            text = cmds.pop(index)
            save_commands(cmds)
            render_cmds()
            append_rx_text("[系统] 已删除指令：%s" % text)

    def feed_plot(chunk):
        """把收到的字节拼成行，遇到逗号分隔的数字就推一个点。

        支持 printf("%f,%f,%f\n", a, b, c) 这种格式；
        数字个数决定画几条线（最多 5 条）。
        """
        nonlocal line_buf
        if not plot_on:
            return
        line_buf += chunk.replace("\r", "")
        while True:
            idx = line_buf.find("\n")
            if idx < 0:
                break
            line = line_buf[:idx].strip()
            line_buf = line_buf[idx + 1:]
            if not line or "," not in line:
                continue
            parts = [p.strip() for p in line.split(",")]
            if not 1 <= len(parts) <= 5:
                continue
            vals = []
            ok = True
            for p in parts:
                try:
                    vals.append(float(p))
                except ValueError:
                    ok = False
                    break
            if ok:
                plot.push(*vals)

    def autosave_write(text):
        nonlocal autosave_path, autosave_fp
        if not autosave_sw.value and autosave_fp is None:
            return
        try:
            if autosave_fp is None:
                d = os.path.join(os.path.dirname(os.path.dirname(app_dir())), "logs")
                os.makedirs(d, exist_ok=True)
                autosave_path = os.path.join(
                    d, "serial_rx_%s.log" % time.strftime("%Y%m%d_%H%M%S"))
                autosave_fp = open(autosave_path, "a", encoding="utf-8")
            autosave_fp.write(text + "\n")
            autosave_fp.flush()
        except Exception as e:
            ansylab.log("自动保存失败: %r" % e)

    def parse_sequence(text):
        """每行一条；支持 `200:AT` 这种指定等待毫秒的写法。"""
        out = []
        for raw in text.splitlines():
            line = raw.strip()
            if not line:
                continue
            delay = int(seq_gap.value)
            cmd = line
            if ":" in line:
                head, _, tail = line.partition(":")
                if head.strip().isdigit() and tail.strip():
                    delay = int(head.strip())
                    cmd = tail.strip()
            out.append((delay, cmd))
        return out

    def open_serial():
        nonlocal serial, last_cfg, rx_total, tx_total
        if serial is not None:
            try:
                serial.close()
            except Exception:
                pass
        try:
            idx = port_box.index
            baud = int(baud_box.value or "115200")
            dbits = int(data_box.value or "8")
            sbits = {"1": 1, "1.5": 3, "2": 2}.get(stop_box.value, 1)
            par = PARITIES.index(parity_box.value) if parity_box.value in PARITIES else 0
            flow = [0, 1, 2, 4][FLOWS.index(flow_box.value)] if flow_box.value in FLOWS else 0
        except ValueError:
            append_rx_text("[系统] 参数非法")
            return False

        s = ansylab.Serial(baud=baud, index=idx, data_bits=dbits,
                           stop_bits=sbits, parity=par, flow=flow)
        err = s.open()
        if err:
            append_rx_text("[系统] 打开失败：%s" % err)
            refresh_status("打开失败")
            return False

        serial = s
        try:
            s.set_dtr(True)
            s.set_rts(True)
            dtr_sw.value = True
            rts_sw.value = True
        except Exception:
            pass
        last_cfg = cfg()
        rx_total = 0
        tx_total = 0
        port_sw.value = True
        append_rx_text("[系统] 打开 %s @%d %d%s%d 流控=%s" % (
            port_box.value, baud, dbits, ["N", "O", "E", "M", "S"][par],
            sbits, flow_box.value))
        refresh_status()
        return True

    def do_send(text):
        nonlocal tx_total
        if serial is None or not text:
            return
        if hex_send.value:
            try:
                payload = bytes(int(x, 16) for x in text.replace(",", " ").split())
            except ValueError:
                append_rx_text("[系统] HEX 格式错误：%s" % text)
                return
        else:
            payload = text.encode("utf-8") + \
                {"无": b"", "\\n": b"\n", "\\r": b"\r", "\\r\\n": b"\r\n"}[ending_box.value]

        n = serial.write(payload)
        if n < 0:
            append_rx_text("[系统] 发送失败")
            return
        tx_total += n
        append_rx_hex("→ " + hexdump(payload))
        if not hex_send.value:
            append_rx_text("→ " + text)
        refresh_status()

    render_cmds()

    if not ports:
        append_rx_text("[系统] 没有找到 USB 串口：检查 OTG 连线")
        refresh_status("没有设备")
    else:
        open_serial()

    while ansylab.running():
        if last_cfg is not None and serial is not None and cfg() != last_cfg:
            append_rx_text("[系统] 参数变化，重新打开")
            open_serial()

        want_open = bool(port_sw.value)
        if want_open != (serial is not None and serial.is_open):
            if want_open:
                if not open_serial():
                    port_sw.value = False
            else:
                if serial is not None:
                    serial.close()
                    serial = None
                append_rx_text("[系统] 串口已关闭")
                refresh_status("已关闭")

        # 命令序列：按顺序、按间隔发送
        if seq_btn.clicked:
            seq_queue = parse_sequence(seq_input.value)
            if seq_queue:
                seq_at = time.monotonic()
                append_rx_text("[系统] 开始发送序列，共 %d 条" % len(seq_queue))
            else:
                append_rx_text("[系统] 序列为空")

        if seq_queue and time.monotonic() >= seq_at:
            delay, cmd = seq_queue.pop(0)
            do_send(cmd)
            seq_at = time.monotonic() + delay / 1000.0

        # 接收自动保存：开关切换时开/关文件
        if bool(autosave_sw.value) and autosave_fp is None:
            autosave_write("[系统] 开始自动保存")
            append_rx_text("[系统] 自动保存到 %s" % autosave_path)
        elif not bool(autosave_sw.value) and autosave_fp is not None:
            try:
                autosave_fp.close()
            except Exception:
                pass
            autosave_fp = None
            append_rx_text("[系统] 已停止自动保存")

        # 显示接收开关（只影响「接收到的数据」，系统和发送内容照常显示）
        show_rx = bool(show_rx_sw.value)

        # 每秒检查一次设置有没有变，变了就写盘（别每帧都读一堆控件）
        now_s = time.monotonic()
        if now_s - last_save_time >= 1.0:
            last_save_time = now_s
            cur = {
                "port": str(port_box.value),
                "baud": str(baud_box.value),
                "data_bits": str(data_box.value),
                "stop_bits": str(stop_box.value),
                "parity": str(parity_box.value),
                "flow": str(flow_box.value),
                "ending": str(ending_box.value),
                "hex_send": bool(hex_send.value),
                "timestamp": bool(stamp.value),
                "auto_ms": int(auto_ms.value),
                "plot": bool(plot_sw.value),
                "show_rx": bool(show_rx_sw.value),
            }
            if cur != last_saved:
                last_saved = cur
                save_settings(cur)

        # 折线图开关
        if bool(plot_sw.value) != plot_on:
            plot_on = bool(plot_sw.value)
            line_buf = ""
            if plot_on:
                plot.show()
                append_rx_text("[系统] 折线图已启用（等待 \"a,b,c\" 格式数据）")
            else:
                plot.hide()
                append_rx_text("[系统] 折线图已关闭")

        if plot_clear_btn.clicked:
            plot.clear()
            append_rx_text("[系统] 曲线已清空")

        if clear_btn.clicked:
            rx_lines.clear()
            rx_hex_lines.clear()
            rx_text.set("")
            rx_hex.set("")
            rx_total = 0
            tx_total = 0
            refresh_status("已清空")

        if export_btn.clicked:
            log_dir = os.path.join(os.path.dirname(os.path.dirname(app_dir())), "logs")
            try:
                os.makedirs(log_dir, exist_ok=True)
                path = os.path.join(log_dir, "serial_%s.log" % time.strftime("%Y%m%d_%H%M%S"))
                with open(path, "w", encoding="utf-8") as fp:
                    fp.write("=== 文本 ===\n" + "\n".join(rx_lines))
                    fp.write("\n\n=== HEX ===\n" + "\n".join(rx_hex_lines))
                append_rx_text("[系统] 已导出：%s" % path)
            except Exception as e:
                append_rx_text("[系统] 导出失败：%r" % e)

        # 添加新指令
        if cmd_input.submitted:
            add_cmd(cmd_input.value)
            cmd_input.set_text("")

        # 指令按钮
        for i, b in enumerate(cmd_btns):
            if i >= len(cmds):
                break
            if b.clicked:
                if bool(edit_sw.value):
                    del_cmd(i)
                else:
                    do_send(cmds[i])
                break

        if send_box.submitted:
            do_send(send_box.value.strip())
            send_box.set_text("")

        interval = int(auto_ms.value)
        if interval > 0 and serial is not None:
            if (time.monotonic() - auto_last) * 1000 >= interval:
                auto_last = time.monotonic()
                text = send_box.value.strip()
                if text:
                    do_send(text)

        if bool(dtr_sw.value) != dtr_last:
            dtr_last = bool(dtr_sw.value)
            if serial is not None and serial.is_open:
                serial.set_dtr(dtr_last)
                append_rx_text("[系统] DTR %s" % ("开" if dtr_last else "关"))
        if bool(rts_sw.value) != rts_last:
            rts_last = bool(rts_sw.value)
            if serial is not None and serial.is_open:
                serial.set_rts(rts_last)
                append_rx_text("[系统] RTS %s" % ("开" if rts_last else "关"))

        if serial is not None:
            data = serial.read(512)
            if data:
                rx_total += len(data)
                text = data.decode("utf-8", errors="replace")
                if autosave_fp is not None:
                    autosave_write("[%s] %s" % (time.strftime("%H:%M:%S"),
                                                hexdump(data)))
                if show_rx:
                    append_rx_hex("← " + hexdump(data))
                    for line in text.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
                        if line:
                            append_rx_text("← " + line)
                feed_plot(text)
                refresh_status()

        ansylab.sleep(0.02)


if __name__ == "__main__":
    main()
