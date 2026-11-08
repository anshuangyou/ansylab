package com.ansylab.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 用 ONNX Runtime 的 QNN Execution Provider 在 Hexagon HTP 上跑 YOLO。
 *
 * 和「离线 qnn-onnx-converter」不同，QNN EP 会在设备上把 ONNX 子图编译成 QNN 图
 * 再交给 HTP 执行，所以只要把 onnx 模型推到手机即可。
 *
 * 依赖：
 *  - jniLibs/arm64-v8a 里的 libQnnHtp.so / libQnnSystem.so
 *    以及 V68/V69/V73/V75/V79/V81 六档 Skel/Stub（覆盖骁龙 888 ~ 8 Elite Gen 5）
 *  - useLegacyPackaging=true，skel 必须解压到磁盘
 *
 * 不做 context 缓存：每次加载都在设备上重新编译（约 1.5–2 秒），
 * 换取「换芯片/换模型不会读到不匹配的缓存」。
 */
object YoloSession {

    /** 排查 EP 分配时置 true（会生成约 1.6 MB 的 profile json）。 */
    private const val PROFILE = false

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName: String = "images"
    private var inputShape: LongArray = longArrayOf(1, 3, 640, 640)
    private var outputShape: LongArray = longArrayOf(1, 84, 8400)

    @Volatile
    var lastError: String = ""

    /** NPU 状态：未跑 / 已启用 / 已回退 CPU。 */
    const val NPU_IDLE = 0
    const val NPU_ON = 1
    const val NPU_FALLBACK = 2

    @Volatile
    var usingNpu: Boolean = false

    /** 当前 NPU 状态，供设置页显示。 */
    @Volatile
    var npuState: Int = NPU_IDLE

    /** 上次建会话耗时（毫秒）。 */
    @Volatile
    var lastInitMs: Double = 0.0

    /**
     * 模型自带的类名（ONNX metadata 的 `names`），按索引排好。
     *
     * 不内置任何 COCO 表：类名、类别数、输入尺寸都以模型为准，
     * 换成自定义模型时这里自动就是新模型的类。
     */
    @Volatile
    var modelNames: List<String> = emptyList()
        private set

    /** 输出里的类别数（shape[1] - 4），取不到时退回 modelNames 的数量。 */
    @Volatile
    var numClasses: Int = 0
        private set

    /** 模型 metadata 里的输入尺寸，取不到返回 0。 */
    @Volatile
    var modelImgsz: Int = 0
        private set

    private fun nativeLibDir(): String = PyBridge.nativeLibDir()

    @Volatile
    private var currentModelPath: String = ""

    @Synchronized
    fun create(modelPath: String, useNpu: Boolean = true): Boolean {
        close()
        currentModelPath = modelPath
        lastError = ""

        if (useNpu) {
            if (tryCreate(modelPath, npu = true)) {
                usingNpu = true
                npuState = NPU_ON
                PyBridge.log("[yolo] ep=QNN/HTP 架构=${NpuInfo.arch()} 建会话 ${"%.0f".format(lastInitMs)} ms")
                return true
            }
            // NPU 起不来（架构不匹配 / 驱动缺失 / 图不支持）：回退 CPU 保证脚本还能跑
            PyBridge.log("[yolo] NPU 初始化失败，回退 CPU：$lastError")
            close()
            if (tryCreate(modelPath, npu = false)) {
                usingNpu = false
                npuState = NPU_FALLBACK
                PyBridge.log("[yolo] ep=CPU 建会话 ${"%.0f".format(lastInitMs)} ms（已回退）")
                lastError = ""
                return true
            }
            npuState = NPU_FALLBACK
            return false
        }

        if (tryCreate(modelPath, npu = false)) {
            usingNpu = false
            npuState = NPU_IDLE
            PyBridge.log("[yolo] ep=CPU 建会话 ${"%.0f".format(lastInitMs)} ms")
            return true
        }
        return false
    }

