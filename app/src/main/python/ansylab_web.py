"""网页推流 + 远程控制（纯标准库实现，不依赖 aiohttp/fastapi）。

为什么不用 aiohttp：Chaquopy 的包仓库里没有它的纯 Python 依赖
（attrs / charset-normalizer / async-timeout / aiosignal / idna），
要么手工往 wheels/ 里凑、要么破掉离线构建；而这里要的功能
（MJPEG 推流 + JSON 控制）用标准库 300 行就能写完。

用法（脚本里）：

    view = ansylab.display()
    web = ansylab.web(port=8080)      # 启动后返回访问地址
    ansylab.log("浏览器打开 %s" % web.url)

    while ansylab.running():
        frame = cam.read()
        view.show(frame)              # show() 会自动把这一帧推给网页

接口：
    GET  /               控制台页面（视频 + 控件）
    GET  /stream         MJPEG 视频流（<img src="/stream">）
    GET  /api/widgets    控件状态 JSON
    POST /api/widget     操作控件 {"id","action","value"}
"""

import json
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

import cv2

# --------------------------------------------------------------------------- #
# 本机地址
#
# Android 上按 8.8.8.8 查路由通常会选中移动数据，拿到的是运营商内网地址，
# 连热点的电脑根本访问不到。所以这里枚举所有网卡，再按「像不像局域网」排序。
# --------------------------------------------------------------------------- #
def local_ips(bridge=None):
    """本机所有 IPv4，热点/局域网排前面。

    Python 的 socket.if_nameindex() 在 Android 上会抛 PermissionError，
    所以优先问 Kotlin 侧（PyBridge.localIps），拿不到再退回到路由探测。
    """
    ips = []
    if bridge is not None:
        try:
            raw = str(bridge.localIps() or "")
            ips = [x for x in raw.split(",") if x]
        except Exception:
            ips = []

    if not ips:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ips = [s.getsockname()[0]]
            s.close()
        except Exception:
            ips = ["127.0.0.1"]

    def rank(ip):
        # 热点一般是 192.168.x / 172.16-31.x；移动数据常是 10.x
        if ip.startswith("192.168."):
            return 0
        if ip.startswith("172."):
            try:
                if 16 <= int(ip.split(".")[1]) <= 31:
                    return 1
            except Exception:
                pass
        if ip.startswith("10."):
            return 3
        return 2

    return sorted(set(ips), key=rank)


# --------------------------------------------------------------------------- #
# 最新帧槽：主循环写、HTTP 线程读，覆盖式写入（慢客户端不会拖慢主循环）
# --------------------------------------------------------------------------- #
class FrameBus:
    def __init__(self, quality=70):
        self._lock = threading.Lock()
        self._jpeg = None
        self._seq = 0
        self._quality = int(quality)
        self.clients = 0          # 当前有几个流客户端（0 时跳过编码，零开销）

    def set_quality(self, q):
        self._quality = max(20, min(95, int(q)))

    def publish(self, arr):
        if self.clients <= 0 or arr is None:
            return
        try:
            ok, buf = cv2.imencode(
                ".jpg", arr, [int(cv2.IMWRITE_JPEG_QUALITY), self._quality])
        except Exception:
            return
        if not ok:
            return
        with self._lock:
            self._jpeg = buf.tobytes()
            self._seq += 1

    def latest(self):
        with self._lock:
            return self._jpeg, self._seq


# --------------------------------------------------------------------------- #
# 控件 → JSON
# --------------------------------------------------------------------------- #
def _describe(w, bridge=None):
    """把一个控件对象转成网页要的 JSON。

    Python 侧的 `.label` 是只写的（写进 Kotlin 模型），读回来要问 bridge。
    """
    t = type(w).__name__
    d = {"id": w.id, "type": t}
    try:
        d["label"] = str(bridge.widgetLabelOf(w.id)) if bridge is not None else ""
        d["region"] = str(bridge.widgetRegion(w.id)) if bridge is not None else "panel"
        d["visible"] = bool(w.visible)
        d["enabled"] = bool(w.enabled)
    except Exception:
        pass
    try:
        if t == "Slider":
            lo, hi, step = 0.0, 1.0, 0.01
            if bridge is not None:
                parts = str(bridge.widgetRange(w.id)).split(",")
                if len(parts) == 3:
                    lo, hi, step = float(parts[0]), float(parts[1]), float(parts[2])
            d.update(value=float(w.value), min=lo, max=hi, step=step)
        elif t in ("Toggle", "CheckBox"):
            d.update(value=bool(w.value))
        elif t == "Plot":
            pts = getattr(w, "_plot", None)
            y0, y1 = getattr(w, "_y", (None, None))
            win = float(getattr(w, "_win", 0.0) or 0.0)
            # 时间窗口在这里裁好再发，网页端不用再存时间戳
            rows = w.window_points() if hasattr(w, "window_points") else (pts or [])
            # 降采样：画布只有 640px，多于 64 个点肉眼看不出差别，
            # 但 JSON 体积会随点数线性增长（IMU 三图曾达到 135 KB/次）
            rows = _downsample(rows, 64)
            d.update(channels=int(getattr(w, "_ch", 1)),
                     max=int(getattr(w, "_n", 200)),
                     window=win,
                     yMin=(None if y0 is None else float(y0)),
                     yMax=(None if y1 is None else float(y1)),
                     points=[list(p) for p in rows])
        elif t == "ProgressBar":
            mx = 100.0
            if bridge is not None:
                parts = str(bridge.widgetRange(w.id)).split(",")
                if len(parts) == 3:
                    mx = float(parts[1])
            d.update(value=float(w.value), max=mx,
                     indeterminate=(bool(bridge.widgetIndeterminate(w.id))
                                    if bridge is not None else False))
        elif t in ("ComboBox", "RadioGroup"):
            d.update(index=int(w.index), items=[str(x) for x in (w.items or [])])
        elif t in ("TextField", "TextInput"):
            d.update(text=str(w.value))
        elif t == "TextOutput":
            d.update(text=str(w.text))
        elif t == "Button":
            d.update(value=0)
        elif t in ("Label", "Header"):
            d.update(text=str(w.text))
        elif t == "Image":
            import ansylab as _a
            d.update(imgSeq=int(_a._image_seq.get(w.id, 0)),
                     hasImage=(w.id in _a._image_store))
        elif t == "Joystick":
            d.update(x=float(w.x), y=float(w.y), dual=False)
        elif t == "DoubleJoystick":
            lx, ly = w.left
            rx, ry = w.right
            d.update(x=float(lx), y=float(ly),
                     x2=float(rx), y2=float(ry), dual=True)
    except Exception as e:
        d["error"] = str(e)
    return d


