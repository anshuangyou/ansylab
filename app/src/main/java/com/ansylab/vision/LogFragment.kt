package com.ansylab.vision

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import com.ansylab.vision.databinding.FragmentLogsBinding
import java.io.File

/**
 * 日志页：直接看 native.log（Kotlin 侧）和 last_run.log（Python 侧）。
 *
 * 出问题时不用连电脑，在手机上就能看到堆栈。
 */
class LogFragment : Fragment() {

    private var _binding: FragmentLogsBinding? = null
    private val binding get() = _binding!!
    private var backCallback: OnBackPressedCallback? = null

    /** 当前看的是哪个文件。 */
    private var current: String = NORMAL

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_logs, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentLogsBinding.bind(view)

        binding.btnBack.setOnClickListener { back() }
        binding.btnNormal.setOnClickListener {
            current = NORMAL
            load()
        }
        binding.btnRun.setOnClickListener {
            current = RUN
            load()
        }
        binding.btnReload.setOnClickListener { load() }
        binding.btnCopy.setOnClickListener { copy() }
        binding.btnClear.setOnClickListener { clear() }

        load()
    }

    override fun onStart() {
        super.onStart()
        val activity = activity as? MainActivity ?: return
        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                back()
            }
        }.also { activity.onBackPressedDispatcher.addCallback(this, it) }
    }

    override fun onStop() {
        super.onStop()
        backCallback?.remove()
        backCallback = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun back() {
        (activity as? MainActivity)?.showSettings()
    }

    private fun file(): File = File(Storage.logs(requireContext()), current)

    private fun load() {
        val ctx = requireContext()
        val file = file()
        binding.logTitle.text = current
        binding.logPath.text = file.absolutePath

        // 读文件放到后台线程：200 KB 在主线程读会造成明显掉帧
        binding.logText.text = getString(R.string.log_loading)
        val appCtx = ctx.applicationContext
        Thread {
            val text = try {
                if (!file.exists()) {
                    appCtx.getString(R.string.log_missing)
                } else {
                // 只读末尾 200 KB，避免超大日志把界面卡死
                val len = file.length()
                val start = if (len > MAX_BYTES) len - MAX_BYTES else 0
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(start)
                    val buf = ByteArray((len - start).toInt())
                    raf.readFully(buf)
                    String(buf, Charsets.UTF_8)
                }
            }
            } catch (t: Throwable) {
                appCtx.getString(R.string.log_read_failed, t.message ?: "")
            }
            activity?.runOnUiThread {
                val b = _binding ?: return@runOnUiThread
                b.logText.text = text
                b.logScroll.post { b.logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }.start()
    }

    private fun copy() {
        val cm = requireContext()
            .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(
            android.content.ClipData.newPlainText(current, binding.logText.text)
        )
        android.widget.Toast.makeText(
            requireContext(), R.string.log_copied, android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun clear() {
        val ctx = requireContext()
        android.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.log_clear_title)
            .setMessage(getString(R.string.log_clear_message, current))
            .setPositiveButton(R.string.log_clear) { _, _ ->
                try {
                    file().writeText("")
                } catch (_: Throwable) {
                }
                load()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val NORMAL = "native.log"
        private const val RUN = "last_run.log"
        private const val MAX_BYTES = 200 * 1024L
    }
}
