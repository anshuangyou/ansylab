package com.ansylab.vision

import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Python 与 Kotlin 之间的控制桥。
 *
 * 注意：像素数据**不走这里**。帧数据由 Python 用 ctypes 直接读 native 帧池，
 * 显示由 Python 画进 native 显示缓冲后调用 [present] 通知一次。
 * 这里只保留控制面（启动/停止/日志/路径），每次调用都是几十字节的参数。
 */
object PyBridge {

    private var activityRef: WeakReference<MainActivity>? = null
    private var cameraRef: CameraController? = null

    private val stopRequested = AtomicBoolean(false)

    fun attach(activity: MainActivity, camera: CameraController) {
        activityRef = WeakReference(activity)
        cameraRef = camera
    }

    // ------------------------------------------------------------------ 相机

    @JvmStatic
    fun startCamera(width: Int, height: Int, fps: Int) {
        cameraRef?.start(width, height, null, fps)
    }

    /** 带 camera id 的启动；id 为空/找不到时退回默认后置主摄。 */
    @JvmStatic
    fun startCameraById(width: Int, height: Int, fps: Int, cameraId: String) {
        cameraRef?.start(width, height, cameraId.ifBlank { null }, fps)
    }

    @JvmStatic
    fun stopCamera() {
        cameraRef?.stop()
    }

    /** 相机停止后让 UI 也丢掉旧的硬件显示缓冲（否则下次会画到已释放的缓冲）。 */
    @JvmStatic
    fun resetDisplayState() {
        activityRef?.get()?.resetDisplay()
    }

    /** 相机清单（JSON 数组字符串）。 */
    @JvmStatic
    fun cameraList(): String {
        val ctx = activityRef?.get() ?: return "[]"
        return CameraCatalog.listJson(ctx)
    }

    /** 开/关闪光灯；返回空串表示成功。优先走 CameraX（已占用相机时 setTorchMode 会失败）。 */
    @JvmStatic
    fun cameraTorch(on: Boolean): String {
        val ctx = activityRef?.get() ?: return "Python 未就绪"
        val cam = cameraRef
        if (cam != null && cam.isRunning()) return cam.setTorch(on)
        // 相机没开时用系统手电筒模式，也能亮
        return CameraCatalog.setTorch(ctx, on)
    }

    @JvmStatic
    fun cameraTorchAvailable(): Boolean {
        val ctx = activityRef?.get() ?: return false
        val cam = cameraRef
        if (cam != null && cam.isRunning()) return cam.hasFlash()
        return CameraCatalog.torchAvailable(ctx)
    }

    /** 当前真正在用的 camera id（可能是默认选出来的）。 */
    @JvmStatic
    fun cameraCurrentId(): String = CameraCatalog.currentId ?: ""

    // ------------------------------------------------------------------ 显示

    /** Python 画完一帧后调用；硬件路径下这里解锁并交换前后台，再通知 UI。 */
    @JvmStatic
    fun present() {
        activityRef?.get()?.onPresented()
    }

    /**
     * Python 首次 imshow（或尺寸变化）时调用，让 Kotlin 准备硬件显示缓冲。
     * 返回 false 时 Python 会退回软件缓冲 + copyPixelsFromBuffer。
     */
    @JvmStatic
    fun prepareDisplay(width: Int, height: Int): Boolean {
        return activityRef?.get()?.prepareDisplay(width, height) ?: false
    }

    @JvmStatic
    fun log(message: String?) {
        val text = message ?: ""
        val act = activityRef?.get()
        if (act != null) {
            // MainActivity.log 内部已经会落盘，这里不要再写一次，
            // 否则每条日志都会出现两遍（看起来像「脚本跑了两份」，实际是重复写）。
            act.log(text)
        } else {
            appendToFile(text)
        }
    }

    private val logLock = Any()

    /** 供 MainActivity 复用的落盘方法（避免 MainActivity.log 递归回 PyBridge.log）。 */
    @JvmStatic
    fun logFile(text: String?) {
        appendToFile(text ?: "")
    }

    private fun appendToFile(text: String) {
        val ctx = activityRef?.get() ?: return
        if (text.isEmpty()) return
        synchronized(logLock) {
            try {
                val dir = Storage.logs(ctx)
                val f = java.io.File(dir, "native.log")
                // 超过 2 MB 就重开，避免无限增长（太小会在复现过程中把关键日志截掉）
                if (f.length() > 2 * 1024 * 1024) f.delete()
                f.appendText(text.trimEnd() + "\n")
            } catch (_: Throwable) {
            }
        }
    }

