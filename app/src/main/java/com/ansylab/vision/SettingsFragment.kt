package com.ansylab.vision

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.ansylab.vision.databinding.FragmentSettingsBinding

/**
 * 设置页：设备信息（NPU / 系统 / 内存 / 存储）与运行选项都在这一页，
 * 不再有二级页面。
 */
class SettingsFragment : Fragment() {

    private var _root: FragmentSettingsBinding? = null
    private var backCallback: OnBackPressedCallback? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _root = FragmentSettingsBinding.bind(view)
    }

    override fun onStart() {
        super.onStart()
        val activity = activity as? MainActivity ?: return
        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                activity.showHome()
            }
        }.also { activity.onBackPressedDispatcher.addCallback(this, it) }
        build()
    }

    override fun onStop() {
        super.onStop()
        backCallback?.remove()
        backCallback = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _root = null
    }

    private fun build() {
        val b = _root ?: return
        val ctx = requireContext()

        // 从主页返回时会再次走 onStart，先清空
        b.groupRun.removeAllViews()
        b.groupWeb.removeAllViews()
        b.groupAbout.removeAllViews()
        b.groupNpu.removeAllViews()
        b.groupSystem.removeAllViews()
        b.groupMemory.removeAllViews()
        b.groupStore.removeAllViews()
        b.groupCamera.removeAllViews()

        // ---- 运行（第一组）
        SettingsUi.addRow(b.groupRun, getString(R.string.language), LocaleHelper.label(ctx)) {
            chooseLanguage()
        }
        SettingsUi.addRow(b.groupRun, getString(R.string.display_scale), displayScaleLabel(ctx)) { chooseDisplayScale() }
        SettingsUi.addRow(b.groupRun, getString(R.string.logs), getString(R.string.logs_desc)) {
            (activity as? MainActivity)?.showLogs()
        }

        // ---- 网页控制
        buildWebGroup(b)

        // ---- 摄像头（只读）
        b.groupCamera.removeAllViews()
        val cams = DeviceInfo.cameraList(ctx)
        if (cams.isEmpty()) {
            SettingsUi.addValueRow(
                b.groupCamera, getString(R.string.camera), getString(R.string.not_found)
            )
        } else {
            cams.forEach { c ->
                SettingsUi.addValueRow(b.groupCamera, "#${c.id}", DeviceInfo.cameraText(c))
            }
        }

        // ---- 关于（第二组，全部只读）
        SettingsUi.addValueRow(b.groupAbout, getString(R.string.about_app), "AnsyLab")
        SettingsUi.addValueRow(b.groupAbout, getString(R.string.version), appVersion())
        SettingsUi.addValueRow(b.groupAbout, "Python", pythonVersion())
        SettingsUi.addValueRow(b.groupAbout, getString(R.string.package_name), ctx.packageName)

        // ---- 本机 / NPU
        SettingsUi.addValueRow(
            b.groupNpu, getString(R.string.run_mode),
            if (DeviceInfo.npuAvailable(ctx)) DeviceInfo.npuStatus() else "CPU"
        )
        SettingsUi.addValueRow(
            b.groupNpu, getString(R.string.hexagon_arch), DeviceInfo.hexagonArch(ctx)
        )
        SettingsUi.addValueRow(b.groupNpu, "VTCM", DeviceInfo.vtcm(ctx))
        SettingsUi.addValueRow(
            b.groupNpu, getString(R.string.soc_model), DeviceInfo.socModelNumber(ctx)
        )
        SettingsUi.addValueRow(b.groupNpu, getString(R.string.fp16), DeviceInfo.fp16())


        // ---- 系统
        SettingsUi.addValueRow(b.groupSystem, getString(R.string.device), DeviceInfo.model())
        SettingsUi.addValueRow(b.groupSystem, getString(R.string.chip), DeviceInfo.socName(ctx))
        SettingsUi.addValueRow(b.groupSystem, getString(R.string.system), DeviceInfo.androidVersion())
        SettingsUi.addValueRow(b.groupSystem, "ABI", DeviceInfo.abi())

        // ---- 内存
        val (memTotal, memUsed, memAvail) = DeviceInfo.memInfo(ctx)
        SettingsUi.addValueRow(b.groupMemory, getString(R.string.mem_total), Storage.human(memTotal))
        SettingsUi.addValueRow(b.groupMemory, getString(R.string.mem_used), Storage.human(memUsed))
        SettingsUi.addValueRow(b.groupMemory, getString(R.string.mem_avail), Storage.human(memAvail))

        // ---- 存储
        val (storeTotal, storeFree) = DeviceInfo.storageSpace(ctx)
        SettingsUi.addValueRow(b.groupStore, getString(R.string.store_total), Storage.human(storeTotal))
        SettingsUi.addValueRow(b.groupStore, getString(R.string.store_free), Storage.human(storeFree))
        SettingsUi.addValueRow(
            b.groupStore, getString(R.string.store_used),
            Storage.human((storeTotal - storeFree).coerceAtLeast(0))
        )
        SettingsUi.addValueRow(
            b.groupStore, getString(R.string.data_usage),
            Storage.human(Storage.totalUsage(ctx))
        )
        // 数据目录：点一下把路径复制走
        val pathRow = SettingsUi.addRow(
            b.groupStore, getString(R.string.data_dir), Storage.ensure(ctx).absolutePath, chevron = false
        ) { copyDataPath() }
        (pathRow.findViewById<TextView>(R.id.rowValue)).apply {
            maxLines = 2
            textSize = 11f
        }
    }

    private fun copyDataPath() {
        val ctx = requireContext()
        val path = Storage.ensure(ctx).absolutePath
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.data_dir_clip), path))
        Toast.makeText(ctx, getString(R.string.data_dir_copied), Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------ 各项设置

    private fun displayScaleLabel(ctx: Context) = when (Storage.displayScale(ctx)) {
        "crop" -> getString(R.string.scale_crop)
        "stretch" -> getString(R.string.scale_stretch)
        else -> getString(R.string.scale_fit)
    }

    private fun chooseLanguage() {
        val ctx = requireContext()
        val values = arrayOf(LocaleHelper.ZH, LocaleHelper.EN)
        val labels = arrayOf(getString(R.string.language_zh), getString(R.string.language_en))
        val current = values.indexOf(Storage.language(ctx)).coerceAtLeast(0)
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.language))
            .setSingleChoiceItems(labels, current) { dialog, which ->
                Storage.setLanguage(ctx, values[which])
                dialog.dismiss()
                // 重新创建 Activity，让资源和所有页面都换成新语言
                requireActivity().recreate()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun chooseDisplayScale() {
        val ctx = requireContext()
        val labels = arrayOf(
            getString(R.string.scale_fit),
            getString(R.string.scale_crop),
            getString(R.string.scale_stretch)
        )
        val values = arrayOf("fit", "crop", "stretch")
        val current = values.indexOf(Storage.displayScale(ctx)).coerceAtLeast(0)
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.display_scale))
            .setSingleChoiceItems(labels, current) { dialog, which ->
                Storage.setDisplayScale(ctx, values[which])
                dialog.dismiss()
                build()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ---------------------------------------------------------------- 网页控制

    private fun buildWebGroup(b: FragmentSettingsBinding) {
        val ctx = requireContext()
        SettingsUi.addSwitchRow(
            b.groupWeb, getString(R.string.web_control), Storage.webEnabled(ctx)
        ) { on ->
            Storage.setWebEnabled(ctx, on)
            if (on) showWebHelp()
            else PyBridge.stopWeb()
            build()
        }
        SettingsUi.addSwitchRow(
            b.groupWeb, getString(R.string.web_password_enable), Storage.webUsePassword(ctx)
        ) { on ->
            Storage.setWebUsePassword(ctx, on)
            if (!on) Toast.makeText(ctx, getString(R.string.web_password_off_hint),
                Toast.LENGTH_SHORT).show()
            build()
        }
        SettingsUi.addRow(
            b.groupWeb, getString(R.string.web_password),
            if (Storage.webUsePassword(ctx)) Storage.webPassword(ctx) else getString(R.string.off)
        ) { if (Storage.webUsePassword(requireContext())) showPasswordDialog() }
        SettingsUi.addValueRow(
            b.groupWeb, getString(R.string.web_address),
            if (Storage.webEnabled(ctx)) "http://${PyBridge.localIps().split(",").firstOrNull()
                ?: "127.0.0.1"}:8080" else getString(R.string.not_enabled)
        )
    }

    private fun showWebHelp() {
        val ctx = requireContext()
        val ip = PyBridge.localIps().split(",").firstOrNull() ?: "127.0.0.1"
        val pw = Storage.webPassword(ctx)
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.web_enabled_title))
            .setMessage(
                """
                手机和电脑连同一个热点 / 局域网后，用浏览器打开：

                http://$ip:8080

                访问密码：$pw

                · 页面上能看实时画面、调滑块、拨开关、点按钮、拖摇杆
                · 只有运行中的程序才会推流，回到主页就停止
                · 密码可在设置里修改
                """.trimIndent()
            )
            .setPositiveButton(getString(R.string.got_it), null)
            .show()
    }

    private fun showPasswordDialog() {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply {
            setText(Storage.webPassword(ctx))
            setSelection(text.length)
            hint = getString(R.string.web_password)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val wrap = android.widget.FrameLayout(ctx).apply {
            val pad = (8 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(input)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.change_password))
            .setView(wrap)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val v = input.text.toString().trim()
                if (v.isEmpty()) {
                    Toast.makeText(ctx, getString(R.string.password_empty), Toast.LENGTH_SHORT).show()
                } else {
                    Storage.setWebPassword(ctx, v)
                    Toast.makeText(ctx, getString(R.string.password_updated),
                        Toast.LENGTH_SHORT).show()
                    build()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun appVersion(): String = try {
        val ctx = requireContext()
        "v" + ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    } catch (t: Throwable) {
        "v?"
    }

    /** Chaquopy 里的 Python 版本，形如 "3.10.22"。 */
    private fun pythonVersion(): String = try {
        com.chaquo.python.Python.getInstance()
            .getModule("sys").get("version").toString()
            .split(" ").firstOrNull() ?: "?"
    } catch (t: Throwable) {
        "?"
    }
}
