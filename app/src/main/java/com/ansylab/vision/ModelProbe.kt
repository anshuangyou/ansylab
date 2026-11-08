package com.ansylab.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * 模型可用性自检：一次性判断某个 ONNX 能否被 Hexagon NPU 完整接管。
 *
 * 判断依据（比「跑起来再说」可靠）：
 *  1. 用 `disable_cpu_ep_fallback=1` 建 QNN 会话 —— 能建成功说明 QNN 认领了整张图；
 *  2. 用全零输入真正跑一次 —— QNN 的崩溃（SSR / 6033）只会在执行时暴露。
 *
 * 只用于测试，不参与业务逻辑。返回 `key=value;` 形式，方便脚本解析。
 */
object ModelProbe {

    @JvmStatic
    fun probe(path: String): String {
        val sb = StringBuilder()
        val env = OrtEnvironment.getEnvironment()
        var session: OrtSession? = null
        try {
            val options = OrtSession.SessionOptions()
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.addQnn(
                mutableMapOf(
                    "backend_path" to "${PyBridge.nativeLibDir()}/libQnnHtp.so",
                    "htp_performance_mode" to PyBridge.npuPerfMode(),
                    "enable_htp_fp16_precision" to "1",
                    "offload_graph_io_quantization" to "0",
                    "prepare_library_path" to "${PyBridge.nativeLibDir()}/libQnnHtpPrepare.so"
                )
            )
            // 关键：禁止回退。能建会话 = QNN 整图接管；否则说明会被分段（分段后大概率崩）
            options.addConfigEntry("session.disable_cpu_ep_fallback", "1")

            val t0 = System.nanoTime()
            val s = env.createSession(path, options)
            session = s
            val initMs = (System.nanoTime() - t0) / 1e6

            val inputName = s.inputInfo.keys.first()
            val info = s.inputInfo[inputName]?.info as? ai.onnxruntime.TensorInfo
            val shape = info?.shape ?: longArrayOf()
            val count = shape.fold(1L) { acc, v -> acc * v }.toInt()

            val t1 = System.nanoTime()
            val zeros = FloatArray(count.coerceAtLeast(1))
            OnnxTensor.createTensor(env, FloatBuffer.wrap(zeros), shape).use { tensor ->
                s.run(mapOf(inputName to tensor)).use { result ->
                    val n = result.size()
                    val shapes = (0 until n).joinToString("+") {
                        val ti = (result[it] as OnnxTensor).info as ai.onnxruntime.TensorInfo
                        ti.shape.joinToString(",")
                    }
                    sb.append("result=ok;")
                    sb.append("init_ms=${"%.0f".format(initMs)};")
                    sb.append("run_ms=${"%.1f".format((System.nanoTime() - t1) / 1e6)};")
                    sb.append("input=${shape.joinToString(",")};")
                    sb.append("outputs=$shapes;")
                }
            }
        } catch (t: Throwable) {
            val msg = (t.message ?: t.toString()).replace(";", ",").replace("\n", " ")
            val kind = if (session == null) "strict_fail" else "run_fail"
            sb.append("result=$kind;")
            sb.append("error=${msg.take(300)};")
        } finally {
            try {
                session?.close()
            } catch (_: Throwable) {
            }
        }
        val out = sb.toString()
        PyBridge.log("[probe] ${path.substringAfterLast('/')} $out")
        return out
    }
}
