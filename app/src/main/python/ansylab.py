"""AnsyLab 的 Python 运行时（第二阶段：零拷贝）。

和第一阶段的区别
----------------
第一阶段：Kotlin 每帧 `ByteArray(1.2MB)` → Chaquopy 转成 Python bytes（又一次拷贝）
          → numpy 视图 → cv2 处理 → `tobytes()`（再一次拷贝）→ Kotlin 再拷进 Bitmap。
          一帧下来 5~6 MB 垃圾，30fps 就是 180 MB/s。

第二阶段：相机帧由 native 三缓冲池承载（一次 memcpy，无法避免，因为 CameraX 的
          buffer 必须尽快还回去）；Python 通过 JNI 拿到 native 地址，用 ctypes
          在地址上建 numpy 视图，**像素零拷贝**；显示则直接画进 native 显示缓冲
          （cv2 的 dst 参数），处理完只发一次轻量通知。

          注意这里不用 ctypes.CDLL 打开 .so：AGP 默认 extractNativeLibs=false，
          库没有解压到磁盘，dlopen 会失败，所以指针一律通过 JNI 取。

用法
----
    import ansylab
    cam = ansylab.Camera(640, 480)
    view = ansylab.display()          # 视频显示控件（固定区）

    # 零分配写法：写进自己复用的缓冲
    buf = None
    while True:
        frame = cam.read_into(buf)
        if buf is None:
            buf = frame
        view.show(frame)

    # 或者拿一个受管的视图（离开 with 后内存会还给帧池）
    with cam.frame() as f:
        print(f.rgba.shape, f.width, f.height)
"""

import ctypes
import json
import collections
import math
import os
import time

import cv2
import numpy as np
from java import jclass

_Bridge = jclass("com.ansylab.vision.PyBridge")
_Native = jclass("com.ansylab.vision.NativeCamera")

__all__ = ["Camera", "Frame", "display", "Display", "slider", "toggle", "button",
           "label", "clear_widgets", "YOLO", "log", "stats",
           "Imu", "imu", "imu_available", "Serial", "serial_list", "running", "sleep", "web",
           "text_input", "text_output", "joystick", "double_joystick", "combo_box", "ComboBox",
"progress_bar", "ProgressBar", "image", "Image", "checkbox", "CheckBox",
"radio_group", "RadioGroup", "text_field", "TextField",
"divider", "Divider", "header", "Header", "plot", "Plot",
    "data_root", "probe_model"]

_display_cache = None  # (w, h, ndarray)


def _view(addr, width, height, row_stride=None):
    """在 native 地址上建一个 (h, w, 4) 的 uint8 视图，不拷贝。

    row_stride 是每行的**字节数**。真机上它常常大于 width*4（行尾 padding），
    这时必须用 strides 建视图，否则每行会读偏，画面呈斜向撕裂。
    """
    if not addr:
        raise RuntimeError("native 返回了空指针")
    if row_stride is None or row_stride < width * 4:
        row_stride = width * 4

    total = row_stride * height
    buffer = (ctypes.c_uint8 * total).from_address(int(addr))
    if row_stride == width * 4:
        return np.frombuffer(buffer, dtype=np.uint8).reshape((height, width, 4))

    return np.ndarray(
        shape=(height, width, 4),
        dtype=np.uint8,
        buffer=buffer,
        strides=(row_stride, 4, 1),
    )


# 当前脚本的会话代号：PyBridge 每开一次新脚本就 +1。
# 旧脚本线程发现代号变了会自己退出，避免「返回后立刻重进」时两个脚本同时跑。
_session_gen = 0


def _bind_session():
    """由 runner 在脚本开始执行前调用。"""
    global _session_gen, _display_widget
    _session_gen = int(_Bridge.sessionGen())
    _display_widget = None
    # 新会话：旧控件已经随页面清空了，注册表也要重置，
    # 否则网页会列出上一轮已经不存在的控件
    try:
        del _widget_registry[:]
        _image_store.clear()
        _image_seq.clear()
        del _RESOURCES[:]
    except Exception:
        pass

# 网页推流服务（ansylab.web() 创建，全局一个）
_web = None



def _check_stop():
    if _Bridge.shouldStop() or int(_Bridge.sessionGen()) != _session_gen:
        raise KeyboardInterrupt("stop requested")


def running():
    """脚本是否还属于当前会话。

    脚本循环建议写成 `while ansylab.running():`，切页/退出时能自己停下来，
    否则旧线程会一直占着相机、串口等资源，导致下一个程序打不开同一设备。
    """
    try:
        return not _Bridge.shouldStop() and int(_Bridge.sessionGen()) == _session_gen
    except Exception:
        return False


def sleep(seconds):
    """可被中断的 sleep（切页后尽快退出）。"""
    end = time.monotonic() + float(seconds)
    while time.monotonic() < end:
        _check_stop()
        time.sleep(0.01)


# 旋转用的复用缓冲（同一时刻只有一帧在被处理，所以单块够用）
_rotate_buf = None


def _rotate(rgba, degrees):
    """把 (h, w, 4) 的视图按度数转正，写进复用缓冲，零分配。"""
    global _rotate_buf
    h, w = rgba.shape[:2]
    if degrees == 90:
        code = cv2.ROTATE_90_CLOCKWISE
        shape = (w, h, 4)
    elif degrees == 180:
        code = cv2.ROTATE_180
        shape = (h, w, 4)
    elif degrees == 270:
        code = cv2.ROTATE_90_COUNTERCLOCKWISE
        shape = (w, h, 4)
    else:
        return rgba

    if _rotate_buf is None or _rotate_buf.shape != shape:
        _rotate_buf = np.empty(shape, dtype=np.uint8)
    cv2.rotate(rgba, code, dst=_rotate_buf)
    return _rotate_buf


# --------------------------------------------------------------------------- #
# 相机
# --------------------------------------------------------------------------- #
class Frame:
    """受管的一帧：离开 with 时把 slot 还给帧池。

    注意：`rgba` 是 native 内存的视图，close() 之后内容可能被下一帧覆盖，
    需要长期保存请自己 copy()。

    方向：`width/height` 已经是**转正之后**的尺寸（竖屏时宽高互换），
    `bgr()` 会自动按 CameraX 给的 rotationDegrees 旋转。
    """

    __slots__ = ("_slot", "_rgba", "width", "height", "rotation")

    def __init__(self, slot, width, height, rotation=0, row_stride=None):
        self._slot = slot
        self.rotation = int(rotation) % 360
        self._rgba = _view(_Native.framePtr(slot), width, height, row_stride)
        if self.rotation in (90, 270):
            self.width = height
            self.height = width
        else:
            self.width = width
            self.height = height

    @property
    def rgba(self):
        return self._rgba

    def bgr(self, dst=None):
        """转成 OpenCV 习惯的 BGR；传 dst 则零分配写入（dst 尺寸按转正后算）。"""
        src = self._rgba
        if self.rotation:
            src = _rotate(self._rgba, self.rotation)
        if dst is None:
            return cv2.cvtColor(src, cv2.COLOR_RGBA2BGR)
        cv2.cvtColor(src, cv2.COLOR_RGBA2BGR, dst=dst)
        return dst

    def close(self):
        if self._slot >= 0:
            _Native.releaseFrame(self._slot)
            self._slot = -1

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        self.close()
        return False