    /** libansylab.so 的绝对路径，供 Python 的 ctypes 加载。 */
    @JvmStatic
    fun nativeLibPath(): String {
        val ctx = activityRef?.get() ?: return ""
        return ctx.applicationInfo.nativeLibraryDir + "/libansylab.so"
    }

    /** jniLibs 解压后的目录：QNN 的 libQnnHtp.so 就在这里。 */
    @JvmStatic
    fun nativeLibDir(): String {
        val ctx = activityRef?.get() ?: return ""
        return ctx.applicationInfo.nativeLibraryDir
    }

    /** 设置里选的 NPU 性能模式（burst / balanced / high_performance / power_saver）。 */
    @JvmStatic
    /**
     * NPU 性能模式：固定用最高性能（burst）。
     *
     * 不在设置里提供「均衡/省电」：本 App 的场景是实时推理，
     * 降频只会掉帧，所以不再暴露给用户。
     */
    fun npuPerfMode(): String = "burst"

    // ------------------------------------------------------------------ IMU

    /** 开始采集；kinds 用逗号分隔（accel,gyro,rotation...）。返回空串表示成功。 */
    @JvmStatic
    fun imuStart(kinds: String, rateHz: Int): String {
        val ctx = activityRef?.get() ?: return "Python 未就绪"
        val list = kinds.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return ImuSensor.start(ctx, list.ifEmpty { listOf("accel") }, rateHz)
    }

    @JvmStatic
    fun imuStop() = ImuSensor.stop()

    /** 某个传感器的原始值；没有数据返回空数组。 */
    @JvmStatic
    fun imuValue(kind: String): FloatArray = ImuSensor.value(kind)

    @JvmStatic
    fun imuAccuracy(kind: String): Int = ImuSensor.accuracyOf(kind)

    /** 欧拉角（度）：[方位角, 俯仰, 横滚]。 */
    @JvmStatic
    fun imuEuler(): FloatArray = ImuSensor.euler()

    /** 3x3 旋转矩阵，行优先。 */
    @JvmStatic
    fun imuRotationMatrix(): FloatArray = ImuSensor.rotationMatrix()

    /**
     * 一次性取 9 个数：加速度(3) + 角速度(3) + 欧拉角(3)。
     *
     * 分三次调用要过三次 JNI，循环只能跑到 70Hz 左右；
     * 合起来一次拿，200Hz 采样才跟得上。
     */
    @JvmStatic
    fun imuSample(): FloatArray {
        val a = ImuSensor.value("accel")
        val g = ImuSensor.value("gyro")
        val e = ImuSensor.euler()
        if (a.size < 3 || g.size < 3 || e.size < 3) return FloatArray(0)
        return floatArrayOf(
            a[0], a[1], a[2],
            g[0], g[1], g[2],
            e[0], e[1], e[2]
        )
    }

    @JvmStatic
    fun imuAvailable(): String {
        val ctx = activityRef?.get() ?: return ""
        return ImuSensor.available(ctx)
    }

    @JvmStatic
    fun imuRunning(): Boolean = ImuSensor.running

    // ------------------------------------------------------------ 第二批控件

    @JvmStatic
    fun addProgress(
        label: String, value: Double, max: Double, indeterminate: Boolean, region: String
    ): String = WidgetStore.addProgress(label, value, max, indeterminate, region)

    @JvmStatic
    fun addImage(label: String, heightDp: Int, region: String): String =
        WidgetStore.addImage(label, heightDp, region)

    @JvmStatic
    fun addCheckbox(label: String, checked: Boolean, region: String): String =
        WidgetStore.addCheckbox(label, checked, region)

    @JvmStatic
    fun addRadio(label: String, items: String, index: Int, region: String): String =
        WidgetStore.addRadio(label, items.split('\n').filter { it.isNotEmpty() }, index, region)

    @JvmStatic
    fun addTextField(label: String, value: String, region: String): String =
        WidgetStore.addTextField(label, value, region)

    @JvmStatic
    fun addDivider(region: String): String = WidgetStore.addDivider(region)

