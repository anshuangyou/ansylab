package com.ansylab.vision

import android.content.Context
import java.io.File

/**
 * 统一的数据根目录。
 *
 * 所有内容都放在一个根目录下：
 *   <root>/apps/<程序名>/     通过三种方式导入的程序
 *   <root>/logs/              运行日志（last_run.log / native.log）
 *   <root>/scripts/           adb 通道下发的临时脚本
 *   <root>/settings.json      配置信息
 *   <root>/yolo_profile_*.json 推理耗时统计
 *
 * 根目录可选「内部存储」（跟随 App 私有目录）或「外部存储」
 * （/sdcard/Android/data/<包名>/files/ansylab，可用文件管理器/USB 访问）。
 *
 * 换位置时会把旧目录里的所有内容整体搬到新目录。
 */
object Storage {

    const val LOCATION_INTERNAL = "internal"
    const val LOCATION_EXTERNAL = "external"

    private const val PREF = "ansylab_prefs"
    private const val KEY_LOCATION = "storage_location"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun location(ctx: Context): String =
        prefs(ctx).getString(KEY_LOCATION, LOCATION_INTERNAL) ?: LOCATION_INTERNAL

    fun isExternal(ctx: Context) = location(ctx) == LOCATION_EXTERNAL

    /** 外部存储是否可用（没插 SD / 未挂载时为 false）。 */
    /** 根目录所在的父目录。 */
    private fun base(ctx: Context): File =
        if (isExternal(ctx)) ctx.getExternalFilesDir(null) ?: ctx.filesDir else ctx.filesDir

    fun root(ctx: Context): File = File(base(ctx), "ansylab")

    fun apps(ctx: Context): File = File(root(ctx), "apps").apply { mkdirs() }

    fun logs(ctx: Context): File = File(root(ctx), "logs").apply { mkdirs() }

    fun scripts(ctx: Context): File = File(root(ctx), "scripts").apply { mkdirs() }

    /**
     * 确保根目录存在，并把散落在 filesDir 根下的旧目录并入新根目录。
     * 每次取路径前都会调用，成本很低（几次 mkdirs + exists）。
     */
    @Synchronized
    fun ensure(ctx: Context): File {
        // 老版本会在根目录留 qnn_ctx_*.onnx（QNN context 缓存）；
        // 该功能已移除，启动时清掉，避免占空间又让人误以为还在用
        root(ctx).listFiles { f -> f.name.startsWith("qnn_ctx_") && f.isFile }
            ?.forEach { it.delete() }
        val root = root(ctx)
        root.mkdirs()
        apps(ctx)
        logs(ctx)
        scripts(ctx)
        migrateLegacy(ctx, root)
        seedDemos(ctx)
        return root
    }

    /**
     * 首次启动把内置示例程序释放到 apps/ 下。
     *
     * 只在 apps/ 里没有任何子目录时执行，因此：
     *  - 用户自己导入过程序 → 不再释放，不会覆盖；
     *  - 用户删光了所有程序 → 下次启动会重新出现示例。
     */
    @Synchronized
    private fun seedDemos(ctx: Context) {
        val dir = apps(ctx)
        val existing = dir.listFiles { f -> f.isDirectory } ?: emptyArray()
        if (existing.isNotEmpty()) return
        val names = try {
            ctx.assets.list("demo") ?: emptyArray()
        } catch (_: Throwable) {
            emptyArray()
        }
        if (names.isEmpty()) return
        var copied = 0
        for (name in names) {
            if (copyAssetDir(ctx, "demo/$name", File(dir, name))) copied++
        }
        if (copied > 0) {
            PyBridge.log("[apps] 首次启动，已释放 $copied 个示例程序")
        }
    }

    private fun copyAssetDir(ctx: Context, assetPath: String, dst: File): Boolean {
        val children = try {
            ctx.assets.list(assetPath) ?: emptyArray()
        } catch (_: Throwable) {
            emptyArray()
        }
        return try {
            if (children.isEmpty()) {
                dst.parentFile?.mkdirs()
                ctx.assets.open(assetPath).use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                true
            } else {
                dst.mkdirs()
                var any = false
                for (child in children) {
                    if (copyAssetDir(ctx, "$assetPath/$child", File(dst, child))) any = true
                }
                any
            }
        } catch (t: Throwable) {
            PyBridge.log("[apps] 释放示例失败 $assetPath: ${t.message}")
            false
        }
    }