class Camera:
    """手机相机。帧数据直接从 native 帧池读，不再经过 Java 的 byte[]。

    - `Camera.open()`        用默认后置主摄
    - `Camera.open("2")`     用指定 camera id（先用 `Camera.list()` 看有哪些）
    - `Camera.list()`        列出本机所有相机
    - `camera.torch = True`  开闪光灯（需要相机已打开）
    """

    def __init__(self, width=640, height=480, fps=30, camera_id=None,
                 focus="continuous"):
        _track(self)
        self.width = int(width)
        self.height = int(height)
        self.fps = int(fps)
        # 对焦模式：auto / continuous / infinity / none（open() 时应用）
        self.focus = str(focus or "continuous")
        self.camera_id = None if camera_id is None else str(camera_id)
        self._opened = False
        self._last_seq = -1
        self.open_timeout_ms = 8000

    # ---------------------------------------------------------------- 枚举
    @staticmethod
    def list():
        """返回本机相机列表（list[dict]），字段见 CameraCatalog.listJson。"""
        raw = str(_Bridge.cameraList())
        try:
            return json.loads(raw)
        except Exception:
            return []

    @staticmethod
    def describe():
        """把相机列表拼成好读的多行文本（demo 里直接显示）。"""
        rows = Camera.list()
        if not rows:
            return "没有找到相机"
        lines = []
        for c in rows:
            lines.append("#%s %-6s 焦距%.1fmm 旋转%s° %s %s" % (
                c.get("id"), c.get("facing"), c.get("focal", 0),
                c.get("orientation", 0),
                "有闪光灯" if c.get("flash") else "无闪光灯",
                c.get("maxSize", ""),
            ))
        return "\n".join(lines)

    # ---------------------------------------------------------------- 打开
    def open(self, camera_id=None, timeout_ms=None):
        """打开相机；camera_id 给空则用当前/默认相机。返回是否拿到首帧。"""
        if camera_id is not None:
            self.camera_id = str(camera_id)
        try:
            _Bridge.setCameraFocus(self.focus)
        except Exception:
            pass
        if self.camera_id:
            _Bridge.startCameraById(self.width, self.height, self.fps, self.camera_id)
        else:
            _Bridge.startCamera(self.width, self.height, self.fps)
        self._last_seq = -1
        ok = self._wait_first_frame(timeout_ms)
        # 只有真的拿到首帧才算打开成功；否则保持未打开，
        # 下一次 frame() 会自动重试 open()，而不是一直读超时
        self._opened = bool(ok)
        # 实际生效的 id（有可能指定的相机被系统隐藏，退回了主摄）
        actual = str(_Bridge.cameraCurrentId())
        log("[ansylab] rotation=%d° frame=%dx%d rowStride=%d camera=%s"
            % (_Native.rotationDegrees(), _Native.frameWidth(),
               _Native.frameHeight(), _Native.frameRowStride(),
               actual or "默认"))
        return ok

    def _wait_first_frame(self, timeout_ms=None):
        """相机是异步初始化的（模拟器上要 2 秒左右），这里等到第一帧进池。

        不等的话，脚本一开头 read 就会超时返回 None，看起来像"没有权限"。
        循环里必须检查停止标志：否则快速切页时旧脚本会在这里空转最多 8 秒，
        新脚本已经起来，两个脚本同时抢帧。
        """
        total = self.open_timeout_ms if timeout_ms is None else int(timeout_ms)
        base = int(_Native.frameSeq())
        deadline = time.monotonic() + total / 1000.0
        while time.monotonic() < deadline:
            _check_stop()
            if int(_Native.frameSeq()) > base or (base == 0 and int(_Native.frameSeq()) > 0):
                return True
            time.sleep(0.01)
        log("[ansylab] 等待首帧超时（%d ms），可能没有相机权限" % total)
        return False

    # ---------------------------------------------------------------- 闪光灯
    @property
    def torch(self):
        """闪光灯是否可用（只读）。"""
        return bool(_Bridge.cameraTorchAvailable())

    @torch.setter
    def torch(self, on):
        err = _Bridge.cameraTorch(bool(on))
        if err:
            log("[ansylab] 闪光灯: " + str(err))

    def set_torch(self, on):
        """开/关闪光灯，返回空串表示成功。"""
        return str(_Bridge.cameraTorch(bool(on)))

    # ---------------------------------------------------------------- id
    @property
    def current_id(self):
        """当前实际在用的 camera id。"""
        return str(_Bridge.cameraCurrentId())

    def _wait(self, timeout_ms):
        """等到一帧比上次新的，返回 slot；超时返回 -1。"""
        deadline = time.monotonic() + timeout_ms / 1000.0
        while True:
            _check_stop()
            seq = _Native.frameSeq()
            if seq != self._last_seq:
                slot = _Native.acquireFrame()
                if slot >= 0:
                    self._last_seq = seq
                    return slot
            if time.monotonic() >= deadline:
                log("[ansylab] 读帧超时 seq=%d last=%d pinned=0x%x dropped=%d"
                    % (seq, self._last_seq, int(_Native.pinnedMask()),
                       int(_Native.droppedFrames())))
                return -1
            time.sleep(0.002)

    def frame(self, timeout_ms=2000):
        """返回 Frame（上下文管理器），超时返回 None。"""
        if not self._opened:
            self.open()
        _check_stop()

        slot = self._wait(timeout_ms)
        if slot < 0:
            return None

        width = int(_Native.frameWidth())
        height = int(_Native.frameHeight())
        # 退出程序页时 native 会释放相机帧；若这里拿到的是空指针，说明那一帧
        # 已经被回收，绝对不能拿去建 numpy 视图（会读到垃圾内存）。
        if width <= 0 or height <= 0 or not _Native.framePtr(slot):
            _Native.releaseFrame(slot)
            return None
        return Frame(slot, width, height, _Native.rotationDegrees(),
                     _Native.frameRowStride())

    def read(self, timeout_ms=2000):
        """返回一帧 BGR ndarray；每次都会分配新数组（兼容老脚本）。"""
        frame = self.frame(timeout_ms)
        if frame is None:
            return None
        try:
            return frame.bgr()
        finally:
            frame.close()

    def read_into(self, dst=None, timeout_ms=2000):
        """把下一帧 BGR 写进 dst（零分配）；dst 为 None 或尺寸不符时新建。"""
        frame = self.frame(timeout_ms)
        if frame is None:
            return None
        try:
            if (dst is None or dst.shape[0] != frame.height
                    or dst.shape[1] != frame.width or dst.shape[2] != 3):
                dst = np.empty((frame.height, frame.width, 3), dtype=np.uint8)
            return frame.bgr(dst)
        finally:
            frame.close()

    def release(self):
        _Bridge.stopCamera()
        self._opened = False

    def __enter__(self):
        return self.open()

    def __exit__(self, exc_type, exc, tb):
        self.release()


# --------------------------------------------------------------------------- #
# 显示
# --------------------------------------------------------------------------- #
def _display_view(width, height):
    """native 显示缓冲的 (h, w, 4) 视图。

    不能只按尺寸缓存：native 的缓冲可能被释放重建（比如退出程序页时），
    同一个尺寸会换到新地址，旧视图就成了野指针。所以每次都比对地址。
    """
    global _display_cache
    addr = _Native.displayResize(width, height)
    if not addr:
        raise RuntimeError("显示缓冲分配失败")
    if _display_cache is not None:
        w, h, cached_addr, arr = _display_cache
        if w == width and h == height and cached_addr == addr:
            return arr
    arr = _view(addr, width, height)
    _display_cache = (width, height, addr, arr)
    return arr