    @JvmStatic
    fun addHeader(text: String, region: String): String = WidgetStore.addHeader(text, region)

    @JvmStatic
    fun progressValue(id: String): Double = WidgetStore.progressValue(id)

    @JvmStatic
    fun progressSet(id: String, value: Double, indeterminate: Boolean, setMode: Boolean) {
        WidgetStore.setProgress(id, value, if (setMode) indeterminate else null)
    }

    @JvmStatic
    fun imageSet(id: String, bytes: ByteArray) = WidgetStore.setImageBytes(id, bytes)

    // ------------------------------------------------------- 所有控件的通用能力

    @JvmStatic
    fun widgetSetVisible(id: String, visible: Boolean) = WidgetStore.setVisible(id, visible)

    /** 控件当前是否可见（网页要按它过滤）。 */
    @JvmStatic
    fun widgetIsVisible(id: String): Boolean =
        WidgetStore.find(id)?.visible ?: true

    /** 控件当前是否可用。 */
    @JvmStatic
    fun widgetIsEnabled(id: String): Boolean =
        WidgetStore.find(id)?.enabled ?: true

    @JvmStatic
    fun widgetSetEnabled(id: String, enabled: Boolean) = WidgetStore.setEnabled(id, enabled)

    @JvmStatic
    fun widgetSetLabel(id: String, label: String) = WidgetStore.setLabelOf(id, label)

    @JvmStatic
    fun widgetRemove(id: String) = WidgetStore.remove(id)

    @JvmStatic
    fun widgetIndex(id: String): Int = WidgetStore.comboIndex(id)

    /** 泛用的下标写入（下拉框/单选组/列表通用）。 */
    @JvmStatic
    fun widgetSetIndex(id: String, index: Int) = WidgetStore.setIndex(id, index)

    @JvmStatic
    fun widgetItemsOf(id: String): String =
        WidgetStore.comboItems(id).joinToString("\n")

    @JvmStatic
    fun widgetSetItems(id: String, items: String, index: Int) {
        WidgetStore.setComboItems(id, items.split('\n').filter { it.isNotEmpty() }, index)
    }



    /** 列出 USB 串口设备（JSON 数组）。 */
    @JvmStatic
    fun serialList(): String {
        val ctx = activityRef?.get() ?: return "[]"
        return SerialPortManager.list(ctx)
    }

    /** 打开串口；返回空串表示成功。parity: 0=None 1=Odd 2=Even 3=Mark 4=Space；flow: 0/1/2/4。 */
    @JvmStatic
    fun serialOpen(
        index: Int, baud: Int, dataBits: Int, stopBits: Int, parity: Int, flow: Int
    ): String {
        val ctx = activityRef?.get() ?: return "Python 未就绪"
        return SerialPortManager.open(ctx, index, baud, dataBits, stopBits, parity, flow)
    }

    @JvmStatic
    fun serialSetDtr(on: Boolean): String = SerialPortManager.setDtr(on)

    @JvmStatic
    fun serialSetRts(on: Boolean): String = SerialPortManager.setRts(on)

    @JvmStatic
    fun serialCtrlLines(): String = SerialPortManager.ctrlLines()

    @JvmStatic
    fun serialClose() = SerialPortManager.close()

    @JvmStatic
    fun serialIsOpen(): Boolean = SerialPortManager.isOpen

    @JvmStatic
    fun serialWrite(data: ByteArray, timeoutMs: Int): Int =
        SerialPortManager.write(data, timeoutMs)

    /** 非阻塞读，最多 max 字节。 */
    @JvmStatic
    fun serialRead(max: Int): ByteArray = SerialPortManager.read(max)

    @JvmStatic
    fun serialAvailable(): Int = SerialPortManager.available()

    @JvmStatic
    fun serialStats(): String {
        val o = org.json.JSONObject()
        o.put("open", SerialPortManager.isOpen)
        o.put("name", SerialPortManager.currentName)
        o.put("rx", SerialPortManager.bytesReceived)
        o.put("tx", SerialPortManager.bytesSent)
        o.put("pending", SerialPortManager.available())
        o.put("error", SerialPortManager.lastError)
        return o.toString()
    }

    /** 数据根目录：程序、日志、配置都在它下面。 */    @JvmStatic
    fun appRootDir(): String {
        val ctx = activityRef?.get() ?: return ""
        return Storage.ensure(ctx).absolutePath
    }

