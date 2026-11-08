package com.ansylab.vision

import android.content.Context
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * CameraX 取帧器（只有 ImageAnalysis，不再绑定 Preview）。
 *
 * 方向：`setTargetRotation(ROTATION_0)` + `setOutputImageRotationEnabled(true)`，
 * 让 CameraX 按竖屏把画面转正；个别机型/预览尺寸不支持时，rotationDegrees 会非 0，
 * Python 侧再按这个值转一次（见 ansylab.Frame）。
 */
@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
@android.annotation.SuppressLint("StaticFieldLeak")  // 传入的是 applicationContext
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private val analyzerExecutor = Executors.newSingleThreadExecutor()

    /**
     * 帧投递与 reset 之间的闸门。
     *
     * stop() 拿写锁再调 NativeCamera.reset()，保证清理时没有 onFrame 正在往 native
     * 里塞 ImageProxy；否则会出现「proxy 塞进去后没人 close」——CameraX 不再出帧，
     * 表现就是多次开关后偶发卡死。
     */
    private val frameGate = ReentrantReadWriteLock()

    /**
     * 启停代次。每次 start()/stop() 都 +1：
     * provider 是异步拿到的，如果在等待期间又发生了一次 stop→start，
     * 先前那次回调就不能再 bind，否则会把新会话的相机状态搞乱。
     */
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)

    private var provider: ProcessCameraProvider? = null

    /** bind 之后拿到的 CameraX Camera，闪光灯要靠它控制。 */
    private var boundCamera: androidx.camera.core.Camera? = null

    @Volatile
    private var running = false

    /** 期望的相机帧率；0 表示用系统默认（通常 30）。 */
    @Volatile
    private var requestedFps: Int = 0

    /** 当前使用的 camera id；null 表示用默认后置主摄。 */
    @Volatile
    private var requestedId: String? = null

    companion object {
        /** 期望的对焦模式（none/auto/continuous/infinity），由设置页决定。 */
        @Volatile
        private var desiredFocus: String = "continuous"

        /** 设置对焦模式；相机已在运行时立即生效。 */
        fun applyFocus(mode: String): String {
            desiredFocus = when (mode) {
                "auto", "continuous", "infinity", "none" -> mode
                else -> "continuous"
            }
            return ""
        }

        /** 当前实例（用于运行中切对焦）。 */
        @Volatile
        var active: CameraController? = null
            private set
    }

    @Synchronized
    fun start(
        requestWidth: Int, requestHeight: Int, cameraId: String? = null, fps: Int = 0
    ) {
        active = this
        requestedId = cameraId
        requestedFps = fps
        if (running) {
            PyBridge.log("[camera] 已在运行，忽略重复启动")
            return
        }
        running = true
        val token = generation.incrementAndGet()

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            // 期间又发生过 stop/start：这次回调已经过期，不能 bind。
            if (!running || token != generation.get()) {
                PyBridge.log("[camera] 启动回调已过期（token=$token gen=${generation.get()}）")
                return@addListener
            }
            try {
                val cameraProvider = future.get()
                provider = cameraProvider

                // 用 ResolutionSelector 明确要 4:3 + 请求的分辨率。
                // 不能用已废弃的 setTargetResolution：部分机型会返回正方形帧，
                // 既费带宽又不好用。
                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(requestWidth, requestHeight),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()

                val analysisBuilder = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setTargetRotation(Surface.ROTATION_0)
                    .setOutputImageRotationEnabled(true)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)

                // 对焦模式（由脚本 Camera(focus=...) 指定）：bind 前用 Camera2 互操作设置
                try {
                    val af = when (desiredFocus) {
                        "auto" -> android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_AUTO
                        "infinity", "none" ->
                            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_OFF
                        else ->
                            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    }
                    val ext = androidx.camera.camera2.interop.Camera2Interop
                        .Extender(analysisBuilder)
                    ext.setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE, af
                    )
                    // 帧率：0 表示不干预，其余请求固定区间（设备不支持会被忽略）
                    if (requestedFps > 0) {
                        ext.setCaptureRequestOption(
                            android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            android.util.Range(requestedFps, requestedFps)
                        )
                    }
                } catch (_: Throwable) {
                }
                val analysis = analysisBuilder.build()
                analysis.setAnalyzer(analyzerExecutor) { image -> onFrame(image) }

                cameraProvider.unbindAll()
                val selector = buildSelector(cameraProvider, requestedId)
                boundCamera =
                    cameraProvider.bindToLifecycle(lifecycleOwner, selector, analysis)
                PyBridge.log(
                    "[camera] 已启动 ${requestWidth}x$requestHeight id=${requestedId ?: "默认"}" +
                        " fps=${if (requestedFps > 0) requestedFps.toString() else "默认"}（native 帧池）"
                )

                // 看门狗：快速开关时 CameraX 偶尔会出现「绑定成功但不出帧」。
                // 2.5 秒内一帧都没来就解绑重绑一次，比让脚本一直读超时好。
                val baseline = NativeCamera.frameSeq()
                ContextCompat.getMainExecutor(context).execute {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (running && token == generation.get() &&
                            NativeCamera.frameSeq() == baseline
                        ) {
                            PyBridge.log("[camera] 2.5s 没有帧，重新绑定一次")
                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    buildSelector(cameraProvider, requestedId),
                                    analysis
                                )
                            } catch (t: Throwable) {
                                PyBridge.log("[camera] 重新绑定失败: $t")
                            }
                        }
                    }, 2500)
                }
            } catch (t: Throwable) {
                running = false
                PyBridge.log("[camera] 启动失败: $t")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        running = false
        boundCamera = null
        generation.incrementAndGet()   // 让还没执行的启动回调作废
        try {
            provider?.unbindAll()
        } catch (_: Throwable) {
            // 退出时忽略解绑异常
        }
        provider = null
        PyBridge.log("[camera] 已停止（gen=${generation.get()}）")

        // 等分析线程里正在处理的那一帧走完，再清 native 状态
        frameGate.writeLock().lock()
        try {
            NativeCamera.reset()
        } finally {
            frameGate.writeLock().unlock()
        }
        // native 的硬件显示缓冲已经被 reset 释放，UI 侧的 hwBitmaps 也必须一起清，
        // 否则下次 prepareDisplay() 会认为缓冲还可用，直接画到已释放的内存上（黑屏）。
        PyBridge.resetDisplayState()
    }

    private fun onFrame(image: ImageProxy) {
        frameGate.readLock().lock()
        try {
            // 已经停止（解绑后可能还会漏进来一两帧）：直接还给相机，
            // 绝不能进 native，否则 reset 之后没人 close 它。
            if (!running) {
                image.close()
                return
            }
            val plane = image.planes[0]
            if (plane.pixelStride != 4) {
                PyBridge.log("[camera] 意外的 pixelStride=${plane.pixelStride}，跳过该帧")
                image.close()
                return
            }
            // 借给 native：成功后由 native 在 Python 读完时 close，
            // 这里不能 close，否则正在被读的 buffer 会被回收。
            NativeCamera.pushFrame(
                image,
                plane.buffer,
                plane.rowStride,
                image.width,
                image.height,
                image.imageInfo.rotationDegrees
            )
        } catch (t: Throwable) {
            PyBridge.log("[camera] 处理帧失败: $t")
            try {
                image.close()
            } catch (_: Throwable) {
                // 忽略：可能已经被 native 关闭
            }
        } finally {
            frameGate.readLock().unlock()
        }
    }

    fun isRunning(): Boolean = running

    /** 当前相机是否带闪光灯。 */
    fun hasFlash(): Boolean = boundCamera?.cameraInfo?.hasFlashUnit() == true

    /**
     * 开关闪光灯。
     *
     * 必须走 CameraX 的 cameraControl：相机已被 CameraX 占用时，
     * 不能用 CameraManager.setTorchMode：相机已占用时会报 CAMERA_IN_USE。
     */
    fun setTorch(on: Boolean): String {
        val cam = boundCamera ?: return "相机还没启动"
        if (cam.cameraInfo.hasFlashUnit() != true) return "当前相机没有闪光灯"
        return try {
            cam.cameraControl.enableTorch(on)
            ""
        } catch (t: Throwable) {
            "闪光灯切换失败: ${t.message}"
        }
    }

    /**
     * 按 camera id 构造选择器；id 为空或找不到就退回默认后置主摄。
     *
     * Camera2CameraInfo 能拿到原始 id，比按焦距猜可靠。
     */
    private fun buildSelector(
        provider: ProcessCameraProvider,
        id: String?
    ): CameraSelector {
        if (id == null) return CameraSelector.DEFAULT_BACK_CAMERA
        val exists = provider.availableCameraInfos.any { info ->
            runCatching { Camera2CameraInfo.from(info).cameraId == id }.getOrDefault(false)
        }
        if (!exists) {
            PyBridge.log("[camera] 找不到 camera id=，退回默认后置")
            CameraCatalog.currentId = null
            return CameraSelector.DEFAULT_BACK_CAMERA
        }
        CameraCatalog.currentId = id
        return CameraSelector.Builder()
            .addCameraFilter { infos ->
                infos.filter { info ->
                    runCatching { Camera2CameraInfo.from(info).cameraId == id }
                        .getOrDefault(false)
                }
            }
            .build()
    }

}