def _downsample(rows, limit):
    """等间隔抽稀，保留首尾点，避免曲线形状失真。"""
    n = len(rows)
    if n <= limit or limit < 2:
        return rows
    step = (n - 1) / float(limit - 1)
    idx = sorted({int(round(i * step)) for i in range(limit)} | {0, n - 1})
    return [rows[i] for i in idx if 0 <= i < n]


class WebServer:
    def __init__(self, registry=None, bridge=None, port=8080, password="123456",
                 fps=15.0, quality=70):
        self.registry = registry if registry is not None else []
        self.bridge = bridge
        self.port = int(port)
        self._password = password or "123456"
        self.min_interval = 1.0 / max(1.0, float(fps))
        self.bus = FrameBus(quality)

        # 自适应档位：网络慢就降帧率/质量，恢复后再升回去
        self._levels = [(15, 70), (10, 60), (7, 50)]
        self._conns = {}            # conn_id -> {"level":0, "ewma":0.0, "last":ts}
        self._conn_seq = 0
        self.max_clients = 3
        self._httpd = None
        self._thread = None
        self._stop = threading.Event()
        # 脚本自己调 ansylab.web() 时置 True：生命周期由脚本管，
        # 不被「设置里关掉了网页控制」这个条件停掉
        self._explicit = False

    # ---------------------------------------------------------------- 生命周期
    def start(self):
        if self._httpd is not None:
            return self
        handler = _make_handler(self)
        # 端口偶尔还没释放干净，重试几次
        last = None
        for _ in range(20):
            try:
                self._httpd = ThreadingHTTPServer(("0.0.0.0", self.port), handler)
                break
            except OSError as e:
                last = e
                time.sleep(0.15)
        if self._httpd is None:
            raise last or OSError("bind failed")
        self._httpd.daemon_threads = True
        self._thread = threading.Thread(
            target=self._httpd.serve_forever, name="ansylab-web", daemon=True)
        self._thread.start()
        threading.Thread(target=self._watch, name="ansylab-web-watch",
                         daemon=True).start()
        return self

    def _watch(self):
        """设置里把开关关掉后，1 秒内自己停掉（端口不留着）。"""
        while not self._stop.is_set():
            time.sleep(1.0)
            if not self._explicit and not self.enabled():
                self.stop()
                return

    def stop(self):
        self._stop.set()
        if self._httpd is not None:
            try:
                self._httpd.shutdown()
                self._httpd.server_close()
            except Exception:
                pass
            self._httpd = None
        self._thread = None

    @property
    def running(self):
        return self._httpd is not None

    # ------------------------------------------------------------------ 推流
    def publish(self, frame):
        self.bus.publish(frame)

    # ---------------------------------------------------------------- 自适应
    def new_conn(self):
        """登记一个流客户端，返回它的连接 id。"""
        self._conn_seq += 1
        cid = self._conn_seq
        self._conns[cid] = {"level": 0, "ewma": 0.0, "last": time.time()}
        self._sync_quality()
        return cid

    def drop_conn(self, cid):
        self._conns.pop(cid, None)
        self._sync_quality()

    def conn_interval(self, cid):
        st = self._conns.get(cid)
        if not st:
            return self.min_interval
        fps, _ = self._levels[st["level"]]
        return 1.0 / float(fps)

    def adapt(self, cid, cost):
        """按每个连接自己的发送耗时调档；慢的降自己的帧率，不拖累别人。"""
        st = self._conns.get(cid)
        if st is None:
            return
        st["ewma"] = st["ewma"] * 0.7 + cost * 0.3 if st["ewma"] else cost
        now = time.time()
        if now - st["last"] < 1.5:
            return
        st["last"] = now
        if st["ewma"] > 0.12 and st["level"] < len(self._levels) - 1:
            st["level"] += 1
            self._sync_quality()
        elif st["ewma"] < 0.03 and st["level"] > 0:
            st["level"] -= 1
            self._sync_quality()

    def _sync_quality(self):
        """编码只做一次：质量取所有在线客户端里最保守的那个。"""
        if not self._conns:
            self.bus.set_quality(self._levels[0][1])
            return
        q = min(self._levels[st["level"]][1] for st in self._conns.values())
        self.bus.set_quality(q)

    @property
    def level(self):
        if not self._conns:
            return 0
        return max(st["level"] for st in self._conns.values())

    # ------------------------------------------------------------------ 密码
    def password(self):
        """当前密码：优先读设置页（运行中改密码立即生效）。"""
        try:
            if self.bridge is not None:
                pw = str(self.bridge.webPassword())
                if pw:
                    self._password = pw
        except Exception:
            pass
        return self._password

    def use_password(self):
        try:
            if self.bridge is not None:
                return bool(self.bridge.webUsePassword())
        except Exception:
            pass
        return True

    def pw_hash(self):
        import hashlib
        return hashlib.sha256(self.password().encode("utf-8")).hexdigest()

    def enabled(self):
        """设置里的开关。读不到时按开启处理（脚本自己调 web() 的场景）。"""
        try:
            if self.bridge is not None:
                return bool(self.bridge.webEnabled())
        except Exception:
            pass
        return True

    # ------------------------------------------------------------------ 地址
    @property
    def ips(self):
        """本机所有可用 IPv4（热点/局域网在前，移动数据在后）。"""
        return local_ips(self.bridge)

    @property
    def ip(self):
        ips = self.ips
        return ips[0] if ips else "127.0.0.1"

    @property
    def url(self):
        return "http://%s:%d/" % (self.ip, self.port)

    @property
    def urls(self):
        return ["http://%s:%d/" % (ip, self.port) for ip in self.ips]

    # -------------------------------------------------------------- 控件读写
    def find(self, widget_id):
        for w in list(self.registry):
            if getattr(w, "id", None) == widget_id:
                return w
        return None

    def widgets_json(self):
        out = []
        for w in list(self.registry):
            if getattr(w, "id", None) is None:
                continue
            out.append(_describe(w, self.bridge))
        return out

    def apply(self, widget_id, action, value):
        w = self.find(widget_id)
        if w is None:
            return False, "控件不存在"
        t = type(w).__name__
        try:
            if action == "set_value" and t == "Slider":
                w.value = float(value)
            elif action == "set_flag" and t in ("Toggle", "CheckBox"):
                w.value = bool(value)
            elif action == "set_index" and t in ("ComboBox", "RadioGroup"):
                w.index = int(value)
            elif action == "set_text" and t in ("TextField", "TextInput"):
                # 网页上的「确定」= 写内容 + 标记提交，否则脚本读不到 submitted
                self.bridge.widgetSubmitText(w.id, str(value))
            elif action == "click" and t == "Button":
                self.bridge.widgetClick(w.id)
            elif action == "set_joystick" and t in ("Joystick", "DoubleJoystick"):
                x, y = value[0], value[1]
                second = bool(value[2]) if len(value) > 2 else False
                self.bridge.widgetSetJoystick(w.id, float(x), float(y), second)
            else:
                return False, "不支持的操作 %s/%s" % (t, action)
        except Exception as e:
            return False, str(e)
        return True, ""