    /** 应用私有 files 目录，用来放 ORT 的 profile / 临时文件。 */
    @JvmStatic
    fun appFilesDir(): String {
        val ctx = activityRef?.get() ?: return ""
        return Storage.ensure(ctx).absolutePath
    }

    // ------------------------------------------------------------------ YOLO

    /** 加载 onnx 模型并建会话；返回空串表示成功，否则是错误信息。 */
    @JvmStatic
    fun yoloInit(modelPath: String, useNpu: Boolean): String {
        return if (YoloSession.create(modelPath, useNpu)) "" else YoloSession.lastError
    }

    @JvmStatic
    fun yoloInfo(): String = YoloSession.info()

    /** 输入 float32 [1,3,640,640] 的原始字节，返回输出张量的原始字节。 */
    @JvmStatic
    fun yoloRun(input: ByteArray): ByteArray? = YoloSession.run(input)

    /** 输入已经写在 native 缓冲里（见 NativeCamera.allocInput），直接推理。 */
    @JvmStatic
    fun yoloRunNative(): ByteArray? = YoloSession.runFromNative()

    @JvmStatic
    fun yoloClose() {
        YoloSession.close()
    }

    // ------------------------------------------------------------------ 控件
    //
    // Python 侧封装在 ansylab.py 的 slider()/button()/toggle()/label() 里，
    // 这里只做最薄的一层转发，避免在 JNI 边界上做任何逻辑。

    @JvmStatic
    fun clearWidgets() = WidgetStore.clear()

    @JvmStatic
    fun addSlider(
        label: String, min: Double, max: Double, step: Double, value: Double, region: String
    ): String = WidgetStore.addSlider(label, min, max, step, value, region)

    @JvmStatic
    fun addButton(label: String, region: String): String = WidgetStore.addButton(label, region)

    @JvmStatic
    fun addToggle(label: String, value: Boolean, region: String): String =
        WidgetStore.addToggle(label, value, region)

    @JvmStatic
    fun addLabel(text: String, region: String): String = WidgetStore.addLabel(text, region)

    /** 视频显示控件（固定区）。脚本不建的话，imshow 首次调用会自动建一个。 */
    @JvmStatic
    fun addDisplay(region: String): String = WidgetStore.addDisplay(region)

    @JvmStatic
    fun addTextInput(label: String, value: String, maxHeightDp: Int, region: String): String =
        WidgetStore.addTextInput(label, value, maxHeightDp, region)

    @JvmStatic
    fun addTextOutput(text: String, maxHeightDp: Int, region: String): String =
        WidgetStore.addTextOutput(text, maxHeightDp, region)

    /** 多行输入框是否点了「确定」（取走即清零）。 */
    @JvmStatic
    fun widgetSubmit(id: String): Boolean = WidgetStore.takeSubmit(id)

    @JvmStatic
    fun addJoystick(label: String, region: String): String =
        WidgetStore.addJoystick(label, region)

    @JvmStatic
    fun addDoubleJoystick(label: String, region: String): String =
        WidgetStore.addDoubleJoystick(label, region)

    /** 摇杆坐标，归一化 [-1, 1]，y 向上为正。 */
    @JvmStatic
    fun joystickX(id: String, second: Boolean): Float = WidgetStore.joystickX(id, second)

    // 下拉框

    @JvmStatic
    fun addCombo(label: String, items: String, index: Int, region: String): String {
        // items 用 \n 分隔（不会出现在选项文本里的字符）
        val list = items.split('\n').filter { it.isNotEmpty() }
        return WidgetStore.addCombo(label, list, index, region)
    }

    @JvmStatic
    fun comboIndex(id: String): Int = WidgetStore.comboIndex(id)    @JvmStatic
    fun comboText(id: String): String = WidgetStore.comboText(id)

    @JvmStatic
    fun comboItems(id: String): String = WidgetStore.comboItems(id).joinToString("\n")

    @JvmStatic
    fun comboSetItems(id: String, items: String, index: Int) {
        WidgetStore.setComboItems(id, items.split('\n').filter { it.isNotEmpty() }, index)
    }

    @JvmStatic
    fun joystickY(id: String, second: Boolean): Float = WidgetStore.joystickY(id, second)

    @JvmStatic
    fun widgetValue(id: String): Double = WidgetStore.value(id)