def _present_frame(img):
    """把一帧画到 App 的显示控件上（零分配）。由 Display.show() 调用。"""
    if img is None:
        return

    _check_stop()
    arr = np.asarray(img)
    if arr.ndim not in (2, 3):
        raise ValueError("show() 只接受 HxW、HxWx3、HxWx4 的图像")

    height, width = arr.shape[:2]

    # 优先走硬件显示：直接写进 AHardwareBuffer，Bitmap 只是包了一层，
    # 渲染时不再有 copyPixelsFromBuffer 的整块拷贝。
    if _Bridge.prepareDisplay(width, height):
        addr = _Native.beginDraw()
        if addr:
            _convert_into(arr, _view(addr, width, height))
            _Bridge.present()      # 解锁 + 交换前后台 + 通知 UI
            _publish_web(arr)
            return

    # 退路：软件显示缓冲，UI 侧还要拷一次 Bitmap
    _publish_web(arr)
    dst = _display_view(width, height)
    _convert_into(arr, dst)
    _Bridge.present()


def _convert_into(arr, dst):
    """把 BGR/灰度/BGRA 统一写成 RGBA，画进 dst（零分配）。"""
    if arr.ndim == 2:
        cv2.cvtColor(arr, cv2.COLOR_GRAY2RGBA, dst=dst)
    elif arr.shape[2] == 3:
        cv2.cvtColor(arr, cv2.COLOR_BGR2RGBA, dst=dst)
    elif arr.shape[2] == 4 and arr is not dst:
        cv2.cvtColor(arr, cv2.COLOR_BGRA2RGBA, dst=dst)


def log(msg):
    _Bridge.log(str(msg))


def stats():
    """(相机帧序号, 被丢弃帧数, 显示序号)，用来观察帧池压力。"""
    return (
        int(_Native.frameSeq()),
        int(_Native.droppedFrames()),
        int(_Native.displaySeq()),
    )


# --------------------------------------------------------------------------- #
# 控件：在程序页的「控件」区创建可交互控件
#
# 用法：
#     conf = ansylab.slider("置信度", 0.1, 0.9, 0.05, 0.25)
#     reset = ansylab.button("重置统计")
#     show = ansylab.toggle("显示类别名", True)
#     fps = ansylab.label("fps=0")
#
#     while True:
#         frame = cam.read()
#         dets = model.detect(frame, conf=conf.value)
#         if reset.clicked:          # 读一次就清零
#             ...
#         fps.set("fps=%.1f" % f)
#
# 线程说明：脚本跑在工作线程，界面在主线程；控件值都是实时读 JVM 里的字段，
# 每帧读一次的开销只有一次 JNI 调用，可以放心放在循环里。
# --------------------------------------------------------------------------- #
# 所有控件都登记一份，网页远程控制需要遍历它们
# ---------------------------------------------------------------- 资源注册表
#
# 脚本被强制结束时（切页、停止、崩溃），Python 侧持有的 YOLO/串口/相机/IMU
# 必须逐个 close，否则下一轮会拿到泄漏的 ORT/QNN 会话或打不开同一串口。
# 用弱引用登记，对象自己 close 不会影响注册表。

import weakref as _weakref

_RESOURCES = []


def _track(obj):
    """登记一个需要 close 的对象（在各自 __init__ 里调用）。"""
    try:
        _RESOURCES.append(_weakref.ref(obj))
    except Exception:
        pass


def _close_all():
    """关闭所有仍存活的资源。由 runner 在脚本结束时调用。"""
    alive = []
    for ref in list(_RESOURCES):
        obj = ref()
        if obj is None:
            continue
        closed = False
        for name in ("close", "release"):
            fn = getattr(obj, name, None)
            if callable(fn):
                try:
                    fn()
                    closed = True
                except BaseException:
                    pass
                break
        if not closed:
            alive.append(ref)
    _RESOURCES[:] = alive


_widget_registry = []

# image 控件的原始字节：网页 /api/image 直接从这里取
_image_store = {}
_image_seq = {}


class _Widget:
    """所有控件的基类：统一提供 label / visible / enabled / remove。"""

    __slots__ = ("id",)

    def __init__(self, widget_id):
        self.id = widget_id
        _widget_registry.append(self)

    # ---- 通用能力（所有控件都有）----
    @property
    def label(self):
        return ""

    @label.setter
    def label(self, text):
        _Bridge.widgetSetLabel(self.id, str(text))

    @property
    def visible(self):
        return bool(_Bridge.widgetIsVisible(self.id))

    @visible.setter
    def visible(self, v):
        _Bridge.widgetSetVisible(self.id, bool(v))

    def show(self):
        self.visible = True
        return self

    def hide(self):
        self.visible = False
        return self

    @property
    def enabled(self):
        return bool(_Bridge.widgetIsEnabled(self.id))

    @enabled.setter
    def enabled(self, v):
        _Bridge.widgetSetEnabled(self.id, bool(v))

    def enable(self):
        self.enabled = True
        return self

    def disable(self):
        self.enabled = False
        return self

    def remove(self):
        """从界面移除该控件。"""
        _Bridge.widgetRemove(self.id)

    def __repr__(self):
        return "<%s %s>" % (self.__class__.__name__, self.id)


class Slider(_Widget):
    """横向拖动条，值是 float。"""

    @property
    def value(self):
        return float(_Bridge.widgetValue(self.id))

    @value.setter
    def value(self, v):
        """脚本（或网页）也能改滑块位置。"""
        _Bridge.widgetSetValue(self.id, float(v))

    def __repr__(self):
        return "<Slider %.3f>" % self.value


class Toggle(_Widget):
    """开关，值是 bool。"""

    @property
    def value(self):
        return bool(_Bridge.widgetBool(self.id))

    @value.setter
    def value(self, v):
        """脚本也能改开关状态（失败回退时用）。"""
        _Bridge.widgetSetFlag(self.id, bool(v))

    def __repr__(self):
        return "<Toggle %s>" % self.value


class Button(_Widget):
    """按钮。`clicked` 是「读一次就清零」的语义，适合放在循环里轮询。"""

    @property
    def clicked(self):
        return bool(_Bridge.widgetClicked(self.id))

    def __repr__(self):
        return "<Button>"


class Label(_Widget):
    """一行只读文字，用来回显状态（比如 FPS、检测数量）。"""

    def set(self, text):
        _Bridge.widgetSetText(self.id, str(text))

    @property
    def text(self):
        return str(_Bridge.widgetText(self.id))


class Display(_Widget):
    """视频显示控件（固定区）。

    它是页面上半部分那块「画面」，用 `view.show(img)` 输出。
    必须由脚本调用 `ansylab.display()` 创建，不再有隐式创建。
    """

    def show(self, img):
        _present_frame(img)


class TextInput(_Widget):
    """多行文本输入框（自带「确定」按钮，超过最大高度可滚动）。

    - `.value`  当前输入内容（边打字边同步）
    - `.submitted` 是否点了「确定」，读一次就清零
    """

    @property
    def value(self):
        return str(_Bridge.widgetText(self.id))

    @property
    def submitted(self):
        return bool(_Bridge.widgetSubmit(self.id))

    def set_text(self, text):
        """改写输入框内容（比如提交后清空）。"""
        _Bridge.widgetSetText(self.id, str(text))

    def __repr__(self):
        return "<TextInput %r>" % self.value


