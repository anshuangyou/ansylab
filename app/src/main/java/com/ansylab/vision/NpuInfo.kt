package com.ansylab.vision

import android.os.Build

/**
 * 本机 Hexagon HTP 架构与 NPU 运行状态。
 *
 * QNN 运行时自己会按设备挑 skel，这里只用于界面展示和日志，
 * 不参与加载决策——加载失败会由 YoloSession 回退到 CPU。
 */
object NpuInfo {

    /** SoC 型号 → Hexagon 架构，覆盖骁龙 888 ~ 8 Elite Gen 5。 */
    private val ARCH_BY_SOC: Map<String, String> = mapOf(
        "SM8350" to "V68",   // 888 / 888+
        "SM8450" to "V69",   // 8 Gen 1
        "SM8475" to "V69",   // 8+ Gen 1
        "SM8550" to "V73",   // 8 Gen 2
        "SM8635" to "V73",   // 8s Gen 3
        "SM8650" to "V75",   // 8 Gen 3
        "SM8750" to "V79",   // 8 Elite
        "SM8850" to "V81"    // 8 Elite Gen 5
    )

    /** 本机 SoC 型号，取不到时返回空串。 */
    fun soc(): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.ifBlank { Build.HARDWARE }.uppercase()
        } else {
            Build.HARDWARE.uppercase()
        }
    } catch (_: Throwable) {
        ""
    }

    /** 期望的 Hexagon 架构，未知返回 "未知"。 */
    fun arch(): String = ARCH_BY_SOC[soc()] ?: "未知"

    /** 是否带 QNN 运行库（能跑 NPU 的前提）。 */
    fun hasQnn(): Boolean = try {
        java.io.File(PyBridge.nativeLibDir(), "libQnnHtp.so").exists()
    } catch (_: Throwable) {
        false
    }

    /**
     * 一行状态，给设置页用：
     *   "Hexagon V75（SM8650）· 已启用"
     *   "Hexagon V73（SM8550）· 已回退 CPU"
     *   "未知（SM7650）· 没有 NPU 运行库"
     */
    fun status(): String {
        val s = soc()
        val arch = ARCH_BY_SOC[s]
        if (arch == null) {
            return if (hasQnn()) "${s.ifBlank { "未知 SoC" }} · 架构未收录，尝试 NPU"
            else "${s.ifBlank { "未知 SoC" }} · 没有 NPU 运行库"
        }
        val where = "${arch}（${s}）"
        return when (YoloSession.npuState) {
            YoloSession.NPU_ON -> "$where · 已启用"
            YoloSession.NPU_FALLBACK -> "$where · 已回退 CPU"
            else -> "$where · 待运行"
        }
    }
}