    @JvmStatic
    fun widgetBool(id: String): Boolean = WidgetStore.boolValue(id)

    /** Python 改开关状态（比如操作失败时把开关拨回去）。 */
    @JvmStatic
    fun widgetSetFlag(id: String, value: Boolean) {
        // 就地刷新：整块重建会把固定区的滚动位置重置，看起来就是画面抖一下
        WidgetStore.setFlagLive(id, value)
    }

    @JvmStatic
    fun widgetText(id: String): String = WidgetStore.textValue(id)

    @JvmStatic
    fun widgetClicked(id: String): Boolean = WidgetStore.takeClick(id)

    /** 网页点按钮：标记成「被点过」，脚本下一次轮询就能看到。 */
    @JvmStatic
    fun widgetClick(id: String) = WidgetStore.markClicked(id)

    @JvmStatic
    fun widgetSetText(id: String, text: String) = WidgetStore.setText(id, text)

    /**
     * 网页点「确定」：写入内容并标记已提交。
     *
     * 只调 widgetSetText 的话脚本读 `.submitted` 永远是 false，
     * 网页上点确定就没有任何反应。
     */
    @JvmStatic
    fun widgetSubmitText(id: String, text: String) {
        WidgetStore.setText(id, text)
        WidgetStore.markSubmitted(id)
    }

    /** 网页远程控制用：移动摇杆。second=true 表示双摇杆的右边那个。 */
    @JvmStatic
    fun widgetSetJoystick(id: String, x: Double, y: Double, second: Boolean) =
        WidgetStore.setJoystickLive(id, x.toFloat(), y.toFloat(), second)

    /** 进度条是否是「不确定进度」（转圈/来回跑的那种）。 */
    @JvmStatic
    fun widgetIndeterminate(id: String): Boolean =
        WidgetStore.find(id)?.indeterminate ?: false

    @JvmStatic
    fun addPlot(
        label: String, points: Int, channels: Int, region: String,
        yMin: Double, yMax: Double, windowSec: Double
    ): String = WidgetStore.addPlot(
        label, points, channels, region,
        if (yMin.isNaN()) null else yMin,
        if (yMax.isNaN()) null else yMax,
        windowSec
    )

    /** 模型自检：返回 key=value; 形式的探测结果（测试用）。 */
    @JvmStatic
    fun probeModel(path: String): String = ModelProbe.probe(path)

    /** 控件内容版本号（任何变化都会递增），网页推送用来判断是否需要重建 JSON。 */
    @JvmStatic
    fun widgetRevision(): Long = WidgetStore.revision