class TextOutput(_Widget):
    """多行文本输出框（只读，超过最大高度可滚动）。"""

    def set(self, text):
        _Bridge.widgetSetText(self.id, str(text))

    def append(self, text):
        """追加一段文本（自动换行）。"""
        cur = str(_Bridge.widgetText(self.id))
        _Bridge.widgetSetText(self.id, (cur + "\n" + str(text)) if cur else str(text))

    @property
    def text(self):
        return str(_Bridge.widgetText(self.id))

    def __repr__(self):
        return "<TextOutput %d chars>" % len(self.text)


class Joystick(_Widget):
    """单摇杆：`.x` / `.y` 归一化到 [-1, 1]，y 向上为正，松手回 0。"""

    @property
    def x(self):
        return float(_Bridge.joystickX(self.id, False))

    @property
    def y(self):
        return float(_Bridge.joystickY(self.id, False))

    @property
    def value(self):
        return (self.x, self.y)

    def set(self, x, y):
        """脚本（或网页）也能设置摇杆位置。"""
        _Bridge.widgetSetJoystick(self.id, float(x), float(y), False)

    def __repr__(self):
        return "<Joystick %.2f,%.2f>" % (self.x, self.y)


class DoubleJoystick(_Widget):
    """双摇杆：`.left` / `.right` 各返回 (x, y)。"""

    @property
    def left(self):
        return (float(_Bridge.joystickX(self.id, False)),
                float(_Bridge.joystickY(self.id, False)))

    @property
    def right(self):
        return (float(_Bridge.joystickX(self.id, True)),
                float(_Bridge.joystickY(self.id, True)))

    def set_left(self, x, y):
        _Bridge.widgetSetJoystick(self.id, float(x), float(y), False)

    def set_right(self, x, y):
        _Bridge.widgetSetJoystick(self.id, float(x), float(y), True)

    def __repr__(self):
        return "<DoubleJoystick %s %s>" % (self.left, self.right)


class ComboBox(_Widget):
    """下拉框：点整行弹出选项列表。

    - `.index`   当前选中下标
    - `.value`   当前选中文本
    - `.items`   选项列表
    - `.set_items([...])` 改写选项（比如重新扫描相机）

    控件是异步建到界面的，刚创建的一瞬间去读会拿到空值；所以本地留一份副本兜底。
    """

    __slots__ = ("_items", "_index")

    def __init__(self, wid, items=None, index=0):
        super(ComboBox, self).__init__(wid)
        self._items = list(items or [])
        self._index = max(0, min(int(index), len(self._items) - 1)) if self._items else 0

    @property
    def index(self):
        if _Bridge.comboItems(self.id):
            return int(_Bridge.comboIndex(self.id))
        return self._index

    @property
    def value(self):
        text = str(_Bridge.comboText(self.id))
        if text:
            return text
        return self._items[self._index] if self._items else ""

    @property
    def items(self):
        raw = str(_Bridge.comboItems(self.id))
        return raw.split("\n") if raw else list(self._items)

    @index.setter
    def index(self, i):
        """脚本 / 网页都能改选中项。"""
        self._index = max(0, int(i))
        _Bridge.widgetSetIndex(self.id, int(i))

    def set_items(self, items, index=0):
        self._items = [str(x) for x in items]
        self._index = max(0, min(int(index), len(self._items) - 1)) if self._items else 0
        _Bridge.comboSetItems(self.id, "\n".join(self._items), self._index)
        return self

    def __repr__(self):
        return "<ComboBox %r>" % self.value


class ProgressBar(_Widget):
    """进度条。`indeterminate=True` 时显示转圈（不知道具体进度）。"""

    @property
    def value(self):
        return float(_Bridge.progressValue(self.id))

    @value.setter
    def value(self, v):
        _Bridge.progressSet(self.id, float(v), False, False)

    def set(self, value, indeterminate=None):
        """更新进度；indeterminate 给 None 表示不改模式。"""
        _Bridge.progressSet(
            self.id, float(value), bool(indeterminate), indeterminate is not None)
        return self

    def __repr__(self):
        return "<ProgressBar %.0f>" % self.value


class Image(_Widget):
    """静态图片显示（区别于 display 的连续视频）。

    `img.show(frame)` 支持 numpy 数组（BGR/gray）或 PNG 字节。
    """

    def show(self, img):
        if img is None:
            return
        if isinstance(img, (bytes, bytearray)):
            data = bytes(img)
        else:
            arr = np.asarray(img)
            ok, buf = cv2.imencode(".png", arr)
            if not ok:
                raise RuntimeError("图片编码失败")
            data = buf.tobytes()
        _image_store[self.id] = data
        _image_seq[self.id] = _image_seq.get(self.id, 0) + 1
        _Bridge.imageSet(self.id, data)
        return self


class Plot(_Widget):
    """折线图：最多三条曲线，自动缩放。

    用法：
        p = ansylab.plot("加速度", 200, channels=3)
        while ...:
            p.push(ax, ay, az)          # 2 个参数就画两条
    """

    __slots__ = ("_n", "_ch", "_plot", "_y", "_win", "_times")

    def __init__(self, wid, points=200, channels=1, y_min=None, y_max=None, window=0.0):
        super(Plot, self).__init__(wid)
        self._n = int(points)
        self._ch = int(channels)
        self._y = (y_min, y_max)
        self._win = float(window or 0.0)
        # 本地留一份，网页取数据时直接从这儿读，不用回 Kotlin 拷
        self._plot = collections.deque(maxlen=max(2, self._n))
        # 每个点的到达时刻（秒），时间窗口模式要用
        self._times = collections.deque(maxlen=max(2, self._n))

    def push(self, *values):
        """推一个点：1~5 个数。"""
        vals = [float(v) for v in values[:5]]
        if not vals:
            return self
        self._plot.append(vals)
        self._times.append(time.monotonic())
        _Bridge.plotPush(self.id, vals)
        return self

    def export_csv(self, path=None):
        """把当前曲线导出成 CSV（第一列是序号，后面每列一条线）。"""
        if not self._plot:
            raise RuntimeError("还没有数据")
        if not path:
            try:
                root = data_root()
            except Exception:
                root = ""
            d = os.path.join(root, "captures")
            try:
                os.makedirs(d, exist_ok=True)
            except Exception:
                pass
            stamp = time.strftime("%Y%m%d_%H%M%S") + "_%03d" % int(time.time() * 1000 % 1000)
            path = os.path.join(d, "plot_%s.csv" % stamp)
        rows = list(self._plot)
        ch = max(len(r) for r in rows)
        with open(path, "w", encoding="utf-8") as fp:
            fp.write(",".join(["index"] + ["ch%d" % (i + 1) for i in range(ch)]) + "\n")
            for i, r in enumerate(rows):
                vals = ["%.6f" % v for v in r]
                vals += [""] * (ch - len(vals))
                fp.write(",".join([str(i)] + vals) + "\n")
        return path

    def window_points(self):
        """按时间窗口裁剪后的点（window<=0 时返回全部）。"""
        rows = list(self._plot)
        if self._win <= 0 or not rows:
            return rows
        cutoff = self._times[-1] - self._win
        out = []
        for t, r in zip(self._times, rows):
            if t >= cutoff:
                out.append(r)
        return out or rows[-1:]

    def clear(self):
        """清空曲线（保留下一个点从左边重新开始画）。"""
        self._plot.clear()
        self._times.clear()
        _Bridge.plotClear(self.id)
        return self

    def __repr__(self):
        return "<Plot %s>" % (self.id,)