# --------------------------------------------------------------------------- #
# HTTP 处理
# --------------------------------------------------------------------------- #
def _make_handler(srv):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):      # 不刷屏
            pass

        # ------------------------------------------------------------ 鉴权
        def _cookie_pw(self):
            raw = self.headers.get("Cookie") or ""
            for part in raw.split(";"):
                k, _, v = part.strip().partition("=")
                if k == "ansylab_pw":
                    return v.strip()
            return ""

        def _authed(self):
            if not srv.use_password():
                return True
            return self._cookie_pw() == srv.pw_hash()

        def _send(self, code, body, ctype="text/plain; charset=utf-8"):
            data = body.encode("utf-8") if isinstance(body, str) else body
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        # ------------------------------------------------------------- 图片
        def _image(self):
            q = parse_qs(urlparse(self.path).query)
            wid = q.get("id", [""])[0]
            try:
                import ansylab as _a
                data = _a._image_store.get(wid)
            except Exception:
                data = None
            if not data:
                self._send(404, b"", "image/png")
                return
            self._send(200, data, "image/png")

        # -------------------------------------------------------------- GET
        def do_GET(self):
            path = urlparse(self.path).path
            if path == "/" and not self._authed():
                self._send(200, LOGIN.replace("{{ERR}}", ""),
                           "text/html; charset=utf-8")
                return
            if not self._authed():
                self._send(401, "请先登录")
                return
            if path == "/":
                self._send(200, PAGE, "text/html; charset=utf-8")
            elif path == "/stream":
                self._stream()
            elif path == "/api/image":
                self._image()
            elif path == "/ws":
                self._ws()
            elif path == "/api/webstat":
                jpg, seq = srv.bus.latest()
                self._send(200, json.dumps({
                    "clients": srv.bus.clients,
                    "seq": seq,
                    "hasFrame": jpg is not None,
                    "level": srv.level,
                    "interval": srv.min_interval,
                    "bound": _is_bound(),
                }), "application/json; charset=utf-8")
            elif path == "/api/widgets":
                self._send(200, json.dumps(srv.widgets_json()),
                           "application/json; charset=utf-8")
            else:
                self._send(404, "not found")

        # ------------------------------------------------------------- POST
        def do_POST(self):
            path = urlparse(self.path).path
            if path == "/login":
                self._login()
                return
            if not self._authed():
                self._send(401, "请先登录")
                return
            if path != "/api/widget":
                self._send(404, "not found")
                return
            try:
                n = int(self.headers.get("Content-Length") or 0)
                payload = json.loads(self.rfile.read(n).decode("utf-8") or "{}")
            except Exception as e:
                self._send(400, "bad json: %s" % e)
                return
            ok, msg = srv.apply(payload.get("id"), payload.get("action"),
                                payload.get("value"))
            self._send(200 if ok else 400,
                       json.dumps({"ok": ok, "msg": msg}),
                       "application/json; charset=utf-8")

        # ------------------------------------------------------------- 登录
        def _login(self):
            try:
                n = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(n).decode("utf-8", "replace")
                form = parse_qs(body)
                pwd = (form.get("pwd") or [""])[0]
            except Exception:
                pwd = ""
            if pwd and pwd == srv.password():
                cookie = "ansylab_pw=%s; Path=/; Max-Age=604800; SameSite=Lax" \
                    % srv.pw_hash()
                body = json.dumps({"ok": True}).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Set-Cookie", cookie)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            self._send(200, json.dumps({"ok": False, "msg": "密码不正确"}),
                       "application/json; charset=utf-8")

        # ----------------------------------------------------------- WebSocket
        def _ws(self):
            """极简 WebSocket：只做「服务器 → 浏览器」的控件状态推送。

            浏览器发来的数据一律忽略（控件操作仍走 POST），
            这样不用实现完整的帧解析，够用且稳。
            """
            key = self.headers.get("Sec-WebSocket-Key")
            if not key:
                self._send(400, "need websocket key")
                return
            import base64
            import hashlib
            accept = base64.b64encode(hashlib.sha1(
                (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()
            ).digest()).decode()
            self.send_response(101, "Switching Protocols")
            self.send_header("Upgrade", "websocket")
            self.send_header("Connection", "Upgrade")
            self.send_header("Sec-WebSocket-Accept", accept)
            self.end_headers()

            conn = self.connection
            conn.settimeout(0.2)
            last = ""
            last_rev = -1
            try:
                while not srv._stop.is_set():
                    try:
                        data = conn.recv(4096)
                        if not data:
                            break
                        # 客户端发来 close / ping 时简单回应
                        op = data[0] & 0x0F
                        if op == 0x8:
                            break
                        if op == 0x9:
                            conn.sendall(b"\x8a\x00")
                    except socket.timeout:
                        pass
                    except OSError:
                        break

                    # 控件层维护 revision：没变化就不重建 JSON（否则每 150ms 重算一次全量）
                    rev = -1
                    try:
                        rev = int(srv.bridge.widgetRevision())
                    except Exception:
                        rev = -1
                    payload = None
                    if rev != last_rev or last is None or rev < 0:
                        last_rev = rev
                        payload = json.dumps(srv.widgets_json())
                    if payload is not None and payload != last:
                        last = payload
                        raw = payload.encode("utf-8")
                        n = len(raw)
                        if n < 126:
                            header = bytes([0x81, n])
                        elif n < 65536:
                            header = bytes([0x81, 126]) + n.to_bytes(2, "big")
                        else:
                            header = bytes([0x81, 127]) + n.to_bytes(8, "big")
                        conn.sendall(header + raw)
                    time.sleep(0.15)
            except Exception:
                pass
            finally:
                try:
                    conn.close()
                except Exception:
                    pass

        # ------------------------------------------------------------ MJPEG
        def _stream(self):
            if srv.bus.clients >= srv.max_clients:
                self._send(503, "同时观看人数已达上限（%d）" % srv.max_clients)
                return
            self.send_response(200)
            self.send_header(
                "Content-Type", "multipart/x-mixed-replace; boundary=frame")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            srv.bus.clients += 1
            cid = srv.new_conn()
            last_seq = -1
            try:
                while not srv._stop.is_set():
                    jpg, seq = srv.bus.latest()
                    if jpg is None or seq == last_seq:
                        time.sleep(0.01)
                        continue
                    last_seq = seq
                    t0 = time.monotonic()
                    self.wfile.write(b"--frame\r\n")
                    self.wfile.write(b"Content-Type: image/jpeg\r\n")
                    self.wfile.write(b"Content-Length: %d\r\n\r\n" % len(jpg))
                    self.wfile.write(jpg)
                    self.wfile.write(b"\r\n")
                    srv.adapt(cid, time.monotonic() - t0)
                    time.sleep(srv.conn_interval(cid))
            except (ConnectionResetError, BrokenPipeError, OSError):
                pass          # 客户端正常断开
            except Exception:
                import traceback
                traceback.print_exc()
            finally:
                srv.bus.clients -= 1
                srv.drop_conn(cid)

    return Handler


# --------------------------------------------------------------------------- #
# 页面（黑白极简，和 App 风格一致）
# --------------------------------------------------------------------------- #
def _is_bound():
    try:
        import ansylab as _a
        return _a._web is not None
    except Exception:
        return False


LOGIN = """<!DOCTYPE html>
<html lang="zh"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>AnsyLab</title>
<style>
*{box-sizing:border-box}
body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;
 background:#fff;color:#111;font:15px/1.5 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif}
.box{width:min(360px,86vw);border:1px solid #e6e6e6;border-radius:14px;padding:26px 24px}
h1{margin:0 0 6px;font-size:18px;font-weight:600;letter-spacing:.5px}
p{margin:0 0 18px;color:#888;font-size:13px}
input{width:100%;border:1px solid #e6e6e6;border-radius:10px;padding:11px 13px;
 font-size:15px;outline:none;font-family:inherit;letter-spacing:.12em;
 -webkit-text-security:disc;-moz-text-security:disc;text-security:disc}
input:focus{border-color:#bbb}
button{width:100%;margin-top:12px;background:#111;color:#fff;border:0;border-radius:10px;
 padding:12px;font-size:15px;cursor:pointer;font-family:inherit}
.err{margin-top:12px;color:#c0392b;font-size:13px;min-height:18px}
</style></head><body>
<div class="box">
  <h1>AnsyLab</h1>
  <p>请输入访问密码</p>
  <input id="pwd" type="text" autocomplete="off" autocapitalize="off"
         autocorrect="off" spellcheck="false" inputmode="text" placeholder="密码">
  <button id="go" type="button">进入</button>
  <div class="err" id="err">{{ERR}}</div>
</div>
<script>
// 不用 <form> 提交、也不用 type=password：
// 浏览器（含 Edge/Chrome 密码管理器）就是靠这两点弹「保存密码」的。
const inp = document.getElementById('pwd');
const err = document.getElementById('err');
const go = document.getElementById('go');
async function submit() {
  const pwd = inp.value.trim();
  if (!pwd) { err.textContent = '请输入密码'; return; }
  go.disabled = true;
  try {
    const r = await fetch('/login', {
      method: 'POST',
      headers: {'Content-Type': 'application/x-www-form-urlencoded'},
      body: 'pwd=' + encodeURIComponent(pwd)
    });
    const j = await r.json();
    if (j.ok) { location.href = '/'; return; }
    err.textContent = j.msg || '密码不正确';
  } catch (e) {
    err.textContent = '网络错误';
  }
  go.disabled = false;
  inp.select();
}
go.addEventListener('click', submit);
inp.addEventListener('keydown', e => { if (e.key === 'Enter') submit(); });
inp.focus();
</script>
</body></html>
"""

PAGE = """<!DOCTYPE html>
<html lang="zh"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>AnsyLab</title>
<style>
*{box-sizing:border-box}
body{margin:0;background:#fff;color:#111;
 font:15px/1.5 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif;
 -webkit-text-size-adjust:100%}
header{padding:14px 18px;border-bottom:1px solid #e6e6e6;
 display:flex;align-items:center;gap:12px}
header h1{margin:0;font-size:17px;font-weight:600;letter-spacing:.5px}
#stat{margin-left:auto;color:#888;font-size:12px}
main{max-width:960px;margin:0 auto;padding:16px}
#view{width:100%;background:#000;border-radius:10px;display:none}
#vtools{display:none;gap:8px;align-items:center;margin:8px 0 4px}
#vtools button{padding:7px 14px;font-size:13px;min-width:0;flex:0 0 auto}
#vtools button:disabled{color:#bbb;border-color:#eee;cursor:default}
#vtip{color:#888;font-size:12px;margin-left:4px}
.regionhead{margin:16px 0 2px;font-size:12px;color:#999;letter-spacing:.5px}
#toast{position:fixed;left:50%;bottom:28px;transform:translateX(-50%);
 background:#111;color:#fff;padding:10px 18px;border-radius:10px;font-size:13px;
 opacity:0;pointer-events:none;transition:opacity .2s;max-width:80vw;z-index:99}
#toast.show{opacity:.92}

.card{border:1px solid #e6e6e6;border-radius:10px;padding:12px 14px;margin:8px 0}
.card.pad0{padding:0;border:0}
.title{font-size:13px;color:#555;margin-bottom:8px}
.row{display:flex;align-items:center;gap:12px}
.name{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:14px}
.val{color:#666;font-variant-numeric:tabular-nums;font-size:13px}

h3{margin:22px 0 6px;font-size:13px;color:#888;font-weight:500}
hr{border:0;border-top:1px solid #e6e6e6;margin:12px 0}

/* 文本 */
pre{margin:0;white-space:pre-wrap;word-break:break-all;font-size:13px;color:#333;
 max-height:220px;overflow:auto;font-family:inherit}
input[type=text],textarea{border:1px solid #e6e6e6;border-radius:10px;padding:9px 12px;
 font-size:14px;outline:none;background:#fff;color:#111;font-family:inherit;width:100%}
textarea{resize:vertical;min-height:64px}
input[type=text]:focus,textarea:focus{border-color:#bbb}

/* 按钮 */
button{background:#fff;color:#111;border:1px solid #e6e6e6;border-radius:10px;
 padding:9px 18px;font-size:14px;cursor:pointer;font-family:inherit;
 white-space:nowrap;flex:0 0 auto;min-width:72px;
 user-select:none;-webkit-user-select:none;-webkit-tap-highlight-color:transparent}
button:active{background:#f2f2f2}
button.wide{width:100%;padding:11px}

/* 开关 */
.switch{width:46px;height:26px;border-radius:13px;background:#cfcfcf;position:relative;
 transition:background .15s;cursor:pointer;flex:0 0 auto}
.switch.on{background:#111}
.switch .thumb{position:absolute;top:2px;left:2px;width:22px;height:22px;border-radius:50%;
 background:#fff;transition:left .15s;box-shadow:0 1px 2px rgba(0,0,0,.2)}
.switch.on .thumb{left:22px}

/* 单选 / 勾选 */
.opt{display:flex;align-items:center;gap:10px;padding:7px 0;cursor:pointer;
 user-select:none;-webkit-user-select:none}
.dot{width:16px;height:16px;border:1.5px solid #999;border-radius:50%;
 position:relative;flex:0 0 auto}
.dot.on{border-color:#111}
.dot.on:after{content:"";position:absolute;inset:3px;border-radius:50%;background:#111}
.box{width:16px;height:16px;border:1.5px solid #999;border-radius:3px;
 position:relative;flex:0 0 auto}
.box.on{border-color:#111;background:#111}
.box.on:after{content:"";position:absolute;left:4px;top:1px;width:4px;height:9px;
 border:2px solid #fff;border-top:0;border-left:0;transform:rotate(42deg)}

/* 下拉 */
select{border:1px solid #e6e6e6;border-radius:8px;padding:8px 10px;font-size:14px;
 background:#fff;color:#111;font-family:inherit;max-width:60%}

/* 滑块 */
input[type=range]{width:100%;accent-color:#111;margin:10px 0 2px}

/* 进度条 */
.shot{display:block;width:100%;border-radius:8px;margin-top:6px;background:#fafafa}
.plot{display:block;width:100%;height:auto;margin-top:6px;background:#fff;border-radius:6px}
.bar{position:relative;height:8px;background:#eee;border-radius:4px;overflow:hidden;margin-top:10px}
.bar>i{display:block;height:100%;background:#111;transition:width .12s linear}
.bar.ind>i{width:35%;animation:slide 1.15s infinite linear}
@keyframes slide{0%{transform:translateX(-110%)}100%{transform:translateX(320%)}}

/* 摇杆 */
.pads{display:flex;flex-wrap:wrap;gap:20px;align-items:center;justify-content:center;
 margin-top:10px}
.padwrap{display:flex;flex-direction:column;align-items:center;gap:8px;flex:0 0 auto}
.pad{position:relative;width:120px;height:120px;border:1.5px solid #111;border-radius:50%;
 background:#fff;cursor:pointer;touch-action:none;flex:0 0 auto;
 user-select:none;-webkit-user-select:none;-webkit-touch-callout:none;
 -webkit-tap-highlight-color:transparent}
.pad:before,.pad:after{content:"";position:absolute;background:#e6e6e6;pointer-events:none}
.pad:before{left:8%;right:8%;top:calc(50% - .5px);height:1px}
.pad:after{top:8%;bottom:8%;left:calc(50% - .5px);width:1px}
.knob{position:absolute;left:50%;top:50%;width:32%;height:32%;border-radius:50%;
 background:#111;transform:translate(-50%,-50%);pointer-events:none;transition:none}
body.dragging{user-select:none;-webkit-user-select:none;cursor:grabbing}
</style></head><body>
<header><h1>AnsyLab</h1><span id="stat">连接中…</span></header>
<main>
  <img id="view" alt="camera">
  <div id="vtools">
    <button id="vshot" type="button">截图</button>
    <button id="vrec" type="button">录制</button>
    <button id="vpause" type="button" disabled>暂停</button>
    <button id="vsave" type="button" disabled>保存</button>
    <span id="vtip"></span>
  </div>
  <div id="widgets"></div>
  <div id="toast"></div>
</main>
<script>
const TOKEN = new URLSearchParams(location.search).get('t') || '';
const api = p => p + (TOKEN ? (p.includes('?') ? '&' : '?') + 't=' + TOKEN : '');
const view = document.getElementById('view');
view.src = api('/stream');

const nodes = new Map();     // id -> {root, type, update, sig}
let layoutKey = '';

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}
function card() { return el('div', 'card'); }

let toastTimer = 0;
function toast(msg) {
  const t = document.getElementById('toast');
  if (!t) return;
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), 2600);
}

async function send(id, action, value) {
  try {
    const r = await fetch(api('/api/widget'), {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({id, action, value})
    });
    let j = null;
    try { j = await r.json(); } catch (e) {}
    if (!r.ok || (j && j.ok === false)) {
      toast('操作失败：' + ((j && j.msg) || ('HTTP ' + r.status)));
    } else if (!j) {
      toast('操作已发送，但服务端没有返回结果');
    }
  } catch (e) {
    toast('网络错误：' + (e && e.message ? e.message : '连接失败'));
  }
}

// ---------------------------------------------------------------- 控件构建
function buildToggle(w) {
  const c = card(), row = el('div', 'row');
  row.appendChild(el('span', 'name', w.label || '开关'));
  const sw = el('div', 'switch' + (w.value ? ' on' : ''));
  sw.appendChild(el('div', 'thumb'));
  row.appendChild(sw);
  c.appendChild(row);
  let val = !!w.value;
  sw.addEventListener('click', () => { send(w.id, 'set_flag', !val); });
  return {
    root: c,
    update(n) { val = !!n.value; sw.classList.toggle('on', val); }
  };
}

function buildCheckbox(w) {
  const c = card(), row = el('div', 'opt');
  const box = el('span', 'box' + (w.value ? ' on' : ''));
  row.appendChild(box);
  row.appendChild(el('span', 'name', w.label || '勾选'));
  c.appendChild(row);
  let val = !!w.value;
  row.addEventListener('click', () => { send(w.id, 'set_flag', !val); });
  return {root: c, update(n) { val = !!n.value; box.classList.toggle('on', val); }};
}

function buildButton(w) {
  const b = el('button', 'wide', w.label || '点击');
  b.addEventListener('click', () => send(w.id, 'click', 0));
  return {root: b, update() {}};
}

function buildSlider(w) {
  const c = card(), row = el('div', 'row');
  row.appendChild(el('span', 'name', w.label || '滑块'));
  const val = el('span', 'val', Number(w.value).toFixed(2));
  row.appendChild(val);
  const r = el('input');
  r.type = 'range';
  r.min = w.min; r.max = w.max; r.step = w.step; r.value = w.value;
  c.appendChild(row); c.appendChild(r);
  const fire = () => {
    val.textContent = Number(r.value).toFixed(2);
    send(w.id, 'set_value', Number(r.value));
  };
  r.addEventListener('input', () => { val.textContent = Number(r.value).toFixed(2); });
  r.addEventListener('change', fire);
  return {
    root: c,
    update(n) {
      if (document.activeElement === r) return;   // 正在拖就别抢
      r.value = n.value;
      val.textContent = Number(n.value).toFixed(2);
    }
  };
}

function buildSelect(w) {
  const c = card(), row = el('div', 'row');
  row.appendChild(el('span', 'name', w.label || '选择'));
  const sel = el('select');
  c.appendChild(row); row.appendChild(sel);
  const fill = (items, index) => {
    sel.innerHTML = '';
    items.forEach((it, i) => {
      const o = el('option', '', it);
      o.value = i;
      if (i === index) o.selected = true;
      sel.appendChild(o);
    });
  };
  fill(w.items || [], w.index);
  sel.addEventListener('change', () => send(w.id, 'set_index', Number(sel.value)));
  return {
    root: c,
    update(n) {
      const sig = (n.items || []).join('\u0001');
      if (sig !== sel._sig) { sel._sig = sig; fill(n.items || [], n.index); }
      else if (document.activeElement !== sel) sel.value = n.index;
    }
  };
}

function buildRadio(w) {
  const c = card();
  if (w.label) c.appendChild(el('div', 'title', w.label));
  const dots = [];
  (w.items || []).forEach((it, i) => {
    const row = el('div', 'opt');
    const dot = el('span', 'dot' + (i === w.index ? ' on' : ''));
    row.appendChild(dot);
    row.appendChild(el('span', 'name', it));
    row.addEventListener('click', () => send(w.id, 'set_index', i));
    c.appendChild(row);
    dots.push(dot);
  });
  return {
    root: c,
    update(n) { dots.forEach((d, i) => d.classList.toggle('on', i === n.index)); }
  };
}

function buildText(w) {
  const c = card();
  if (w.label) c.appendChild(el('div', 'title', w.label));
  const row = el('div', 'row');
  const inp = el('input');
  inp.type = 'text';
  inp.value = w.text || '';
  inp.placeholder = '输入内容';
  const btn = el('button', '', '确定');
  const submit = () => send(w.id, 'set_text', inp.value);
  btn.addEventListener('click', submit);
  inp.addEventListener('keydown', e => { if (e.key === 'Enter') submit(); });
  row.appendChild(inp); row.appendChild(btn);
  c.appendChild(row);
  return {
    root: c,
    update(n) { if (document.activeElement !== inp) inp.value = n.text || ''; }
  };
}

function buildTextArea(w) {
  const c = card();
  if (w.label) c.appendChild(el('div', 'title', w.label));
  const ta = el('textarea');
  ta.value = w.text || '';
  const btn = el('button', 'wide', '确定');
  btn.style.marginTop = '8px';
  btn.addEventListener('click', () => send(w.id, 'set_text', ta.value));
  c.appendChild(ta); c.appendChild(btn);
  return {
    root: c,
    update(n) { if (document.activeElement !== ta) ta.value = n.text || ''; }
  };
}

function buildOutput(w) {
  const c = card();
  if (w.label) c.appendChild(el('div', 'title', w.label));
  const pre = el('pre', '', w.text || '');
  // 直接写死换行样式：不依赖外部 CSS，避免被别的规则影响
  pre.style.whiteSpace = 'pre-wrap';
  pre.style.wordBreak = 'break-all';
  c.appendChild(pre);
  return {
    root: c,
    update(n) {
      const t = n.text == null ? '' : String(n.text);
      // 不做「内容相同就跳过」：文本可能被清空或整段替换，直接覆盖最保险
      pre.textContent = t;
      pre.scrollTop = pre.scrollHeight;
    }
  };
}

function buildProgress(w) {
  const c = card(), row = el('div', 'row');
  row.appendChild(el('span', 'name', w.label || '进度'));
  const val = el('span', 'val', '');
  row.appendChild(val);
  c.appendChild(row);
  const bar = el('div', 'bar'), fill = el('i');
  bar.appendChild(fill);
  c.appendChild(bar);
  return {
    root: c,
    update(n) {
      const ind = !!n.indeterminate;
      bar.classList.toggle('ind', ind);
      if (ind) {
        fill.style.width = '';
        val.textContent = '进行中';
      } else {
        const max = Number(n.max) > 0 ? Number(n.max) : 100;
        const p = Math.max(0, Math.min(1, (Number(n.value) || 0) / max));
        fill.style.width = (p * 100).toFixed(1) + '%';
        val.textContent = Math.round(n.value || 0) + ' / ' + Math.round(max);
      }
    }
  };
}

function buildPlot(w) {
  const c = card();
  if (w.label) c.appendChild(el('div', 'title', w.label));
  const cv = el('canvas', 'plot');
  cv.width = 640; cv.height = 260;
  c.appendChild(cv);
  const ctx = cv.getContext('2d');
  const COLORS = ['#111111', '#e05252', '#4a90d9', '#2e9e5b', '#d98324'];
  let last = null;

  function draw(n) {
    const pts = n.points || [];
    const ch = Math.max(1, Math.min(5, n.channels || 1));
    const W = cv.width, H = cv.height;
    ctx.clearRect(0, 0, W, H);
    // 网格
    ctx.strokeStyle = '#e6e6e6';
    ctx.lineWidth = 1;
    for (let i = 1; i <= 3; i++) {
      const y = H * i / 4;
      ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(W, y); ctx.stroke();
    }
    if (pts.length < 2) {
      ctx.fillStyle = '#999';
      ctx.font = '20px sans-serif';
      ctx.fillText('等待数据…', 12, 30);
      return;
    }
    let lo = Infinity, hi = -Infinity;
    for (const p of pts) {
      for (let k = 0; k < Math.min(ch, p.length); k++) {
        lo = Math.min(lo, p[k]);
        hi = Math.max(hi, p[k]);
      }
    }
    if (n.yMin != null && n.yMax != null && n.yMax > n.yMin) {
      lo = n.yMin; hi = n.yMax;
    } else if (hi - lo < 0.5) {
      const mid = (hi + lo) / 2;
      lo = mid - 0.25; hi = mid + 0.25;
    }
    const pad = (hi - lo) * 0.12;
    lo -= pad; hi += pad;
    const span = hi - lo;
    const maxPts = Math.max(2, n.max || 200);
    // 时间窗口模式：按当前实际点数铺满；否则按固定点数窗口
    const spanPts = (n.window > 0) ? Math.max(2, pts.length) : maxPts;
    const stepX = W / (spanPts - 1);

    for (let k = 0; k < ch; k++) {
      ctx.beginPath();
      ctx.strokeStyle = COLORS[k];
      ctx.lineWidth = 2;
      let started = false;
      for (let i = 0; i < pts.length; i++) {
        const p = pts[i];
        if (k >= p.length) continue;
        const x = i * stepX;
        const y = H - (p[k] - lo) / span * H;
        if (!started) { ctx.moveTo(x, y); started = true; }
        else ctx.lineTo(x, y);
      }
      if (started) ctx.stroke();
    }

    // 最新值
    const lastP = pts[pts.length - 1];
    const names = ['x', 'y', 'z', 'c4', 'c5'];
    let txt = '';
    for (let k = 0; k < Math.min(ch, lastP.length); k++) {
      if (k) txt += '  ';
      txt += names[k] + '=' + Number(lastP[k]).toFixed(2);
    }
    ctx.fillStyle = '#666';
    ctx.font = '18px sans-serif';
    ctx.fillText(txt, 8, 22);
  }

  draw(w);
  return {root: c, update: draw};
}

function buildImage(w) {
  const c = card();
  if (w.label) c.appendChild(el('div', 'title', w.label));
  const img = el('img', 'shot');
  c.appendChild(img);
  let seq = -1;
  const load = n => {
    if (!n.hasImage) { img.style.display = 'none'; return; }
    if (n.imgSeq === seq) return;
    seq = n.imgSeq;
    img.style.display = 'block';
    img.src = api('/api/image?id=' + encodeURIComponent(n.id) + '&v=' + seq);
  };
  load(w);
  return {root: c, update: load};
}

function buildJoystick(w) {
  const c = card();
  if (w.label) c.appendChild(el('div', 'title', w.label));
  const wrap = el('div', 'pads');
  const dual = w.type === 'DoubleJoystick';
  const updaters = [];

  function makePad(second, x0, y0) {
    const pad = el('div', 'pad');
    const knob = el('div', 'knob');
    pad.appendChild(knob);
    const val = el('span', 'val', fmt(x0, y0));
    let dragging = false, timer = 0, pending = null;
    let holdUntil = 0;          // 松手后的一小段时间内不信服务端回传的值
    let cx = x0, cy = y0;

    function fmt(x, y) {
      return (x >= 0 ? '+' : '') + Number(x).toFixed(2) + ', ' +
             (y >= 0 ? '+' : '') + Number(y).toFixed(2);
    }
    function paint(x, y) {
      cx = x; cy = y;
      knob.style.left = (50 + x * 34) + '%';
      knob.style.top = (50 - y * 34) + '%';
      val.textContent = fmt(x, y);
    }
    function pos(ev) {
      const r = pad.getBoundingClientRect();
      const mx = r.left + r.width / 2, my = r.top + r.height / 2;
      let dx = ev.clientX - mx, dy = ev.clientY - my;
      const maxR = Math.max(1, r.width / 2 - r.width * 0.16);
      const d = Math.hypot(dx, dy);
      if (d > maxR) { dx = dx / d * maxR; dy = dy / d * maxR; }
      return [Math.max(-1, Math.min(1, dx / maxR)),
              Math.max(-1, Math.min(1, -dy / maxR))];
    }
    function push(x, y) {
      pending = [x, y];
      if (timer) return;
      timer = setTimeout(() => {
        timer = 0;
        if (pending) send(w.id, 'set_joystick', [pending[0], pending[1], second]);
      }, 50);
    }
    pad.addEventListener('pointerdown', ev => {
      ev.preventDefault();
      dragging = true;
      document.body.classList.add('dragging');
      try { pad.setPointerCapture(ev.pointerId); } catch (e) {}
      const [x, y] = pos(ev);
      paint(x, y); push(x, y);
    });
    pad.addEventListener('pointermove', ev => {
      if (!dragging) return;
      ev.preventDefault();
      const [x, y] = pos(ev);
      paint(x, y); push(x, y);
    });
    const stop = ev => {
      if (!dragging) return;
      dragging = false;
      document.body.classList.remove('dragging');
      // 关键：把还在排队的节流请求取消掉。
      // 否则松手后 50ms 内那个 timer 还会把「拖动时的旧坐标」发上去，
      // 服务端就停在旧值，轮询回传后摇杆头又跳回去 —— 看起来就是卡住。
      if (timer) { clearTimeout(timer); timer = 0; }
      pending = null;
      paint(0, 0);
      holdUntil = Date.now() + 400;
      send(w.id, 'set_joystick', [0, 0, second]);
    };
    pad.addEventListener('pointerup', stop);
    pad.addEventListener('pointercancel', stop);
    // 兜底：在页面外松手、或被浏览器吞掉 pointercapture 时也能复位
    document.addEventListener('pointerup', stop, true);
    document.addEventListener('pointercancel', stop, true);
    window.addEventListener('blur', stop);

    paint(x0, y0);
    updaters.push({
      paint,
      isDragging: () => dragging,
      isHolding: () => Date.now() < holdUntil,
      second
    });
    return [pad, val];
  }

  // 每个摇杆（圆盘 + 数值）包成一列，整列居中
  const [p1, v1] = makePad(false, w.x || 0, w.y || 0);
  const w1 = el('div', 'padwrap');
  w1.appendChild(p1); w1.appendChild(v1);
  wrap.appendChild(w1);
  if (dual) {
    const [p2, v2] = makePad(true, w.x2 || 0, w.y2 || 0);
    const w2 = el('div', 'padwrap');
    w2.appendChild(p2); w2.appendChild(v2);
    wrap.appendChild(w2);
  }
  c.appendChild(wrap);

  return {
    root: c,
    update(n) {
      for (const u of updaters) {
        // 拖拽中不抢；刚松手的 400ms 内也不抢（等 [0,0] 生效）
        if (u.isDragging() || u.isHolding()) continue;
        u.paint(u.second ? (n.x2 || 0) : (n.x || 0),
                u.second ? (n.y2 || 0) : (n.y || 0));
      }
    }
  };
}

const BUILDERS = {
  Label: w => {
    const d = el('div', '', w.text || w.label);
    return {
      root: d,
      update(n) {
        const t = n.text || n.label || '';
        if (d.textContent !== t) d.textContent = t;
      }
    };
  },
  Header: w => ({root: el('h3', '', w.text || w.label), update() {}}),
  Divider: w => ({root: el('hr'), update() {}}),
  Button: buildButton,
  Toggle: buildToggle,
  CheckBox: buildCheckbox,
  Slider: buildSlider,
  ComboBox: buildSelect,
  RadioGroup: buildRadio,
  TextField: buildText,
  TextInput: buildTextArea,
  TextOutput: buildOutput,
  ProgressBar: buildProgress,
  Plot: buildPlot,
  Image: buildImage,
  Joystick: buildJoystick,
  DoubleJoystick: buildJoystick,
};

function structureKey(list) {
  return list.filter(w => w.visible !== false && BUILDERS[w.type])
             .map(w => w.id + ':' + w.type + ':' + (w.region || 'panel')).join(',');
}

function widgetList(list) {
  return list.filter(w => w.visible !== false && BUILDERS[w.type]);
}

const REGIONS = [['main', '固定区'], ['panel', '折叠区']];

function rebuild(list) {
  const box = document.getElementById('widgets');
  box.innerHTML = '';
  nodes.clear();
  const items = widgetList(list);
  const both = REGIONS.some(([r]) => items.some(w => (w.region || 'panel') === r))
    && items.some(w => (w.region || 'panel') === 'main');
  for (const [reg, name] of REGIONS) {
    const group = items.filter(w => (w.region || 'panel') === reg);
    if (!group.length) continue;
    if (both) box.appendChild(el('div', 'regionhead', name));
    for (const w of group) {
      const n = BUILDERS[w.type](w);
      n.type = w.type;
      if (n.update) n.update(w);
      box.appendChild(n.root);
      nodes.set(w.id, n);
    }
  }
}

function apply(list) {
  const key = structureKey(list);
  if (key !== layoutKey) {
    layoutKey = key;
    rebuild(list);
    return;
  }
  for (const w of list) {
    const n = nodes.get(w.id);
    if (!n) continue;
    if (w.visible === false) { n.root.style.display = 'none'; continue; }
    n.root.style.display = '';
    if (n.update) n.update(w);
  }
}

async function refresh() {
  try {
    const r = await fetch(api('/api/widgets'));
    const list = JSON.parse(await r.text());
    const hasDisplay = list.some(w => w.type === 'Display');
    view.style.display = hasDisplay ? 'block' : 'none';
    if (window.__acSetVideoTools) window.__acSetVideoTools(hasDisplay);
    apply(list);
    document.getElementById('stat').textContent = '已连接';
  } catch (e) {
    document.getElementById('stat').textContent = '断开';
  }
}

// ---------------------------------------------------------------- 实时通道
// 优先用 WebSocket 推送控件状态；连不上就退回 1 秒轮询。
let wsOk = false;
function applyList(list) {
  const hasDisplay = list.some(w => w.type === 'Display');
  view.style.display = hasDisplay ? 'block' : 'none';
  if (window.__acSetVideoTools) window.__acSetVideoTools(hasDisplay);
  apply(list);
  document.getElementById('stat').textContent = '已连接';
}

function connectWs() {
  let ws;
  try {
    const proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
    ws = new WebSocket(proto + location.host + '/ws');
  } catch (e) {
    return;
  }
  ws.onopen = () => { wsOk = true; };
  ws.onmessage = ev => {
    try { applyList(JSON.parse(ev.data)); } catch (e) {}
  };
  ws.onclose = () => {
    wsOk = false;
    setTimeout(connectWs, 3000);
  };
  ws.onerror = () => { try { ws.close(); } catch (e) {} };
}

refresh();
connectWs();
setInterval(() => { if (!wsOk) refresh(); }, 1000);

// ------------------------------------------------------------------ 视频工具
// 这几个按钮只跟着视频控件走：截图/录制都在浏览器本地完成，和 demo 无关。
(function () {
  const img = document.getElementById('view');
  const bar = document.getElementById('vtools');
  const bShot = document.getElementById('vshot');
  const bRec = document.getElementById('vrec');
  const bPause = document.getElementById('vpause');
  const bSave = document.getElementById('vsave');
  const tip = document.getElementById('vtip');
  if (!img || !bar) return;

  const cv = document.createElement('canvas');
  const ctx = cv.getContext('2d');
  let rec = null, chunks = [], timer = 0, paused = false, recMime = '';

  function frame() {
    const w = img.naturalWidth || img.width;
    const h = img.naturalHeight || img.height;
    if (!w || !h) return false;
    if (cv.width !== w || cv.height !== h) { cv.width = w; cv.height = h; }
    try { ctx.drawImage(img, 0, 0, w, h); } catch (e) { return false; }
    return true;
  }

  function stamp() {
    const d = new Date();
    const p = n => String(n).padStart(2, '0');
    return d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + '_' +
           p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds());
  }

  function download(blob, name) {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = name;
    document.body.appendChild(a);
    a.click();
    setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
  }

  function setBusy(recording) {
    bRec.disabled = recording;
    bSave.disabled = !recording;
    bPause.disabled = !recording;
    if (!recording) { bPause.textContent = '暂停'; paused = false; }
  }

  bShot.addEventListener('click', () => {
    if (!frame()) { tip.textContent = '还没有画面'; return; }
    cv.toBlob(b => {
      if (b) { download(b, 'ansylab_' + stamp() + '.png'); tip.textContent = '截图已下载'; }
    }, 'image/png');
  });

  bRec.addEventListener('click', () => {
    if (typeof MediaRecorder === 'undefined') {
      tip.textContent = '这个浏览器不支持录制';
      return;
    }
    if (!frame()) { tip.textContent = '还没有画面'; return; }
    try {
      const stream = cv.captureStream(10);
      // 优先 MP4（Chrome 126+ 支持 H.264 in MP4），不支持再退回 WebM
      const CANDIDATES = [
        'video/mp4;codecs=avc1.42E01E',
        'video/mp4;codecs=avc1',
        'video/mp4',
        'video/webm;codecs=vp9',
        'video/webm'
      ];
      const mime = CANDIDATES.find(t => MediaRecorder.isTypeSupported(t)) || '';
      recMime = mime;
      rec = new MediaRecorder(stream, mime
        ? { mimeType: mime, videoBitsPerSecond: 4000000 }
        : { videoBitsPerSecond: 4000000 });
    } catch (e) {
      tip.textContent = '录制启动失败：' + e.message;
      return;
    }
    chunks = [];
    rec.ondataavailable = e => { if (e.data && e.data.size) chunks.push(e.data); };
    rec.onstop = () => {
      clearInterval(timer);
      timer = 0;
      const isMp4 = recMime.indexOf('mp4') >= 0;
      const blob = new Blob(chunks, { type: isMp4 ? 'video/mp4' : 'video/webm' });
      chunks = [];
      rec = null;
      setBusy(false);
      if (blob.size > 0) {
        download(blob, 'ansylab_' + stamp() + (isMp4 ? '.mp4' : '.webm'));
        tip.textContent = '录像已下载（' + Math.round(blob.size / 1024) + ' KB）';
      } else {
        tip.textContent = '没有录到内容';
      }
    };
    rec.start(200);
    timer = setInterval(frame, 100);   // 10fps 把 MJPEG 画面画进 canvas
    setBusy(true);
    tip.textContent = '录制中…';
  });

  bPause.addEventListener('click', () => {
    if (!rec) return;
    if (rec.state === 'paused') {
      rec.resume();
      paused = false;
      bPause.textContent = '暂停';
      tip.textContent = '录制中…';
    } else {
      rec.pause();
      paused = true;
      bPause.textContent = '继续';
      tip.textContent = '已暂停';
    }
  });

  bSave.addEventListener('click', () => {
    if (!rec) return;
    tip.textContent = '正在保存…';
    rec.stop();
  });

  // 有视频控件才显示这排按钮
  window.__acSetVideoTools = show => {
    bar.style.display = show ? 'flex' : 'none';
  };
})();

console.log('AnsyLab page v4');
</script></body></html>
"""