    /** 模型自带的类名，JSON 数组；模型没有 metadata 时返回 "[]"。 */
    @JvmStatic
    fun yoloNames(): String {
        val names = YoloSession.modelNames
        if (names.isEmpty()) return "[]"
        return names.joinToString(",", "[", "]") { s ->
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
    }

    /** 模型输出的类别数（shape[1] - 4）。 */
    @JvmStatic
    fun yoloClasses(): Int = YoloSession.numClasses

    /** 模型 metadata 里的输入尺寸；0 表示模型没写。 */
    @JvmStatic
    fun yoloImgsz(): Int = YoloSession.modelImgsz

    /** 折线图时间戳（毫秒），导出 CSV 用。 */
    @JvmStatic
    fun plotTimes(id: String): LongArray = WidgetStore.plotTimes(id).toLongArray()

    /** 推一个点：values 是 1~3 个数（x / x,y / x,y,z）。 */
    @JvmStatic
    fun plotPush(id: String, values: FloatArray) = WidgetStore.pushPlot(id, values)

    @JvmStatic
    fun plotClear(id: String) = WidgetStore.clearPlot(id)

    /** 控件在哪个区域（main = 固定区，panel = 折叠区）。 */
    @JvmStatic
    fun widgetRegion(id: String): String = WidgetStore.find(id)?.region ?: "panel"

    /** 应用数据根目录（脚本存日志、导出 CSV 用）。 */
    @JvmStatic
    fun dataRoot(): String {
        val ctx = activityRef?.get() ?: return ""
        return Storage.root(ctx).absolutePath
    }

    /** 控件的显示名（Python 侧的 label 是只写的，网页需要读回来）。 */
    @JvmStatic
    fun widgetLabelOf(id: String): String = WidgetStore.find(id)?.label ?: ""

    /** 数值控件的取值范围，返回 "min,max,step"。 */
    @JvmStatic
    fun widgetRange(id: String): String {
        val m = WidgetStore.find(id) ?: return "0,1,0.01"
        return "%s,%s,%s".format(m.min, m.max, m.step)
    }

    /** 网页远程控制用：改滑块/进度条的值（会顺带刷新界面）。 */
    @JvmStatic
    fun widgetSetValue(id: String, value: Double) {
        WidgetStore.setNumberLive(id, value)
    }

    /**
     * 脚本打开相机时指定对焦模式（auto / continuous / infinity / none）。
     * 返回空串表示成功。
     */
    @JvmStatic
    fun setCameraFocus(mode: String): String = CameraController.applyFocus(mode)

    // ------------------------------------------------------------ 网页控制

    /** 脚本抛异常时把 traceback 送到界面。 */
    @JvmStatic
    fun scriptError(text: String) {
        val act = activityRef?.get() ?: return
        act.postScriptError(text)
    }

    /**
     * 网页控制是否开启（设置页的开关）。
     *
     * Python 侧启动脚本时会读它决定要不要起 Web 服务；
     * 运行中关掉开关时，服务线程每秒检查一次，会自己退出。
     */
    @JvmStatic
    fun webEnabled(): Boolean {
        val ctx = activityRef?.get() ?: return false
        return Storage.webEnabled(ctx)
    }

    /** 网页是否需要密码。 */
    @JvmStatic
    fun webUsePassword(): Boolean {
        val ctx = activityRef?.get() ?: return true
        return Storage.webUsePassword(ctx)
    }

    /** 网页访问密码（明文，设置里也能看到）。 */
    @JvmStatic
    fun webPassword(): String {
        val ctx = activityRef?.get() ?: return "123456"
        return Storage.webPassword(ctx)
    }

    /** 设置里关掉开关时调用：让 Python 侧尽快停掉服务。 */
    @JvmStatic
    fun stopWeb() {
        // Python 的服务线程会通过 webEnabled() 发现开关已关，1 秒内自行退出。
        // 这里不需要额外动作，留个方法名方便以后加主动通知。
    }

    /**
     * 本机所有 IPv4 地址（逗号分隔）。
     *
     * Python 侧 socket.if_nameindex() 在 Android 上会抛 PermissionError，
     * 所以网卡枚举放在 Kotlin 做。
     */
    @JvmStatic
    fun localIps(): String {
        val out = ArrayList<String>()
        try {
            val nifs = java.net.NetworkInterface.getNetworkInterfaces() ?: return ""
            for (nif in java.util.Collections.list(nifs)) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in java.util.Collections.list(nif.inetAddresses)) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { out.add(it) }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        // 排序：192.168 > 172.16-31（热点）> 其它 > 10.x（移动数据常用）
        return out.distinct().sortedBy { ip ->
            when {
                ip.startsWith("192.168.") -> 0
                ip.startsWith("172.") -> 1
                ip.startsWith("10.") -> 3
                else -> 2
            }
        }.joinToString(",")
    }

    fun droppedFrames(): Long = NativeCamera.droppedFrames()

    // ------------------------------------------------------------------ 停止
    //
    // 只用一个布尔标志是不够的：返回主页后立刻再进同一个程序，旧脚本线程还没
    // 看到标志，新脚本就把 clearStop() 清掉了，于是两个脚本同时跑、同时抢帧，
    // 表现为偶发卡顿/卡死。这里加一个「会话代号」：每开一次新脚本代号 +1，
    // 旧线程发现代号变了就自己退出。

    private val generation = java.util.concurrent.atomic.AtomicInteger(0)

    @JvmStatic
    fun requestStop() {
        stopRequested.set(true)
        generation.incrementAndGet()
    }

    /** 开新会话：清掉停止标志并换一个代号，返回给脚本记住。 */
    @JvmStatic
    fun newSession(): Int {
        stopRequested.set(false)
        return generation.incrementAndGet()
    }

    @JvmStatic
    fun clearStop() {
        stopRequested.set(false)
    }

    @JvmStatic
    fun shouldStop(): Boolean = stopRequested.get()

    @JvmStatic
    fun sessionGen(): Int = generation.get()
}