    /** 真正建会话；npu=true 时挂 QNN EP。失败返回 false，错误留在 lastError。 */
    private fun tryCreate(modelPath: String, npu: Boolean): Boolean = try {
        val environment = OrtEnvironment.getEnvironment()
        env = environment

        val options = OrtSession.SessionOptions()
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        if (PROFILE) {
            options.enableProfiling(
                "${PyBridge.appFilesDir()}/yolo_profile_${if (npu) "npu" else "cpu"}.json"
            )
        }

        if (npu) {
            // backend 指向随 APK 打包的 libQnnHtp.so；它按设备 DSP 架构自己挑 skel
            options.addQnn(
                mutableMapOf(
                    "backend_path" to "${nativeLibDir()}/libQnnHtp.so",
                    "htp_performance_mode" to PyBridge.npuPerfMode(),
                    "enable_htp_fp16_precision" to "1",
                    "offload_graph_io_quantization" to "0",
                    "prepare_library_path" to "${nativeLibDir()}/libQnnHtpPrepare.so"
                )
            )
            // 禁止静默回退到 CPU：否则 QNN 失败时 ORT 会悄悄把整个图落到 CPU，
            // 表现为「能跑」但根本没用到 NPU。失败由外层接住再显式回退。
            options.addConfigEntry("session.disable_cpu_ep_fallback", "1")
        }

        val t0 = System.nanoTime()
        val s = environment.createSession(modelPath, options)
        lastInitMs = (System.nanoTime() - t0) / 1_000_000.0
        session = s

        val firstInput = s.inputInfo.keys.first()
        inputName = firstInput
        (s.inputInfo[firstInput]?.info as? ai.onnxruntime.TensorInfo)?.shape?.let {
            inputShape = it
        }
        val firstOutput = s.outputInfo.keys.first()
        (s.outputInfo[firstOutput]?.info as? ai.onnxruntime.TensorInfo)?.shape?.let {
            outputShape = it
        }

        // 类名 / 类别数 / imgsz 一律从模型读，不在代码里写死
        val meta = try {
            s.metadata?.customMetadata ?: emptyMap()
        } catch (_: Throwable) {
            emptyMap()
        }
        modelNames = parseNames(meta["names"])
        numClasses = if (outputShape.size >= 2) {
            (outputShape[1] - 4).toInt().coerceAtLeast(0)
        } else {
            modelNames.size
        }
        modelImgsz = parseImgsz(meta["imgsz"])
        if (npu) {
            PyBridge.log(
                "[yolo] 模型元数据：classes=$numClasses names=${modelNames.size} " +
                    "imgsz=${if (modelImgsz > 0) modelImgsz.toString() else "默认"}"
            )
        }
        true
    } catch (t: Throwable) {
        lastError = t.message ?: t.toString()
        close()
        false
    }

    /** 解析 Ultralytics 写进 metadata 的 `{0: 'person', 1: 'car', ...}`。 */
    private fun parseNames(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val re = Regex("""(\d+)\s*:\s*['"]([^'"]*)['"]""")
        val found = re.findAll(raw)
            .mapNotNull { m -> m.groupValues[1].toIntOrNull()?.let { it to m.groupValues[2] } }
            .sortedBy { it.first }
            .toList()
        if (found.isEmpty()) return emptyList()
        // 索引不连续时按最大值补齐，避免下游按索引取值越界
        val max = found.last().first
        val out = MutableList(max + 1) { "class_$it" }
        found.forEach { (i, n) -> out[i] = n }
        return out
    }

    /** 解析 `[640, 640]` 这类 imgsz。 */
    private fun parseImgsz(raw: String?): Int {
        if (raw.isNullOrBlank()) return 0
        return Regex("""\d+""").find(raw)?.value?.toIntOrNull() ?: 0
    }

    fun info(): String {
        val shape = inputShape.joinToString(",")
        val oshape = outputShape.joinToString(",")
        val ep = if (usingNpu) "QNN/HTP" else "CPU"
        val arch = if (usingNpu) " arch=${NpuInfo.arch()}" else ""
        return "ep=$ep$arch init=%.0fms input=$inputName[$shape] output=[$oshape]"
            .format(lastInitMs)
    }

    /** 输入是已经预处理好的 float32 [1,3,H,W]（NCHW, RGB, 0..1）。 */
    @Synchronized
    fun run(input: ByteArray): ByteArray? {
        val fb: FloatBuffer = ByteBuffer.wrap(input)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        return runInternal(fb)
    }

    /**
     * 从 native 输入缓冲直接推理：Python 已经把预处理结果写在 [NativeCamera.allocInput]
     * 返回的那块内存里，这里只建一个视图，不再跨语言搬 4.9 MB。
     */
    @Synchronized
    fun runFromNative(): ByteArray? {
        if (inputShape.isEmpty() || inputShape.any { it <= 0 }) return null
        val count = inputShape.fold(1L) { acc, v -> acc * v }.toInt()
        if (count <= 0) return null
        val buffer = NativeCamera.inputBuffer(count) ?: return null
        buffer.rewind()
        val fb = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
        return runInternal(fb)
    }

    private fun runInternal(fb: FloatBuffer): ByteArray? {
        val s = session ?: return null
        val e = env ?: return null
        return try {
            OnnxTensor.createTensor(e, fb, inputShape).use { tensor ->
                s.run(mapOf(inputName to tensor)).use { result ->
                    val out = result[0] as OnnxTensor
                    val buffer = out.floatBuffer
                    val floats = FloatArray(buffer.remaining())
                    buffer.get(floats)

                    val bytes = ByteArray(floats.size * 4)
                    ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer().put(floats)
                    bytes
                }
            }
        } catch (t: Throwable) {
            lastError = t.message ?: t.toString()
            PyBridge.log("[yolo] 推理失败: $lastError")
            null
        }
    }

    @Synchronized
    fun close() {
        try {
            session?.close()
        } catch (_: Throwable) {
        }
        session = null
        // 会话已关，状态回到「未运行」；否则设置页会一直显示「已启用」
        npuState = NPU_IDLE
        // 清掉形状，避免会话创建失败后 runFromNative() 还按旧尺寸算
        inputShape = longArrayOf()
        outputShape = longArrayOf()
    }
}