    /** filesDir 根下的 apps/logs/scripts/models 一并搬到新根目录。 */
    private fun migrateLegacy(ctx: Context, root: File) {
        if (root == ctx.filesDir) return // 理论上不会发生（root 永远带 ansylab 子目录）
        val legacyNames = listOf("apps", "logs", "scripts", "models")
        for (name in legacyNames) {
            val src = File(ctx.filesDir, name)
            if (!src.exists()) continue
            val dst = File(root, name)
            if (!dst.exists()) {
                // 目标还不存在：直接改名（同一分区，几乎零成本）
                if (src.renameTo(dst)) continue
            }
            // 目标已存在：把内容并进去，保留已有文件
            src.listFiles()?.forEach { child ->
                val target = File(dst, child.name)
                if (!target.exists()) {
                    if (!child.renameTo(target)) {
                        child.copyRecursively(target, overwrite = false)
                        child.deleteRecursively()
                    }
                }
            }
            if (src.listFiles()?.isEmpty() != false) src.delete()
        }
        // QNN context / profile 这类零散文件
        ctx.filesDir.listFiles()?.forEach { f ->
            val n = f.name
            if (n.startsWith("yolo_profile_")) {
                val target = File(root, n)
                if (!target.exists()) {
                    if (!f.renameTo(target)) {
                        f.copyTo(target, overwrite = true)
                        f.delete()
                    }
                }
            }
        }
    }

    /** 各子目录的占用（名字 -> 字节数）。 */
    fun usage(ctx: Context): List<Pair<String, Long>> {
        val root = root(ctx)
        val names = listOf("apps", "logs", "scripts")
        val out = ArrayList<Pair<String, Long>>()
        for (n in names) {
            val d = File(root, n)
            out.add(n to sizeOf(d))
        }
        val loose = root.listFiles()
            ?.filter { it.isFile }
            ?.sumOf { it.length() } ?: 0L
        out.add("其他文件" to loose)
        return out
    }

    fun totalUsage(ctx: Context): Long = sizeOf(root(ctx))

    private fun sizeOf(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        var sum = 0L
        file.listFiles()?.forEach { sum += sizeOf(it) }
        return sum
    }

    fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes / 1073741824.0)
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1L shl 10 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    /** 把当前配置写一份 JSON 到数据目录，方便查看/备份。 */
    fun writeSnapshot(ctx: Context) {
        try {
            val root = ensure(ctx)
            val json = org.json.JSONObject()
            json.put("storage_location", location(ctx))
            json.put("root", root.absolutePath)
            json.put("apps_dir", File(root, "apps").absolutePath)
            json.put("logs_dir", File(root, "logs").absolutePath)
            json.put("display_scale", displayScale(ctx))
            json.put("updated_at", System.currentTimeMillis())
            File(root, "settings.json").writeText(json.toString(2))
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- 其他设置

    private const val KEY_SCALE = "display_scale"
    private const val KEY_WEB_ON = "web_enabled"
    private const val KEY_WEB_PW = "web_password"
    private const val KEY_WEB_NEED_PW = "web_need_password"

    /** 主页图标的自定义顺序（逗号分隔的程序名，空表示用默认顺序）。 */
    fun appOrder(ctx: Context): List<String> =
        (prefs(ctx).getString("app_order", "") ?: "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun setAppOrder(ctx: Context, names: List<String>) {
        prefs(ctx).edit().putString("app_order", names.joinToString(",")).apply()
    }

    /** 界面语言：zh / en（默认中文）。 */
    fun language(ctx: Context): String =
        prefs(ctx).getString("language", "zh") ?: "zh"

    fun setLanguage(ctx: Context, v: String) {
        prefs(ctx).edit().putString("language", v).apply()
    }

    /** 网页控制开关（默认关）。 */
    fun webEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_WEB_ON, false)

    fun setWebEnabled(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_WEB_ON, v).apply()
        writeSnapshot(ctx)
    }

    /** 网页访问密码（默认 123456）。 */
    fun webPassword(ctx: Context): String =
        prefs(ctx).getString(KEY_WEB_PW, "123456")?.takeIf { it.isNotBlank() } ?: "123456"

    /** 网页是否需要密码（默认需要）。关掉后任何人可直连。 */
    fun webUsePassword(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_WEB_NEED_PW, true)

    fun setWebUsePassword(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_WEB_NEED_PW, v).apply()
        writeSnapshot(ctx)
    }

    fun setWebPassword(ctx: Context, v: String) {
        prefs(ctx).edit().putString(KEY_WEB_PW, v).apply()
        writeSnapshot(ctx)
    }

    /** 画面缩放：fit / crop / stretch。 */
    fun displayScale(ctx: Context): String =
        prefs(ctx).getString(KEY_SCALE, "fit") ?: "fit"

    fun setDisplayScale(ctx: Context, v: String) {
        prefs(ctx).edit().putString(KEY_SCALE, v).apply()
        writeSnapshot(ctx)
    }

}