class CheckBox(_Widget):
    """勾选框，值是 bool。"""

    @property
    def value(self):
        return bool(_Bridge.widgetBool(self.id))

    @value.setter
    def value(self, v):
        _Bridge.widgetSetFlag(self.id, bool(v))

    def __repr__(self):
        return "<CheckBox %s>" % self.value


class RadioGroup(_Widget):
    """单选组：所有选项直接铺在界面上。"""

    @property
    def index(self):
        return int(_Bridge.widgetIndex(self.id))

    @property
    def value(self):
        return str(_Bridge.comboText(self.id))

    @index.setter
    def index(self, i):
        """脚本 / 网页都能改选中项。"""
        _Bridge.widgetSetIndex(self.id, int(i))

    @property
    def items(self):
        raw = str(_Bridge.widgetItemsOf(self.id))
        return raw.split("\n") if raw else []

    def set_items(self, items, index=0):
        _Bridge.widgetSetItems(self.id, "\n".join(str(x) for x in items), int(index))
        return self

    def __repr__(self):
        return "<RadioGroup %r>" % self.value


class TextField(_Widget):
    """单行输入框（回车或点确定提交）。"""

    @property
    def value(self):
        return str(_Bridge.widgetText(self.id))

    @property
    def submitted(self):
        return bool(_Bridge.widgetSubmit(self.id))

    def set_text(self, text):
        _Bridge.widgetSetText(self.id, str(text))

    def __repr__(self):
        return "<TextField %r>" % self.value


class Divider(_Widget):
    """一条分隔线。"""


class Header(_Widget):
    """分组标题（比 label 醒目）。"""

    @property
    def text(self):
        return str(_Bridge.widgetText(self.id))

    @text.setter
    def text(self, v):
        _Bridge.widgetSetLabel(self.id, str(v))

    def set(self, text):
        self.text = text
        return self

    def __repr__(self):
        return "<Header %r>" % self.text


_display_widget = None


def display(region="main"):
    """创建视频显示控件并返回；重复调用返回同一个。

    显示画面一律走 `view.show(img)`，不再提供 imshow。
    """
    global _display_widget
    wid = _Bridge.addDisplay(str(region))
    if _display_widget is None:
        _display_widget = str(wid)
    return Display(_display_widget)


def _web_password():
    try:
        return str(_Bridge.webPassword()) or "123456"
    except Exception:
        return "123456"


def _publish_web(arr):
    """给网页推流用；没人看时直接返回，零开销。"""
    w = _web
    if w is None:
        return
    try:
        w.publish(arr)
    except Exception:
        pass


def _autostart_web():
    """脚本启动时按设置自动开网页服务（不用脚本自己调 web()）。"""
    try:
        if not _Bridge.webEnabled():
            return None
    except Exception:
        return None
    return web()


def web(port=8080, token="", fps=15.0, quality=70):
    """启动网页服务：浏览器看画面 + 远程操作控件。

    返回的对象有：
        .url     访问地址（含 ?t=token）
        .ip      本机在局域网里的地址
        .stop()  关闭
        .publish(frame)  手动推一帧（一般不用，display.show() 会自动推）

    视频流地址是 `<url>stream`，`view.show()` 每帧会自动送过去。
    """
    global _web
    if _web is None:
        import ansylab_web
        _web = ansylab_web.WebServer(
            registry=_widget_registry, bridge=_Bridge,
            port=int(port), password=_web_password(),
            fps=float(fps), quality=int(quality))
        _web.start()
    # 脚本显式启动：不让设置里的开关把它停掉
    _web._explicit = True
    return _web


def _shutdown_web():
    """脚本结束时关掉网页服务（由 runner 调用）。"""
    global _web
    w = _web
    _web = None
    if w is not None:
        try:
            w.stop()
        except Exception:
            pass


def clear_widgets():
    """清空控件区（程序页每次进入时会自动清一次）。"""
    global _display_widget
    _display_widget = None
    _Bridge.clearWidgets()


def slider(label, min=0.0, max=1.0, step=0.01, value=None, region="panel"):
    """创建拖动条。value 默认取 min 与 max 的中点。

    region="main" 放在固定区，"panel"（默认）放在可折叠区。
    """
    if value is None:
        value = (min + max) / 2.0
    return Slider(_Bridge.addSlider(str(label), float(min), float(max),
                                    float(step), float(value), str(region)))


def button(label, region="panel"):
    """创建按钮。"""
    return Button(_Bridge.addButton(str(label), str(region)))


def toggle(label, value=False, region="panel"):
    """创建开关。"""
    return Toggle(_Bridge.addToggle(str(label), bool(value), str(region)))


def label(text="", region="panel"):
    """创建一行文字。"""
    return Label(_Bridge.addLabel(str(text), str(region)))


def text_input(label="", value="", max_height=120, region="panel"):
    """创建多行文本输入框（带「确定」按钮）。

    max_height 单位是 dp，内容超过它之后输入框内部可上下滚动。
    """
    return TextInput(_Bridge.addTextInput(
        str(label), str(value), int(max_height), str(region)))


def text_output(text="", max_height=160, region="panel"):
    """创建多行文本输出框；超过 max_height（dp）之后可上下滚动。"""
    return TextOutput(_Bridge.addTextOutput(
        str(text), int(max_height), str(region)))


def joystick(label="", region="panel"):
    """创建单摇杆（占一整行）。x 向右为正，y 向上为正，范围 [-1, 1]。"""
    return Joystick(_Bridge.addJoystick(str(label), str(region)))


def double_joystick(label="", region="panel"):
    """创建双摇杆（两个摇杆并排占一行）。"""
    return DoubleJoystick(_Bridge.addDoubleJoystick(str(label), str(region)))


def combo_box(label="", items=None, index=0, region="panel"):
    """创建下拉框。items 是字符串列表（元素里不要带换行）。"""
    items = list(items or [])
    wid = _Bridge.addCombo(
        str(label), "\n".join(str(x) for x in items), int(index), str(region))
    return ComboBox(wid, items, index)


# ---------------------------------------------------------------- 第二批控件
def progress_bar(label="", value=0, max=100, indeterminate=False, region="panel"):
    """进度条。indeterminate=True 显示转圈。"""
    return ProgressBar(_Bridge.addProgress(
        str(label), float(value), float(max), bool(indeterminate), str(region)))


def image(label="", height=180, region="main"):
    """静态图片显示。height 是 dp。"""
    return Image(_Bridge.addImage(str(label), int(height), str(region)))


def checkbox(label="", checked=False, region="panel"):
    """勾选框。"""
    return CheckBox(_Bridge.addCheckbox(str(label), bool(checked), str(region)))


def radio_group(label="", items=None, index=0, region="panel"):
    """单选组（选项全部铺开，不用点开）。"""
    items = list(items or [])
    wid = _Bridge.addRadio(
        str(label), "\n".join(str(x) for x in items), int(index), str(region))
    return RadioGroup(wid)


def text_field(label="", value="", region="panel"):
    """单行输入框（回车/确定提交）。"""
    return TextField(_Bridge.addTextField(str(label), str(value), str(region)))


