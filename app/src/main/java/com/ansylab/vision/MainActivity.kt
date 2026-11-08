package com.ansylab.vision

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.ansylab.vision.databinding.ActivityMainBinding
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

/**
 * 只做三件事：
 *   1. 承载三个页面（主页 / 程序页 / 设置）和底部导航
 *   2. 管理相机、Python、显示缓冲这些「引擎」资源
 *   3. 把 Python 画好的帧转交给当前可见的程序页（DisplaySink）
 *
 * 页面本身在 HomeFragment / AppFragment / SettingsFragment。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var camera: CameraController

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前可见的程序页；没有时为 null，帧直接丢弃。 */
    private var sink: DisplaySink? = null

    private var displayBitmap: Bitmap? = null
    private var worker: Thread? = null

    /** 崩溃记录文件名。 */
    private val CRASH_FILE = "crash_last.txt"

    /** 当前正在跑的脚本绝对路径 / 最近一次启动来源（防重复启动用）。 */
    private var runningPath: String? = null
    private var lastStartFrom: String = ""

    @Volatile
    private var presentScheduled = false

    @Volatile
    private var lastDisplaySeq = -1L

    private var hwBitmaps: Array<Bitmap>? = null
    private var hwWidth = 0
    private var hwHeight = 0

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted: Boolean ->
            // 相机由脚本的 Camera.open() 启动；这里只负责拿到权限
            if (granted) {
                log("[app] 相机权限已授予")
            } else {
                log("[app] 没有相机权限，ansylab.camera 读不到画面")
            }
        }

    override fun attachBaseContext(newBase: android.content.Context) {
        // 先按用户选的语言包一层，再让系统加载资源
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android 15+ 强制边到边，setDecorFitsSystemWindows(true) 不再生效，
        // 内容会画到状态栏/手势条下面（底部「控件」按钮点不到）。
        // 这里手动补系统栏留白。
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        camera = CameraController(applicationContext, this)
        PyBridge.attach(this, camera)

        installCrashHandler()
        checkLastCrash()
        // 触发一次目录准备（顺带清掉老版本留下的 qnn_ctx_*.onnx）
        Storage.ensure(this)

        setupNav()
        if (savedInstanceState == null) showHome()

        logPythonVersion()
        logNativeVersion()
        ensureCameraPermission()
        handleLaunchIntent(intent)
    }

    // ------------------------------------------------------------------ 导航

    private fun setupNav() {
        binding.tabApps.setOnClickListener { showHome() }
        binding.tabSettings.setOnClickListener { showSettings() }
    }

    fun showHome() {
        stopWorkerAsync(pageToken)
        supportFragmentManager.beginTransaction()
            .replace(R.id.navHost, HomeFragment())
            .commitNow()
        setTab(settings = false)
        setBottomNavVisible(true)
    }

    // ---------------------------------------------------------------- 崩溃处理

    /**
     * 未捕获异常落盘到 logs/crash_last.txt。
     *
     * 只保留最后一次：崩溃后重启一次就能看到，不会越积越多。
     * 写完后把异常交回系统默认处理器，保证行为和原来一致（对话框/杀进程）。
     */
    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val f = java.io.File(Storage.logs(this), CRASH_FILE)
                val sw = java.io.StringWriter()
                error.printStackTrace(java.io.PrintWriter(sw))
                val text = buildString {
                    append("time: ").append(java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", java.util.Locale.US
                    ).format(java.util.Date())).append('\n')
                    append("thread: ").append(thread.name).append('\n')
                    append("device: ").append(DeviceInfo.model()).append('\n')
                    append("android: ").append(DeviceInfo.androidVersion()).append('\n')
                    append("app: ").append(appVersionSafe()).append('\n')
                    append("\n").append(sw.toString())
                }
                f.writeText(text)
                log("[crash] 已记录到 " + f.absolutePath)
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(thread, error)
        }
    }

    /** 上次是否异常退出：是就弹提示，可直接跳日志页。 */
    private fun checkLastCrash() {
        val f = java.io.File(Storage.logs(this), CRASH_FILE)
        if (!f.exists()) return
        val head = try {
            f.readLines().filter { it.isNotBlank() }.take(6).joinToString("\n")
        } catch (_: Throwable) {
            ""
        }
        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.crash_title)
                .setMessage(head.ifEmpty { getString(R.string.crash_empty) })
                .setPositiveButton(R.string.crash_view) { _, _ ->
                    f.delete()
                    showLogs()
                }
                .setNegativeButton(R.string.crash_dismiss) { _, _ ->
                    f.delete()
                }
                .show()
        }, 400)
    }

    private fun appVersionSafe(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    /** 打开日志页。 */
    fun showLogs() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.navHost, LogFragment())
            .commitNow()
        setTab(settings = true)
        setBottomNavVisible(false)
    }

    /** 脚本异常：显示在当前程序页上。 */
    fun postScriptError(text: String) {
        runOnUiThread {
            log("[py] 脚本异常: " + text.lineSequence().lastOrNull { it.isNotBlank() })
            val f = supportFragmentManager.findFragmentById(R.id.navHost) as? AppFragment
            f?.showScriptError(text)
        }
    }

    fun showSettings() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.navHost, SettingsFragment())
            .commitNow()
        setTab(settings = true)
        setBottomNavVisible(true)
    }

    /** 程序页不显示底部导航，只靠返回键回上一级。 */
    private fun setBottomNavVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        binding.bottomNav.visibility = v
        binding.navDivider.visibility = v
    }

    private fun setTab(settings: Boolean) {
        val active = ContextCompat.getColor(this, R.color.ink)
        val idle = ContextCompat.getColor(this, R.color.ink_secondary)
        binding.tabAppsLabel.setTextColor(if (settings) idle else active)
        binding.tabSettingsLabel.setTextColor(if (settings) active else idle)
    }

    /** 打开一个程序：进程序页并自动运行。 */
    fun openApp(app: AppEntry) {
        // 防重复：同一个程序已经在显示就不要再 replace 一次。
        // am start 可能先 onCreate 后 onNewIntent，各调一次 openApp，
        // 于是出现两个 AppFragment，各自 onStart 各跑一份脚本。
        val shown = supportFragmentManager.findFragmentById(R.id.navHost) as? AppFragment
        if (shown != null && shown.currentAppName == app.name) {
            log("[page] 已在 ${app.name}，忽略重复打开")
            return
        }
        val fragment = AppFragment.newInstance(app.name, null)
        supportFragmentManager.beginTransaction()
            .replace(R.id.navHost, fragment)
            .commitNow()
        setTab(settings = false)
        setBottomNavVisible(false)
    }

    /** 兼容 ADB 通道：直接给一个 .py 路径（不在 apps/ 里）。 */
    fun openScript(path: String) {
        val fragment = AppFragment.newInstance(null, path)
        supportFragmentManager.beginTransaction()
            .replace(R.id.navHost, fragment)
            .commitNow()
        setTab(settings = false)
        setBottomNavVisible(false)
    }

    // -------------------------------------------------------------- 页面回调

    fun registerSink(s: DisplaySink) {
        sink = s
        if (hwBitmaps != null && hwWidth > 0 && hwHeight > 0) {
            s.onDisplayReady(hwWidth, hwHeight)
        }
    }

    fun unregisterSink(s: DisplaySink) {
        if (sink === s) sink = null
    }

    /** 程序页会话令牌：每次进页面 +1，用来丢弃「旧页面迟到的 stop」。 */
    private var pageToken = 0

    fun startApp(app: AppEntry?, scriptPath: String?): Int {
        stopWorker(500)

        val token = ++pageToken
        log("[page] start token=$token")

        val script = when {
            app?.script != null -> app.script
            scriptPath != null -> resolveScript(scriptPath)
            else -> null
        }
        if (script == null) {
            log("[app] 这个程序里没有 .py 文件")
            return token
        }
        runScript(script)
        return token
    }

    /**
     * 相机不再由 Activity 提前启动，交给脚本的 `ansylab.Camera.open()`。
     *
     * 不能在启动脚本前先 startCamera()：旧脚本退出时的 `stopCamera()`
     * 看到的仍是「当前代」，会把刚启动的新相机一起关掉，
     * 表现为新会话读一帧之后就 `seq=0` 超时。
     */
    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * 程序页离开时调用。
     *
     * 快速开关时，旧页面的 onStop 可能晚于新页面的 onStart 执行；如果无条件
     * 关相机，就会把新会话刚起来的相机和帧池一起清掉——新脚本随即读帧超时
     * （native 侧能看到 frameSeq 被重置成 0）。所以带令牌比对。
     */
    fun stopApp(token: Int) {
        if (token != pageToken) {
            log("[page] stop token=$token 已过期（当前=$pageToken），忽略")
            return
        }
        log("[page] stop token=$token 生效")
        stopWorkerAsync(token)
    }

    /**
     * 停脚本但不阻塞主线程。
     *
     * 脚本正常会在几十毫秒内退出，但万一卡在推理里，主线程 join 会直接冻住界面。
     * 这里把等待放到后台线程，等它真正退出后再关相机/串口。
     * 用 pageToken 兜底：等的不耐烦时用户已经开了新程序，就不能再关新会话的相机。
     */
    private fun stopWorkerAsync(token: Int) {
        val t = worker
        worker = null
        runningPath = null
        PyBridge.requestStop()
        if (t == null || !t.isAlive) {
            cleanupAfterStop(token)
            return
        }
        Thread {
            t.join(1500)
            mainHandler.post { cleanupAfterStop(token) }
        }.start()
    }

    private fun cleanupAfterStop(token: Int) {
        if (token != pageToken) {
            log("[page] 停止清理已过期（当前=$pageToken），跳过关相机")
            return
        }
        camera.stop()
        // 脚本被强制结束时可能来不及关串口；这里兜底，
        // 否则下一个程序打开同一个串口会冲突。
        SerialPortManager.close()
        resetDisplay()
    }

    /**
     * 清掉显示侧缓存的硬件缓冲。
     *
     * NativeCamera.reset() 会释放 native 的 AHardwareBuffer 引用，但这里的
     * hwBitmaps 还留着旧对象；不一起清的话 prepareDisplay() 会走「尺寸相同直接复用」
     * 的短路，把画面画进已经释放的缓冲 —— 表现就是切相机后彻底黑屏。
     */
    fun resetDisplay() {
        hwBitmaps = null
        hwWidth = 0
        hwHeight = 0
        displayBitmap = null
        lastDisplaySeq = -1L
        presentScheduled = false
    }

    // ---------------------------------------------------------------- 跑脚本

    private fun runScript(script: File) {
        // 上一次的脚本可能还在退出（它在下一次 read()/imshow() 才看到停止标记）。
        // 等它一下，并且换一个会话代号：即使它没及时退出，也会发现代号变了而停下。
        worker?.let { old ->
            if (old.isAlive) {
                // 防重复启动：同一个脚本已经在跑就别再起一个线程。
                // am start 可能触发两次 openApp（onCreate + onNewIntent），
                // 不拦的话同一脚本会跑两份，串口/相机互相抢占。
                if (runningPath == script.absolutePath) {
                    log("[app] 已在运行 ${script.name}，忽略重复启动（$lastStartFrom）")
                    return
                }
                PyBridge.requestStop()
                old.join(500)
            }
        }

        PyBridge.newSession()
        runningPath = script.absolutePath
        lastStartFrom = callerTrace()
        log("[app] 开始运行 ${script.name}")
        worker = Thread({
            try {
                Python.getInstance().getModule("runner").callAttr("run_file", script.absolutePath)
                log("[app] 脚本结束")
            } catch (t: Throwable) {
                log("[app] 运行异常: ${t.message}")
            }
        }, "ansylab-python").also { it.start() }
    }

    /** 记录启动来源，重复启动时打出来方便定位。 */
    private fun callerTrace(): String = Throwable().stackTrace
        .drop(2)
        .take(4)
        .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }

    private fun stopWorker(timeoutMs: Long = 1500) {
        val t = worker
        worker = null
        runningPath = null
        PyBridge.requestStop()
        if (t != null && t.isAlive) {
            // 必须等脚本线程真正退出，再让调用方去关相机/释放 native 缓冲。
            // 否则脚本可能还停在 model.detect(frame) 里，读的却是已经被 reset
            // 释放掉的相机内存 —— 画面上会出现满屏噪声、几百个 0.00 的框。
            // 脚本里的等待循环都会检查停止标志，正常情况下几十毫秒就能退出。
            t.join(timeoutMs)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 已经在前台时再来一条 am start（带上 -e app / -e script），也要能切页
        handleLaunchIntent(intent)
    }

    // ---------------------------------------------------------------- intent

    /**
     * ADB / 上位机通道：
     *   -e app <文件夹名>         打开 apps/<文件夹名>
     *   -e script <绝对路径>      直接跑某个 .py
     *   -e importZip <绝对路径>   导入一个 project zip（等同主页「导入项目压缩包」）
         *   -e autorun true           打开后立即运行
     */
    private fun handleLaunchIntent(intent: Intent?) {
        val zipPath = intent?.getStringExtra("importZip")
        if (zipPath != null) {
            mainHandler.post {
                val f = java.io.File(zipPath)
                log("[app] 导入源 ${f.absolutePath} exists=${f.exists()} size=${if (f.exists()) f.length() else -1}")
                val entry = AppRepository.importZipFile(this, f)
                log("[app] 导入 ${entry?.name ?: "失败"}")
                showHome()
            }
            return
        }

        val appName = intent?.getStringExtra("app")
        val scriptPath = intent?.getStringExtra("script")
        if (appName == null && scriptPath == null) return

        val autorun = intent?.getBooleanExtra("autorun", false) == true ||
            intent?.getStringExtra("autorun")?.toBoolean() == true
        mainHandler.post {
            if (appName != null) {
                val app = AppRepository.find(this, appName)
                if (app != null) {
                    openApp(app)
                    if (autorun) startApp(app, null)
                } else {
                    log("[app] 找不到程序 $appName")
                }
            } else if (scriptPath != null) {
                openScript(scriptPath)
                if (autorun) startApp(null, scriptPath)
            }
        }
    }

    private fun resolveScript(rawPath: String): File? {
        val direct = File(rawPath)
        if (direct.isFile) return direct
        val name = direct.name
        return listOf(
            File(Storage.scripts(this), name),
            File(Storage.ensure(this), name),
            File(filesDir, name),
            getExternalFilesDir(null)?.let { File(it, name) }
        ).firstOrNull { it != null && it.isFile }
    }

    // -------------------------------------------------------- PyBridge 回调

    fun onPresented() {
        if (hwBitmaps != null) {
            NativeCamera.endDraw()
        } else {
            NativeCamera.markPresented()
        }
        onNativeFrameReady()
    }

    fun prepareDisplay(width: Int, height: Int): Boolean {
        if (hwBitmaps != null && hwWidth == width && hwHeight == height) {
            sink?.onDisplayReady(width, height)
            return true
        }
        if (width <= 0 || height <= 0) return false

        return try {
            val usage = HardwareBuffer.USAGE_CPU_WRITE_OFTEN or
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
            val bufferA = HardwareBuffer.create(width, height, HardwareBuffer.RGBA_8888, 1, usage)
            val bufferB = HardwareBuffer.create(width, height, HardwareBuffer.RGBA_8888, 1, usage)
            val colorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            val bitmapA = Bitmap.wrapHardwareBuffer(bufferA, colorSpace)
            val bitmapB = Bitmap.wrapHardwareBuffer(bufferB, colorSpace)
            if (bitmapA == null || bitmapB == null) {
                log("[app] wrapHardwareBuffer 返回 null，退回软件显示")
                return false
            }
            if (!NativeCamera.setDisplayBuffers(bufferA, bufferB, width, height)) {
                log("[app] native 绑定硬件缓冲失败，退回软件显示")
                return false
            }
            hwBitmaps = arrayOf(bitmapA, bitmapB)
            hwWidth = width
            hwHeight = height
            displayBitmap = null
            lastDisplaySeq = -1L
            sink?.onDisplayReady(width, height)
            true
        } catch (t: Throwable) {
            log("[app] 硬件显示不可用: $t")
            false
        }
    }

    fun onNativeFrameReady() {
        if (presentScheduled) return
        presentScheduled = true
        mainHandler.post {
            presentScheduled = false
            drawNativeFrame()
        }
    }

    private fun drawNativeFrame() {
        val target = sink ?: return
        val seq = NativeCamera.displaySeq()
        if (seq == lastDisplaySeq) return
        lastDisplaySeq = seq

        val width = NativeCamera.displayWidth()
        val height = NativeCamera.displayHeight()
        if (width <= 0 || height <= 0) return

        val bitmaps = hwBitmaps
        if (bitmaps != null) {
            target.onFrame(bitmaps[NativeCamera.frontIndex()])
            return
        }

        var bitmap = displayBitmap
        if (bitmap == null || bitmap.width != width || bitmap.height != height) {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            displayBitmap = bitmap
        }
        val buffer = NativeCamera.displayBuffer() ?: return
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        target.onFrame(bitmap)
    }

    /** PyBridge / native 的日志都走这里，转给当前页面显示一行，同时落盘。 */
    fun log(text: String) {
        val line = text.trim()
        if (line.isEmpty()) return
        PyBridge.logFile(line)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            sink?.onLog(line)
        } else {
            mainHandler.post { sink?.onLog(line) }
        }
    }

    // ---------------------------------------------------------------- 杂项

    private external fun nativeVersion(): String

    private fun logNativeVersion() {
        try {
            log("[app] JNI " + nativeVersion() + " / Python 就绪")
        } catch (t: Throwable) {
            log("[app] JNI 加载失败: $t")
        }
    }

    private fun logPythonVersion() {
        try {
            val version = Python.getInstance().getModule("platform")
                .callAttr("python_version").toString()
            log("[app] Python $version")
        } catch (t: Throwable) {
            log("[app] Python 启动失败: $t")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        PyBridge.requestStop()
        camera.stop()
    }

    companion object {

        init {
            System.loadLibrary("ansylab")
        }
    }
}
