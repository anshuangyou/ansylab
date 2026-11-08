package com.ansylab.vision

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ansylab.vision.databinding.FragmentHomeBinding

/**
 * 主页：4 列图标网格。
 *
 * 每个图标对应 files/apps/ 下的一个文件夹，文件夹名就是程序名。
 * 右上角「+」支持两种导入：项目文件夹 / 项目压缩包。
 * 长按图标 5 秒进入「卸载模式」，图标右上角出现 ×。
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val adapter = AppGridAdapter(
        onClick = { app ->
            if (selectMode) {
                exitSelectMode()
            } else {
                (activity as? MainActivity)?.openApp(app)
            }
        },
        onRemove = { app -> confirmRemove(app) },
        onLongPress = { enterSelectMode() },
        onDragStart = { enterSelectMode() }
    )

    /** 卸载模式：每个图标右上角显示 ×。 */
    private var selectMode = false

    /** 拖拽排序：长按起拖、松手保存顺序。 */
    private val dragCallback = object :
        androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.UP or
                androidx.recyclerview.widget.ItemTouchHelper.DOWN or
                androidx.recyclerview.widget.ItemTouchHelper.START or
                androidx.recyclerview.widget.ItemTouchHelper.END,
            0
        ) {

        override fun onMove(
            rv: RecyclerView,
            holder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            adapter.moveItem(holder.bindingAdapterPosition, target.bindingAdapterPosition)
            return true
        }

        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun isLongPressDragEnabled(): Boolean = true

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG) {
                viewHolder?.itemView?.let {
                    it.alpha = 0.85f
                    it.scaleX = 1.06f
                    it.scaleY = 1.06f
                    it.elevation = 12f * resources.displayMetrics.density
                    it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                }
                // 拖动中不能整表刷新（会打断 ItemTouchHelper），
                // 只改标志并把已绑定 holder 的 × 直接显示出来。
                // 注意 fragment 自己的 selectMode 也要置上，否则空白处点击不会退出。
                selectMode = true
                adapter.setSelectMode(true, notify = false)
                _binding?.let { b ->
                    for (i in 0 until b.appGrid.childCount) {
                        b.appGrid.getChildAt(i)
                            .findViewById<android.widget.ImageView>(R.id.removeBadge)
                            ?.visibility = View.VISIBLE
                    }
                }
            }
        }

        override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(rv, viewHolder)
            viewHolder.itemView.alpha = 1f
            viewHolder.itemView.scaleX = 1f
            viewHolder.itemView.scaleY = 1f
            viewHolder.itemView.elevation = 0f
            // 松手：把当前顺序存下来，下次进来保持一致
            val ctx = _binding?.root?.context ?: return
            Storage.setAppOrder(ctx, adapter.names())
            adapter.setSelectMode(true)
        }
    }

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) importFolder(uri)
        }

    private val pickZip =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) importZip(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.appGrid.layoutManager = GridLayoutManager(requireContext(), COLUMNS)
        binding.appGrid.adapter = adapter
        binding.btnImport.setOnClickListener { showImportMenu() }

        // 卸载模式下「点任意位置」都退出：
        //   - 根布局覆盖标题栏和四周空白
        //   - RecyclerView 自己会吃掉触摸，所以单独监听「点在空白处」
        // 注册顺序有意义：RecyclerView 会依次询问 OnItemTouchListener，
        // ItemTouchHelper 会先拦截事件，排在它后面的监听器收不到点击，
        // 所以空白点击的监听必须在 attach ItemTouchHelper 之前注册。
        binding.root.setOnClickListener { exitSelectMode() }
        binding.appGrid.addOnItemTouchListener(
            object : RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    if (selectMode && e.actionMasked == MotionEvent.ACTION_DOWN &&
                        rv.findChildViewUnder(e.x, e.y) == null
                    ) {
                        exitSelectMode()
                    }
                    return false
                }
            }
        )

        // 长按图标 → 浮起可拖动；拖动时顺带进入卸载模式（显示 ×），
        // 松手后停在卸载模式，点空白处退出。
        androidx.recyclerview.widget.ItemTouchHelper(dragCallback)
            .attachToRecyclerView(binding.appGrid)

        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    // ---------------------------------------------------------------- 导入菜单

    private fun showImportMenu() {
        exitSelectMode()   // 点「+」也算点了别处
        // 系统弹出的列表菜单：宽度按文字自适应。
        // PopupMenu 没有偏移接口（宽菜单会一直顶到屏幕右边），所以用同样属于
        // 系统控件的 ListPopupWindow：右对齐 + 往左让 20dp，不贴边。
        val items = arrayOf(
                getString(R.string.import_folder),
                getString(R.string.import_zip)
            )
        val popup = android.widget.ListPopupWindow(requireContext())
        popup.anchorView = binding.btnImport
        // 宽度按最长的一条文字测量（WRAP_CONTENT 会被锚点宽度限制成一条窄条）
        val textView = android.widget.TextView(requireContext())
        textView.textSize = 16f
        val textWidth = items.maxOf { textView.paint.measureText(it).toInt() }
        popup.width = textWidth + dp(48)
        popup.isModal = true
        popup.setDropDownGravity(android.view.Gravity.END)
        popup.horizontalOffset = -dp(12)
        popup.verticalOffset = dp(2)
        popup.setAdapter(
            android.widget.ArrayAdapter(
                requireContext(),
                android.R.layout.simple_list_item_1,
                items
            )
        )
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            when (position) {
                0 -> pickFolder.launch(null)
                1 -> pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
            }
        }
        popup.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun importFolder(uri: Uri) {
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Throwable) {
            // 不是所有 provider 都支持持久授权，失败不影响本次拷贝
        }
        val app = AppRepository.importTree(requireContext(), uri)
        finishImport(app, getString(R.string.no_py_in_folder))
    }

    private fun importZip(uri: Uri) {
        val app = try {
            AppRepository.importZip(requireContext(), uri)
        } catch (t: Throwable) {
            Toast.makeText(requireContext(), getString(R.string.unzip_failed, t.message ?: ""), Toast.LENGTH_LONG).show()
            null
        }
        finishImport(app, getString(R.string.no_app_in_zip))
    }

    private fun finishImport(app: AppEntry?, errorMessage: String) {
        if (app == null) {
            Toast.makeText(requireContext(), getString(R.string.import_failed, errorMessage), Toast.LENGTH_SHORT).show()
            return
        }
        reload()
        Toast.makeText(requireContext(), getString(R.string.imported, app.name), Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- 卸载

    private fun enterSelectMode() {
        if (selectMode) return
        selectMode = true
        adapter.setSelectMode(true)
    }

    private fun exitSelectMode() {
        if (!selectMode) return
        selectMode = false
        adapter.setSelectMode(false)
    }

    private fun confirmRemove(app: AppEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.remove_title, app.name))
            .setMessage(getString(R.string.remove_message))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.remove)) { _, _ ->
                val ok = AppRepository.delete(requireContext(), app.name)
                if (ok) {
                    reload()
                    Toast.makeText(requireContext(), getString(R.string.removed, app.name), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.remove_failed), Toast.LENGTH_SHORT).show()
                }
                if (adapter.itemCount == 0) exitSelectMode()
            }
            .show()
    }

    private fun reload() {
        val apps = AppRepository.list(requireContext())
        adapter.submit(apps)
        binding.emptyHint.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        binding.appGrid.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
        if (apps.isEmpty() && selectMode) exitSelectMode()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val COLUMNS = 4

        /** 长按多久进入卸载模式。 */
        private const val LONG_PRESS_MS = 3000L

        private class AppGridAdapter(
            val onClick: (AppEntry) -> Unit,
            val onRemove: (AppEntry) -> Unit,
            val onLongPress: () -> Unit,
            val onDragStart: () -> Unit
        ) : RecyclerView.Adapter<AppGridAdapter.Holder>() {

            private val items = mutableListOf<AppEntry>()
            private var selectMode = false

            fun submit(list: List<AppEntry>) {
                items.clear()
                items.addAll(list)
                notifyDataSetChanged()
            }

            fun setSelectMode(on: Boolean, notify: Boolean = true) {
                selectMode = on
                if (notify) notifyDataSetChanged()
            }

            /** 拖动排序：交换两项。 */
            fun moveItem(from: Int, to: Int) {
                if (from < 0 || to < 0 || from >= items.size || to >= items.size) return
                val item = items.removeAt(from)
                items.add(to, item)
                notifyItemMoved(from, to)
            }

            fun names(): List<String> = items.map { it.name }

            fun onDragStart() = onLongPress()

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
                val v = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_app, parent, false)
                return Holder(v)
            }

            override fun onBindViewHolder(holder: Holder, position: Int) {
                holder.bind(items[position], selectMode, onClick, onRemove, onLongPress)
            }

            override fun getItemCount(): Int = items.size

            class Holder(view: View) : RecyclerView.ViewHolder(view) {
                private val cell: View = view.findViewById(R.id.appCell)
                private val icon: ImageView = view.findViewById(R.id.appIcon)
                private val name: TextView = view.findViewById(R.id.appName)
                private val badge: ImageView = view.findViewById(R.id.removeBadge)

                private val handler = Handler(Looper.getMainLooper())
                private var longPressTask: Runnable? = null

                fun bind(
                    app: AppEntry,
                    selectMode: Boolean,
                    onClick: (AppEntry) -> Unit,
                    onRemove: (AppEntry) -> Unit,
                    onLongPress: () -> Unit
                ) {
                    name.text = app.name
                    val bmp = app.icon?.let { BitmapFactory.decodeFile(it.absolutePath) }
                    if (bmp != null) icon.setImageBitmap(bmp) else icon.setImageResource(R.drawable.ic_app_default)

                    badge.visibility = if (selectMode) View.VISIBLE else View.GONE

                    cell.setOnClickListener { onClick(app) }
                    badge.setOnClickListener { onRemove(app) }

                    // 长按 5 秒才进入卸载模式（系统默认的 ~500ms 太容易误触）
                    cell.setOnTouchListener { v, e ->
                        when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                cancelLongPress()
                                val task = Runnable {
                                    v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                    onLongPress()
                                }
                                longPressTask = task
                                handler.postDelayed(task, LONG_PRESS_MS)
                                false
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                cancelLongPress()
                                false
                            }
                            else -> false
                        }
                    }
                }

                private fun cancelLongPress() {
                    longPressTask?.let { handler.removeCallbacks(it) }
                    longPressTask = null
                }
            }
        }
    }
}