def probe_model(path):
    """探测一个 ONNX 能否被 NPU 完整接管（测试用）。

    返回 dict：result = ok / strict_fail（QNN 认领不全，会被分段）/
    run_fail（会话建好但执行崩，通常是 HTP 固件报错）。
    """
    raw = str(_Bridge.probeModel(str(path)))
    out = {}
    for part in raw.split(";"):
        if "=" in part:
            k, v = part.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def data_root():
    """应用数据根目录（<package>/files/ansylab）。"""
    try:
        r = str(_Bridge.dataRoot())
        if r:
            return r
    except Exception:
        pass
    return os.path.expanduser("~")


def plot(label="", points=200, channels=1, region="panel",
         y_min=None, y_max=None, window=0.0):
    """折线图控件。

    - `p.push(x)` / `p.push(x, y)` / `p.push(x, y, z)`
    - `y_min`/`y_max`：锁定 Y 轴范围（都给才生效）
    - `window`：按时间窗口显示，单位秒（0 = 按点数）
    - `p.export_csv()`：导出当前数据
    """
    wid = _Bridge.addPlot(
        str(label), int(points), int(channels), str(region),
        float("nan") if y_min is None else float(y_min),
        float("nan") if y_max is None else float(y_max),
        float(window or 0.0))
    return Plot(wid, points, channels, y_min, y_max, window)


def divider(region="panel"):
    """一条分隔线。"""
    return Divider(_Bridge.addDivider(str(region)))


def header(text="", region="panel"):
    """分组标题。"""
    return Header(_Bridge.addHeader(str(text), str(region)))


# --------------------------------------------------------------------------- #
# 姿态传感器（IMU）
# --------------------------------------------------------------------------- #
class Imu:
    """姿态/运动传感器。

    用法：
        imu = ansylab.imu()
        imu.enable("accel", "gyro", "game", rate_hz=50)
        while True:
            print(imu.acceleration, imu.angular_velocity, imu.euler)
            imu.close()

    可用的传感器名：
        accel     加速度（含重力，m/s²）
        gyro      角速度（rad/s）
        mag       地磁（μT）
        gravity   重力分量（m/s²）
        linear    线性加速度（去重力，m/s²）
        rotation  旋转矢量（含地磁，有绝对航向）
        game      游戏旋转矢量（不含地磁，航向会漂，推荐做平衡控制）
        geomagnetic 地磁旋转矢量（不用陀螺，响应慢）
    """

    def __init__(self):
        _track(self)
        self._kinds = []
        self._rate = 0

    def enable(self, *kinds, **kwargs):
        """开启指定传感器；rate_hz 默认 50。返回空串表示成功，否则是错误信息。"""
        if len(kinds) == 1 and isinstance(kinds[0], (list, tuple)):
            kinds = tuple(kinds[0])
        rate = int(kwargs.get("rate_hz", 50))
        self._kinds = [str(k) for k in kinds] or ["accel"]
        self._rate = rate
        err = _Bridge.imuStart(",".join(self._kinds), rate)
        return "" if err is None else str(err)

    @property
    def running(self):
        return bool(_Bridge.imuRunning())

    @property
    def kinds(self):
        return list(self._kinds)

    @property
    def rate_hz(self):
        return self._rate

    def _raw(self, kind):
        return tuple(float(v) for v in _Bridge.imuValue(kind))

    @property
    def acceleration(self):
        """(x, y, z) 加速度，含重力，单位 m/s²。"""
        return self._raw("accel")

    @property
    def angular_velocity(self):
        """(x, y, z) 角速度，单位 rad/s。"""
        return self._raw("gyro")

    @property
    def magnetic_field(self):
        """(x, y, z) 地磁，单位 μT。"""
        return self._raw("mag")

    @property
    def gravity(self):
        """(x, y, z) 重力分量，单位 m/s²。"""
        return self._raw("gravity")

    @property
    def linear_acceleration(self):
        """(x, y, z) 去重力后的线性加速度，单位 m/s²。"""
        return self._raw("linear")

    @property
    def rotation_vector(self):
        """(x, y, z, w) 旋转矢量原始值（含地磁）。"""
        return self._raw("rotation")

    @property
    def game_rotation_vector(self):
        """(x, y, z, w) 游戏旋转矢量原始值（不含地磁）。"""
        return self._raw("game")

    @property
    def quaternion(self):
        """(w, x, y, z) 四元数；优先用含地磁的 rotation，没有就用 game。"""
        q = self.rotation_vector
        if len(q) < 4:
            q = self.game_rotation_vector
        if len(q) < 4:
            return ()
        return (q[3], q[0], q[1], q[2])

    @property
    def euler(self):
        """(方位角, 俯仰角, 横滚角)，单位**度**。"""
        return tuple(float(v) for v in _Bridge.imuEuler())

    @property
    def euler_rad(self):
        """同 euler，但单位是弧度。"""
        return tuple(math.radians(v) for v in self.euler)

    @property
    def pitch(self):
        """俯仰角（度）。平衡车主要看这个。"""
        e = self.euler
        return e[1] if len(e) > 1 else 0.0

    @property
    def roll(self):
        """横滚角（度）。"""
        e = self.euler
        return e[2] if len(e) > 2 else 0.0

    @property
    def yaw(self):
        """方位角（度）；用 game 传感器时是相对上电朝向的。"""
        e = self.euler
        return e[0] if len(e) > 0 else 0.0

    def accuracy(self, kind):
        """精度：0=不可信 1=低 2=中 3=高。"""
        return int(_Bridge.imuAccuracy(str(kind)))

    def rotation_matrix(self):
        """3x3 旋转矩阵，行优先，长度 9 的列表。"""
        return [float(v) for v in _Bridge.imuRotationMatrix()]

    def sample(self):
        """一次性取 (加速度, 角速度, 欧拉角)，拿不到返回 None。

        比分别读三个属性快得多（一次 JNI 而不是三次）。
        """
        raw = _Bridge.imuSample()
        if raw is None or len(raw) < 9:
            return None
        return ((float(raw[0]), float(raw[1]), float(raw[2])),
                (float(raw[3]), float(raw[4]), float(raw[5])),
                (float(raw[6]), float(raw[7]), float(raw[8])))

    def close(self):
        _Bridge.imuStop()

    def disable(self):
        """停掉传感器（和 close 等价，和 enable 对称）。"""
        _Bridge.imuStop()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    def __repr__(self):
        return "<Imu %s @%dHz running=%s>" % (",".join(self._kinds), self._rate, self.running)


def imu():
    """创建 IMU 对象（需要自己调 enable()）。"""
    return Imu()


def imu_available():
    """列出本机可用的传感器，形如 "accel=lsm6dso Accelerometer@xxxHz"。"""
    return str(_Bridge.imuAvailable())


