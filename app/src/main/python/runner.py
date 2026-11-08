"""脚本执行器：由 Kotlin 的 MainActivity 调用 runner.run_file(path)。

职责：
  1. 让 cv2.imshow 报错（显示统一走 ansylab.display().show()），waitKey 变成空操作
  2. 把 stdout / stderr 接到 App 的日志窗口
  3. exec 用户脚本，并保证异常能显示出行号
  4. 同时把输出写一份到 files/logs/last_run.log
     （手机 logcat 有时是空的，只能靠这个文件把完整 traceback 拉出来）
"""

import io
import os
import sys
import traceback

import cv2
from java import jclass

_Bridge = jclass("com.ansylab.vision.PyBridge")
_Log = jclass("android.util.Log")
_LOG_TAG = "ansylab-py"

_log_file = None


def _open_log_file():
    """每次运行覆盖写一份，路径 files/logs/last_run.log。"""
    global _log_file
    try:
        base = _Bridge.appFilesDir()
        if not base:
            return None
        d = os.path.join(base, "logs")
        os.makedirs(d, exist_ok=True)
        _log_file = open(os.path.join(d, "last_run.log"), "w", encoding="utf-8", buffering=1)
        return _log_file
    except BaseException:
        return None


class _Stream(io.TextIOBase):
    """把 print 的内容同时转发到 App 日志区、logcat 和文件。"""

    def writable(self):
        return True

    def write(self, text):
        if text:
            message = str(text)
            try:
                _Bridge.log(message)
            except BaseException:
                pass
            try:
                _Log.i(_LOG_TAG, message.rstrip("\n"))
            except BaseException:
                pass
            try:
                if _log_file is not None:
                    _log_file.write(message)
            except BaseException:
                pass
        return len(text) if text else 0

    def flush(self):
        try:
            if _log_file is not None:
                _log_file.flush()
        except BaseException:
            pass


def _patch_cv2():
    """显示不再走 cv2.imshow：调用时直接报错并给出用法提示。"""

    def imshow(*args, **kwargs):
        raise RuntimeError(
            "cv2.imshow 已不再支持，请改用 ansylab.display().show(frame)：\n"
            "    view = ansylab.display()\n"
            "    ...\n"
            "    view.show(frame)"
        )

    cv2.imshow = imshow
    cv2.namedWindow = lambda *args, **kwargs: None
    cv2.waitKey = lambda delay=0: -1
    cv2.destroyWindow = lambda *args, **kwargs: None
    cv2.destroyAllWindows = lambda *args, **kwargs: None


def run_file(path):
    _patch_cv2()
    _open_log_file()

    gen = int(_Bridge.sessionGen())

    stream = _Stream()
    old_stdout, old_stderr = sys.stdout, sys.stderr
    sys.stdout = stream
    sys.stderr = stream

    try:
        import ansylab

        ansylab._bind_session()
        ansylab._autostart_web()      # 设置里开了「网页控制」就自动起服务
        ansylab.log("[py] 开始执行 " + str(path))
        with open(path, encoding="utf-8") as f:
            source = f.read()

        code = compile(source, path, "exec")
        globs = {"__name__": "__main__", "__file__": path}
        exec(code, globs)

    except KeyboardInterrupt:
        print("[py] 已被停止")
    except BaseException:
        traceback.print_exc()
        # 把错误摘要顶到界面，不用连电脑也能看到原因
        try:
            import traceback as _tb
            text = "".join(_tb.format_exception(*sys.exc_info()))
            _Bridge.scriptError(text.strip())
        except BaseException:
            pass
    finally:
        sys.stdout = old_stdout
        sys.stderr = old_stderr
        try:
            if _log_file is not None:
                _log_file.flush()
                _log_file.close()
        except BaseException:
            pass
        try:
            # 先停网页服务，再统一释放：YOLO 会话 / 串口 / IMU / 相机
            import ansylab as _ansylab
            _ansylab._shutdown_web()
            _ansylab._close_all()
        except BaseException:
            pass
        try:
            # 只有当自己还是「当前会话」时才关相机。
            # 快速开关时旧脚本会比新会话晚退出，若无条件 stopCamera，
            # 会把新会话的相机一起关掉 —— 新脚本随即读不到帧、打印「脚本结束」。
            if int(_Bridge.sessionGen()) == gen:
                _Bridge.stopCamera()
        except BaseException:
            pass
