package com.ansylab.vision

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/**
 * 设备信息采集。
 *
 * NPU 相关的值全部来自本机真实环境：
 *   - Hexagon 架构：从打包进来的 libQnnHtpV*Skel.so 名字里读（v68/v69/v73/v75/v79/v81）
 *   - SoC 型号：Build.SOC_MODEL（Android 12+），没有就退回 Build.HARDWARE
 *   - VTCM / fp16 / 上下文二进制：由 SoC 映射表 + context 文件实际大小推算
 */
object DeviceInfo {

    /** 常见骁龙的 VTCM 大小（MB），查不到就是 null。 */
    private val VTCM_TABLE = mapOf(
        "SM8550" to 8, "SM8650" to 8, "SM8450" to 8, "SM8475" to 8,
        "SM8750" to 8, "SM8845" to 8, "SM8850" to 8,
        "SM8350" to 8, "SM7325" to 8, "SM7475" to 8
    )

    fun socName(ctx: Context): String {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else ""
        if (!soc.isNullOrBlank() && soc != "unknown") return soc
        return Build.HARDWARE ?: "未知"
    }

    fun hexagonArch(ctx: Context): String {
        val dir = File(ctx.applicationInfo.nativeLibraryDir)
        val arches = dir.listFiles()
            ?.mapNotNull { f ->
                Regex("libQnnHtpV(\\d+)Skel\\.so").find(f.name)?.groupValues?.get(1)
            }
            ?.distinct()
            ?.sortedBy { it.toInt() }
            ?: emptyList()
        if (arches.isEmpty()) return "未找到"
        return "v" + arches.joinToString("/v")
    }

    /** ORT 的 QNN EP 报告的 SoC 型号（本机 SM8650 是 57）。 */
    /** SoC 型号（SM8650 之类），直接给 QNN 用的字符串。 */
    fun socModelNumber(ctx: Context): String =
        NpuInfo.soc().ifBlank { socName(ctx).uppercase().ifBlank { "未知" } }

    fun vtcm(ctx: Context): String {
        val soc = socName(ctx).uppercase()
        // 各 SoC 的 VTCM 是查表估算的，仅作参考；真实值由 QNN 运行时决定
        for ((k, v) in VTCM_TABLE) if (soc.contains(k)) return "约 $v MB（参考）"
        return "未知"
    }

    /** HTP 是否真的用 fp16，取决于芯片与 QNN 版本；这里只说明「已请求」。 */
    fun fp16(): String = "已请求（由 QNN 决定）"

    /** NPU 运行状态：架构 + 是否真的用上了。 */
    fun npuStatus(): String = NpuInfo.status()

    fun memInfo(ctx: Context): Triple<Long, Long, Long> {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val total = mi.totalMem
        val avail = mi.availMem
        val used = total - avail
        return Triple(total, used, avail)
    }

    fun androidVersion() = "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）"

    fun abi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "未知"

    fun model(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /** 一个粗略的「能不能用 NPU」判断，给设置页的副标题用。 */
    fun npuAvailable(ctx: Context): Boolean =
        File(ctx.applicationInfo.nativeLibraryDir, "libQnnHtp.so").exists() &&
            hexagonArch(ctx) != "未找到"

    /** 分区总量与可用量（字节）。 */
    fun storageSpace(ctx: Context): Pair<Long, Long> {
        val stat = android.os.StatFs(Storage.root(ctx).absolutePath)
        return stat.totalBytes to stat.availableBytes
    }

    /** 摄像头清单（设置页展示用）。 */
    class CameraRow(
        val id: String,
        val facing: String,
        val focal: Double,
        val maxSize: String,
        val flash: Boolean
    )

    fun cameraList(ctx: Context): List<CameraRow> {
        val out = ArrayList<CameraRow>()
        try {
            val json = org.json.JSONArray(CameraCatalog.listJson(ctx))
            for (i in 0 until json.length()) {
                val o = json.getJSONObject(i)
                out.add(
                    CameraRow(
                        id = o.optString("id", "?"),
                        facing = o.optString("facing", "external"),
                        focal = o.optDouble("focal", 0.0),
                        maxSize = o.optString("maxSize", ""),
                        flash = o.optBoolean("flash", false)
                    )
                )
            }
        } catch (_: Throwable) {
        }
        return out
    }

    /** 一行展示：朝向 · 焦距 · 最大分辨率 · 闪光灯。 */
    fun cameraText(c: CameraRow): String {
        val facing = when (c.facing) {
            "back" -> "后置"
            "front" -> "前置"
            else -> "外接"
        }
        val flash = if (c.flash) "有闪光灯" else "无闪光灯"
        val focal = if (c.focal > 0) "%.1fmm".format(c.focal) else "-"
        val size = if (c.maxSize.isNotBlank()) c.maxSize else "-"
        return "$facing · $focal · $size · $flash"
    }
}