# --------------------------------------------------------------------------- #
# USB 串口
# --------------------------------------------------------------------------- #
class Serial:
    """USB 转串口（CH340/CH341/CH343、CP210x、FTDI、PL2303 等）。

    用法：
        ports = ansylab.Serial.list()      # 列出设备
        ser = ansylab.Serial(baud=115200)
        ser.open()                          # 用第 0 个设备
        ser.write(b"hello\\n")
        data = ser.read(256)                # 非阻塞，没有就返回 b""
        ser.close()

    注意：第一次打开会弹系统的 USB 授权框，必须先允许；没授权时 open() 会返回提示。
    """

    # 校验位：0=无 1=奇 2=偶 3=标记 4=空格
    PARITY_NONE = 0
    PARITY_ODD = 1
    PARITY_EVEN = 2
    PARITY_MARK = 3
    PARITY_SPACE = 4

    # 流控：0=无 1=RTS/CTS 2=DSR/DTR 4=XON/XOFF
    FLOW_NONE = 0
    FLOW_RTS_CTS = 1
    FLOW_DSR_DTR = 2
    FLOW_XON_XOFF = 4

    def __init__(self, baud=115200, index=0, data_bits=8, stop_bits=1,
                 parity=PARITY_NONE, flow=FLOW_NONE):
        _track(self)
        self.baud = int(baud)
        self.index = int(index)
        self.data_bits = int(data_bits)
        self.stop_bits = int(stop_bits)
        self.parity = int(parity)
        self.flow = int(flow)
        self._opened = False

    # ---------------------------------------------------------------- 枚举
    @staticmethod
    def list():
        """返回串口设备列表（list[dict]）。"""
        try:
            return json.loads(str(_Bridge.serialList()))
        except Exception:
            return []

    @staticmethod
    def describe():
        """设备列表拼成可读文本。"""
        rows = Serial.list()
        if not rows:
            return "没有找到 USB 串口设备"
        return "\n".join(
            "#%d %s%s" % (i, r.get("name"), "" if r.get("hasPermission") else "（未授权）")
            for i, r in enumerate(rows)
        )

    # ---------------------------------------------------------------- 打开
    def open(self, index=None):
        """打开串口，返回空串表示成功，否则是错误信息。"""
        if index is not None:
            self.index = int(index)
        err = _Bridge.serialOpen(self.index, self.baud, self.data_bits,
                                 self.stop_bits, self.parity, self.flow)
        err = "" if err is None else str(err)
        self._opened = err == ""
        if self._opened:
            log("[ansylab] 串口已打开 #%d @%d" % (self.index, self.baud))
        else:
            log("[ansylab] 串口打开失败: " + err)
        return err

    @property
    def is_open(self):
        return bool(_Bridge.serialIsOpen())

    @property
    def name(self):
        """当前打开的串口名，如 "CH34x 1a86:7523"。"""
        return str(json.loads(str(_Bridge.serialStats())).get("name", ""))

    @property
    def available(self):
        """还没读走的字节数。"""
        return int(_Bridge.serialAvailable())

    # ---------------------------------------------------------------- 读写
    def write(self, data):
        """写字节串或 str（str 按 UTF-8 编码）。返回写入字节数，失败 -1。"""
        if isinstance(data, str):
            data = data.encode("utf-8")
        return int(_Bridge.serialWrite(bytes(data), 1000))

    def write_line(self, text, ending="\n"):
        """写一行并追加换行。"""
        return self.write(str(text) + ending)

    def read(self, max_bytes=256):
        """非阻塞读，返回 bytes（可能为空）。"""
        return bytes(_Bridge.serialRead(int(max_bytes)))

    def read_text(self, max_bytes=256, encoding="utf-8"):
        """非阻塞读并解码；非法字节用 replace 兜住。"""
        return self.read(max_bytes).decode(encoding, errors="replace")

    def read_line(self, timeout_ms=1000, max_bytes=4096):
        """读到换行为止，返回 str（超时返回已收到的部分）。"""
        buf = bytearray()
        deadline = time.monotonic() + timeout_ms / 1000.0
        while time.monotonic() < deadline:
            _check_stop()
            chunk = self.read(max_bytes)
            if chunk:
                buf.extend(chunk)
                if b"\n" in chunk:
                    break
            else:
                time.sleep(0.005)
        return bytes(buf).decode("utf-8", errors="replace")

    def read_until(self, timeout_ms=1000, max_bytes=4096):
        """一直读到暂时没有新数据（适合收一整包）。"""
        buf = bytearray()
        deadline = time.monotonic() + timeout_ms / 1000.0
        idle = 0
        while time.monotonic() < deadline:
            _check_stop()
            chunk = self.read(max_bytes)
            if chunk:
                buf.extend(chunk)
                idle = 0
            else:
                idle += 1
                if bytes(buf) and idle > 5:
                    break
                time.sleep(0.005)
        return bytes(buf)

    # ---------------------------------------------------------------- 状态
    def stats(self):
        """返回 dict：open/name/rx/tx/pending/error。"""
        try:
            return json.loads(str(_Bridge.serialStats()))
        except Exception:
            return {}

    def flush_input(self):
        """丢掉接收缓存里还没读的字节。"""
        while self.read(4096):
            pass

    # ------------------------------------------------------- 控制线 / 流控
    def set_dtr(self, on):
        """设置 DTR 电平（不少开发板用它做复位）。返回空串表示成功。"""
        return str(_Bridge.serialSetDtr(bool(on)))

    def set_rts(self, on):
        """设置 RTS 电平。"""
        return str(_Bridge.serialSetRts(bool(on)))

    @property
    def ctrl_lines(self):
        """(dtr, rts) 当前电平；读不到返回 ()。"""
        raw = str(_Bridge.serialCtrlLines())
        if not raw:
            return ()
        parts = raw.split(",")
        return tuple(p == "1" for p in parts)

    def close(self):
        _Bridge.serialClose()
        self._opened = False

    def __enter__(self):
        self.open()
        return self

    def __exit__(self, *exc):
        self.close()

    def __repr__(self):
        return "<Serial #%d @%d open=%s>" % (self.index, self.baud, self.is_open)


def serial_list():
    """列出 USB 串口设备。"""
    return Serial.list()


