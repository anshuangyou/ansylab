package com.ansylab.vision

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 一个「程序」= files/apps/ 下的一个文件夹。
 *
 *   apps/<名字>/
 *       icon.png      主页上显示的图标（可选，没有就用默认图标）
 *       main.py       入口脚本（没有 main.py 就取第一个 .py）
 *       *.onnx        YOLO 模型（脚本里自己写路径）
 *       widgets.json  控件声明（可选）
 *
 * 文件夹名就是主页上显示的程序名。
 */
data class AppEntry(
    val name: String,
    val dir: File,
    val icon: File?,
    val script: File?
)

object AppRepository {

    fun root(context: Context): File = Storage.apps(context)

    /** 主页图标的固定顺序；不在表里的（用户自己导入的）排在后面按名字排。 */
    private val PREFERRED_ORDER = listOf("yolo", "serial", "imu", "widget")

    fun list(context: Context): List<AppEntry> {
        val dirs = root(context).listFiles { f -> f.isDirectory } ?: return emptyList()
        val entries = dirs.mapNotNull { dir -> toEntry(dir.name, dir) }
        val saved = Storage.appOrder(context)

        // 用户拖过（saved 非空）就按保存的顺序；没拖过用默认顺序。
        // 名字对不上（已删除）的排在后面，不会残留。
        return entries.sortedWith(
            compareBy(
                { e ->
                    if (saved.isEmpty()) 0
                    else saved.indexOfFirst { it.equals(e.name, ignoreCase = true) }
                        .let { if (it < 0) 900 else it }
                },
                { e ->
                    PREFERRED_ORDER.indexOf(e.name.lowercase())
                        .let { if (it < 0) 99 else it }
                },
                { it.name.lowercase() }
            )
        )
    }

    fun find(context: Context, name: String): AppEntry? {
        val dir = File(root(context), name)
        return if (dir.isDirectory) toEntry(name, dir) else null
    }

    private fun toEntry(name: String, dir: File): AppEntry {
        val scripts = dir.listFiles { f -> f.isFile && f.extension.equals("py", true) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        val script = scripts.firstOrNull { it.name.equals("main.py", true) } ?: scripts.firstOrNull()
        val icon = File(dir, "icon.png").takeIf { it.isFile }
        return AppEntry(name, dir, icon, script)
    }

    /** 卸载：直接删掉 apps/<name>/ 整个文件夹。 */
    fun delete(context: Context, name: String): Boolean {
        val dir = File(root(context), name)
        return !dir.exists() || dir.deleteRecursively()
    }

    /**
     * 导入项目压缩包：解压成 apps/<压缩包名>/。
     *
     * 压缩包里如果只有一层根目录（常见于「右键压缩文件夹」），会自动把这层剥掉，
     * 免得程序里多出一级目录。
     */
    fun importZip(context: Context, uri: Uri): AppEntry? {
        val zipName = queryName(context, uri)?.substringBeforeLast('.') ?: "app"
        val tmp = File(context.cacheDir, "import_${System.currentTimeMillis()}.zip")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            return importZipFile(context, tmp, zipName)
        } finally {
            tmp.delete()
        }
    }

    /** 从一个本地 zip 文件导入（上位机 / adb 调试通道用）。 */
    fun importZipFile(context: Context, file: File, nameHint: String? = null): AppEntry? {
        if (!file.isFile) return null
        val zipName = nameHint?.takeIf { it.isNotBlank() }
            ?: file.name.substringBeforeLast('.').ifBlank { "app" }
        val tmp = file

        // 先扫一遍条目名，判断是否需要剥掉公共根目录
        val names = mutableListOf<String>()
        java.util.zip.ZipInputStream(tmp.inputStream().buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                val n = e.name.replace('\\', '/')
                if (!e.isDirectory && !n.startsWith("__MACOSX/")) names.add(n)
            }
        }
        if (names.isEmpty()) return null

        val roots = names.map { it.substringBefore('/') }.distinct()
        val stripPrefix = if (roots.size == 1 && names.all { it.contains('/') }) "${roots[0]}/" else ""

        val dir = File(root(context), sanitize(zipName))
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()

        var copied = 0
        java.util.zip.ZipInputStream(tmp.inputStream().buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                val raw = e.name.replace('\\', '/')
                if (e.isDirectory || raw.startsWith("__MACOSX/")) continue
                val relative = if (stripPrefix.isNotEmpty() && raw.startsWith(stripPrefix)) {
                    raw.substring(stripPrefix.length)
                } else raw
                if (relative.isEmpty()) continue
                val target = File(dir, relative)
                // 防止 ../ 之类的路径穿越
                if (!target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) continue
                target.parentFile?.mkdirs()
                target.outputStream().use { zis.copyTo(it) }
                copied++
            }
        }
        if (copied == 0) {
            dir.deleteRecursively()
            return null
        }
        return toEntry(dir.name, dir)
    }

    /** 最近一次导入失败的原因（给界面提示用）。 */
    var lastImportError: String = ""
        private set

    /**
     * 导入一个已转换好的 YOLO 模型：自动建项目文件夹，并放一份通用的
     * yolo_demo 脚本进去（脚本会在同目录下找模型文件）。
     */
    fun importTree(context: Context, uri: Uri): AppEntry? {
        val doc = DocumentFile.fromTreeUri(context, uri) ?: return null
        val name = (doc.name ?: "app").trim().ifBlank { "app" }
        val dir = File(root(context), sanitize(name))
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()

        var copied = 0
        for (child in doc.listFiles()) {
            if (!child.isFile) continue
            val fileName = child.name ?: continue
            // 只收程序需要的东西，避免把杂七杂八的图片/视频拷进来
            val keep = fileName.equals("icon.png", true) ||
                fileName.endsWith(".py", true) ||
                fileName.endsWith(".onnx", true)
            if (!keep) continue
            val input = context.contentResolver.openInputStream(child.uri)
            if (input != null) {
                input.use { src ->
                    File(dir, fileName).outputStream().use { dst -> src.copyTo(dst) }
                }
                copied++
            }
        }
        if (copied == 0) {
            dir.deleteRecursively()
            return null
        }
        return toEntry(dir.name, dir)
    }

    /** 文件名里不能出现的字符换成下划线。 */
    /** 从 content:// 里取文件名（压缩包名会拿它当程序名）。 */
    @android.annotation.SuppressLint("Recycle")  // 已用 use{} 关闭，lint 识别不到
    private fun queryName(context: Context, uri: Uri): String? {
        var name: String? = null
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            if (cursor != null) {
                cursor.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
                }
            }
        } catch (_: Throwable) {
        }
        if (name.isNullOrBlank()) name = uri.lastPathSegment
        return name
    }
    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(48).ifBlank { "app" }
}