# --------------------------------------------------------------------------- #
# YOLO：ONNX Runtime + QNN EP，推理跑在 Hexagon NPU 上
class YOLO:
    """YOLOv8 检测器，推理走设备 NPU（QNN HTP）。

    用法：
        import ansylab
        model = ansylab.YOLO("/data/user/0/<pkg>/files/models/yolov8n.onnx")
        dets = model.detect(frame)          # [[x1,y1,x2,y2,cls,score], ...]

    frame 是 OpenCV 习惯的 BGR ndarray，返回坐标已映射回原图。
    """

    def __init__(self, model_path, conf=0.25, iou=0.45, imgsz=None, names=None,
                 use_npu=True):
        _track(self)
        self.model_path = str(model_path)
        self.conf = float(conf)
        self.iou = float(iou)
        self.use_npu = bool(use_npu)

        err = _Bridge.yoloInit(self.model_path, self.use_npu)
        if err:
            raise RuntimeError("YOLO 初始化失败: %s" % err)
        print("[yolo] %s" % _Bridge.yoloInfo())

        # 类名、类别数、输入尺寸都以模型 metadata 为准，代码里不内置类别表
        self.names = list(names) if names else self._names_from_model()
        self._nc = len(self.names) or int(_Bridge.yoloClasses()) or 1
        if not self.names:
            self.names = ["class_%d" % i for i in range(self._nc)]
        self.imgsz = int(imgsz) if imgsz else (int(_Bridge.yoloImgsz()) or 640)

        self._input = None      # 复用的 float32 CHW 缓冲（旧路径）
        self._canvas = None     # letterbox 画布
        self.last_timing = (0.0, 0.0, 0.0)   # (预处理, 推理, 后处理) ms

        # native 输入缓冲：分配一次，之后每帧只写不搬
        self._in_count = 1 * 3 * self.imgsz * self.imgsz
        self._in_ptr = int(_Native.allocInput(self._in_count))
        self._in_view = None
        if self._in_ptr:
            raw = (ctypes.c_float * self._in_count).from_address(self._in_ptr)
            self._in_view = np.frombuffer(raw, dtype=np.float32).reshape(
                (1, 3, self.imgsz, self.imgsz))

    @staticmethod
    def _names_from_model():
        """从模型 metadata 读类名；模型没写就返回空列表。"""
        try:
            raw = str(_Bridge.yoloNames())
            if not raw or raw == "[]":
                return []
            return [str(x) for x in json.loads(raw)]
        except Exception:
            return []

    def _preprocess(self, img):
        """旧路径：结果放在 Python 侧数组里，调用时要 tobytes() 传给 Kotlin。"""
        h, w = img.shape[:2]
        s = self.imgsz
        scale = min(s / w, s / h)
        nw, nh = int(round(w * scale)), int(round(h * scale))

        if self._canvas is None or self._canvas.shape[0] != s:
            self._canvas = np.full((s, s, 3), 114, dtype=np.uint8)

        resized = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_LINEAR)
        dx, dy = (s - nw) // 2, (s - nh) // 2
        self._canvas[:] = 114
        self._canvas[dy:dy + nh, dx:dx + nw] = resized

        rgb = cv2.cvtColor(self._canvas, cv2.COLOR_BGR2RGB)
        chw = np.ascontiguousarray(rgb.transpose(2, 0, 1), dtype=np.float32) / 255.0

        if self._input is None:
            self._input = np.empty((1, 3, s, s), dtype=np.float32)
        self._input[0] = chw
        return self._input, scale, dx, dy

    def _preprocess_native(self, img):
        """新路径：预处理结果直接写进 native 缓冲，省掉 tobytes() 和跨语言拷贝。

        cv2.dnn.blobFromImage 一次完成 BGR→RGB、/255、HWC→CHW；
        letterbox 的缩放和 pad 仍然用 cv2（NEON 加速），写进复用的画布。
        """
        h, w = img.shape[:2]
        s = self.imgsz
        scale = min(s / w, s / h)
        nw, nh = int(round(w * scale)), int(round(h * scale))

        if self._canvas is None or self._canvas.shape[0] != s:
            self._canvas = np.full((s, s, 3), 114, dtype=np.uint8)

        dx, dy = (s - nw) // 2, (s - nh) // 2
        self._canvas[:] = 114
        self._canvas[dy:dy + nh, dx:dx + nw] = cv2.resize(
            img, (nw, nh), interpolation=cv2.INTER_LINEAR)

        # 等价于 blobFromImage（BGR→RGB、/255、HWC→CHW），
        # 但直接写进预分配的 native 缓冲，不再每帧分配 4.9 MB
        rgb = self._canvas[:, :, ::-1]              # 视图，BGR -> RGB
        np.divide(rgb.transpose(2, 0, 1), 255.0,
                  out=self._in_view[0], dtype=np.float32)
        return scale, dx, dy

    def _postprocess(self, raw, scale, dx, dy, orig_w, orig_h):
        out = np.frombuffer(raw, dtype=np.float32)
        nc = self._nc
        if nc <= 0 or out.size % (4 + nc) != 0:
            raise RuntimeError(
                "输出维度不匹配：size=%d，类别数=%d（应为 (1, 4+nc, N)）" % (out.size, nc))
        out = out.reshape(1, 4 + nc, -1)[0]                    # (4+nc, N)
        out = out.transpose(1, 0)                              # (N, 4+nc)

        scores_all = out[:, 4:]
        cls = np.argmax(scores_all, axis=1)
        scores = scores_all[np.arange(scores_all.shape[0]), cls]
        keep = scores >= self.conf
        if not np.any(keep):
            return []

        boxes = out[keep, :4]
        cls = cls[keep]
        scores = scores[keep]

        # cx,cy,w,h -> x1,y1,x2,y2（letterbox 坐标）
        cx, cy, bw, bh = boxes[:, 0], boxes[:, 1], boxes[:, 2], boxes[:, 3]
        x1 = cx - bw / 2
        y1 = cy - bh / 2
        x2 = cx + bw / 2
        y2 = cy + bh / 2

        # 去掉 padding 并映射回原图
        x1 = np.clip((x1 - dx) / scale, 0, orig_w)
        y1 = np.clip((y1 - dy) / scale, 0, orig_h)
        x2 = np.clip((x2 - dx) / scale, 0, orig_w)
        y2 = np.clip((y2 - dy) / scale, 0, orig_h)

        # 统一约定：(x1, y1, x2, y2, cls, score)；NMS 与调用方都用这个顺序
        dets = np.stack([x1, y1, x2, y2, cls.astype(np.float32), scores], axis=1)
        return _nms(dets, self.iou)

    def detect(self, img, conf=None, iou=None):
        if img is None:
            return []
        if conf is not None:
            old, self.conf = self.conf, float(conf)
        else:
            old = None
        try:
            orig_h, orig_w = img.shape[:2]
            t0 = time.monotonic()
            if self._in_view is not None:
                # 快路径：预处理直接写进 native 缓冲，只传一次指针
                scale, dx, dy = self._preprocess_native(img)
                t1 = time.monotonic()
                raw = _Bridge.yoloRunNative()
            else:
                tensor, scale, dx, dy = self._preprocess(img)
                t1 = time.monotonic()
                raw = _Bridge.yoloRun(tensor.tobytes())
            t2 = time.monotonic()
            if raw is None:
                return []
            dets = self._postprocess(raw, scale, dx, dy, orig_w, orig_h)
            t3 = time.monotonic()
            self.last_timing = ((t1 - t0) * 1000.0, (t2 - t1) * 1000.0, (t3 - t2) * 1000.0)
            return dets
        finally:
            if old is not None:
                self.conf = old

    def close(self):
        _Bridge.yoloClose()


def _nms(dets, iou_thres):
    """按类 NMS。dets: (N,6) = x1,y1,x2,y2,cls,score（与返回值同序）。"""
    if len(dets) == 0:
        return []
    out = []
    for c in np.unique(dets[:, 4]):
        group = dets[dets[:, 4] == c]
        order = group[:, 5].argsort()[::-1]
        group = group[order]
        keep = []
        while len(group) > 0:
            keep.append(group[0])
            if len(group) == 1:
                break
            rest = group[1:]
            xx1 = np.maximum(group[0, 0], rest[:, 0])
            yy1 = np.maximum(group[0, 1], rest[:, 1])
            xx2 = np.minimum(group[0, 2], rest[:, 2])
            yy2 = np.minimum(group[0, 3], rest[:, 3])
            inter = np.maximum(0.0, xx2 - xx1) * np.maximum(0.0, yy2 - yy1)
            area0 = (group[0, 2] - group[0, 0]) * (group[0, 3] - group[0, 1])
            arear = (rest[:, 2] - rest[:, 0]) * (rest[:, 3] - rest[:, 1])
            iou = inter / np.maximum(1e-6, area0 + arear - inter)
            group = rest[iou <= iou_thres]
        out.extend(keep)
    # 原样返回，保持 (x1, y1, x2, y2, cls, score)
    return [[float(b[0]), float(b[1]), float(b[2]), float(b[3]),
             int(b[4]), float(b[5])] for b in out]
