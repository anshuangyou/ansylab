package com.ansylab.vision

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import com.ansylab.vision.databinding.FragmentAppBinding
import com.ansylab.vision.databinding.ItemWidgetButtonBinding
import com.ansylab.vision.databinding.ItemWidgetComboBinding
import com.ansylab.vision.databinding.ItemWidgetJoystick1Binding
import com.ansylab.vision.databinding.ItemWidgetJoystick2Binding
import com.ansylab.vision.databinding.ItemWidgetLabelBinding
import com.ansylab.vision.databinding.ItemWidgetSliderBinding
import com.ansylab.vision.databinding.ItemWidgetTextInputBinding
import com.ansylab.vision.databinding.ItemWidgetTextOutputBinding
import com.ansylab.vision.databinding.ItemWidgetToggleBinding

/**
 * 程序页：显示区 + 可收起的控件区。
 *
 * 控件由 Python 通过 ansylab.slider()/button()/toggle()/label() 创建，
 * 数据放在 [WidgetStore]，这里只负责画出来并把用户操作写回模型。
 */
class AppFragment : Fragment(), DisplaySink {

    private var _binding: FragmentAppBinding? = null
    private val binding get() = _binding!!

    private var appName: String? = null

    /** 当前页面显示的程序名（MainActivity 防重复打开用）。 */
    val currentAppName: String? get() = appName
    private var scriptPath: String? = null

    /** 控件区默认折叠，进页面先看画面。 */
    private var expanded = false
    private var backCallback: OnBackPressedCallback? = null

    /** 本次页面会话的令牌（MainActivity 分配）。 */
    private var myToken = -1

    /** 防止 onStart 连续触发导致同一个脚本被启动两次（会加载两份模型）。 */
    private var launchPending = false

    /** 防止重建列表时，SeekBar 的 setProgress 反过来改模型。 */
    private var rendering = false

    /** label 控件 id -> TextView，文字变化时就地更新，避免整块重建。 */
    private val labelViews = HashMap<String, TextView>()

    /** 多行输出框 id -> TextView，同样就地更新。 */
    private val outputViews = HashMap<String, TextView>()

    /** 多行输入框 id -> EditText：脚本改内容（比如发送后清空）时要跟着变。 */
    private val inputViews = HashMap<String, android.widget.EditText>()

    /** 高频控件的就地刷新：进度条 / 图片，避免整块重建。 */
    private val liveUpdaters = HashMap<String, () -> Unit>()

    private val onLiveChanged: (String) -> Unit = { id ->
        liveUpdaters[id]?.invoke()
    }

    /** 多行输出框 id -> 外层 ScrollView / 最大高度，文字变化时要重新算高度。 */
    private val outputScrolls = HashMap<String, ScrollView>()
    private val outputMaxDp = HashMap<String, Int>()

    /** 下拉框 id -> 显示当前值的 TextView。 */
    private val comboValues = HashMap<String, TextView>()

    // 回调用字段持有，卸载时按身份判断再清空：
    // 否则旧页面 onStop 会清掉新页面刚装上的回调，控件会显示不出来。
    private val onWidgetsChanged: () -> Unit = { renderWidgets() }
    private val onWidgetTextChanged: (String, String) -> Unit = { id, text ->
        labelViews[id]?.text = text
        outputViews[id]?.let { tv ->
            // 自动跟随最新一行，但只在已经贴底时才滚，
            // 避免干扰用户向上翻看历史。
            val scroll = outputScrolls[id]
            val atBottom = if (scroll == null) true else {
                val child = scroll.getChildAt(0)
                val contentHeight = child?.height ?: 0
                val distance = contentHeight - (scroll.height + scroll.scrollY)
                distance <= dp(24)
            }
            tv.text = text
            adjustOutputHeight(id)
            if (scroll != null && atBottom) {
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        // 输入框：脚本写内容（比如发送后清空）要同步到界面。
        // 用户正在打字时不覆盖他的内容，但「清空」这种操作必须生效。
        inputViews[id]?.let { et ->
            val same = et.text.toString() == text
            if (!same && (!et.isFocused || text.isEmpty())) et.setText(text)
        }
    }

    /**
     * 多行输出框的高度：**固定**为「最大高度」，内容多了在框内滚动。
     *
     * 不能按内容高度自适应：控件区（fixedWidgetList）是 wrap_content，
     * 输出框随行数长高会把下面的控件顶出屏幕之外。
     * 同时给一个屏幕占比上限，防止脚本把 max_height 设得过大。
     */
    private fun adjustOutputHeight(id: String) {
        val scroll = outputScrolls[id] ?: return
        val tv = outputViews[id] ?: return
        val asked = dp(outputMaxDp[id] ?: 0)
        if (asked <= 0) return
        val budget = (resources.displayMetrics.heightPixels * 0.42f).toInt()
        val h = minOf(asked, budget)
        scroll.post {
            if (scroll.layoutParams.height != h) {
                scroll.layoutParams = scroll.layoutParams.apply { height = h }
            }
            // 内容变化后保持滚动条可见（TextView 内容高度可能还没测完，延一帧）
            tv.post { scroll.isVerticalScrollBarEnabled = true }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        appName = arguments?.getString(ARG_APP)
        scriptPath = arguments?.getString(ARG_SCRIPT)

        val app = appName?.let { AppRepository.find(requireContext(), it) }
        binding.appTitle.text = app?.name ?: scriptPath?.substringAfterLast('/') ?: getString(R.string.program)

        binding.btnBack.setOnClickListener { (activity as? MainActivity)?.showHome() }
        // 只有箭头可点：整条标题不再响应点击
        binding.widgetChevron.setOnClickListener { toggleWidgets() }

        applyExpanded()
    }

    /** 脚本抛异常时，把摘要显示在页面顶部。 */
    fun showScriptError(text: String) {
        val b = _binding ?: return
        val lines = text.lines().filter { it.isNotBlank() }
        val summary = lines.takeLast(3).joinToString("\n")
        b.errorBanner.visibility = View.VISIBLE
        b.errorBanner.text = summary
    }

    override fun onStart() {
        super.onStart()
        val activity = activity as? MainActivity ?: return

        if (launchPending) {
            // onStart 被连续调用（视图重建等）：不要重复启动脚本
            return
        }
        launchPending = true

        // 系统返回键：回到主页，而不是直接退出 App
        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                activity.showHome()
            }
        }.also { activity.onBackPressedDispatcher.addCallback(this, it) }

        WidgetStore.onChanged = onWidgetsChanged
        WidgetStore.onTextChanged = onWidgetTextChanged
        WidgetStore.onLiveChanged = onLiveChanged
        WidgetStore.clear()
        renderWidgets()

        activity.registerSink(this)
        val app = appName?.let { AppRepository.find(requireContext(), it) }
        // 进入程序页一律自动运行
        // 记住本次会话令牌，onStop 时带回去：旧页面迟到的 stop 会被忽略
        myToken = activity.startApp(app, scriptPath)
    }

    override fun onStop() {
        super.onStop()
        launchPending = false
        backCallback?.remove()
        backCallback = null
        if (WidgetStore.onChanged === onWidgetsChanged) WidgetStore.onChanged = null
        if (WidgetStore.onTextChanged === onWidgetTextChanged) WidgetStore.onTextChanged = null
        if (WidgetStore.onLiveChanged === onLiveChanged) WidgetStore.onLiveChanged = null
        labelViews.clear()
        outputViews.clear()
        inputViews.clear()
        comboValues.clear()
        outputScrolls.clear()
        outputMaxDp.clear()
        (activity as? MainActivity)?.let {
            it.unregisterSink(this)
            it.stopApp(myToken)
        }
    }

    // ---------------------------------------------------------------- 控件区

    /**
     * 整块重建。控件数量是个位数，重建比 diff 简单可靠。
     *
     * 两个区域分开渲染：
     *   - 固定区 fixedWidgetList：region="main"，视频显示控件也在这一区
     *   - 折叠区 widgetList：region="panel"
     */
    private fun renderWidgets() {
        val b = _binding ?: return
        rendering = true
        labelViews.clear()
        outputViews.clear()
        inputViews.clear()
        comboValues.clear()
        outputScrolls.clear()
        outputMaxDp.clear()
        liveUpdaters.clear()

        // 视频显示控件存在时才显示 ImageView；脚本用 ansylab.display() 创建，
        // 或者 imshow 首次调用时自动创建。
        val hasDisplay = WidgetStore.hasDisplay()
        b.displayContainer.visibility = if (hasDisplay) View.VISIBLE else View.GONE
        if (!hasDisplay) {
            b.displayPlaceholder.visibility = View.VISIBLE
        }

        // 高度分配：有视频时视频占大头、固定控件区限高可滚动；
        // 没有视频时整块固定区都给控件（且可滚动）。
        val dpParams = b.displayContainer.layoutParams as LinearLayout.LayoutParams
        val spParams = b.fixedWidgetScroll.layoutParams as LinearLayout.LayoutParams
        if (hasDisplay) {
            dpParams.weight = 1f
            dpParams.height = 0
            // 有视频时固定控件区只占一小条，避免展开折叠区后把画面挤没了
            spParams.weight = 0f
            spParams.height = (resources.displayMetrics.heightPixels * 0.24f).toInt()
            b.displayContainer.minimumHeight = (120 * resources.displayMetrics.density).toInt()
        } else {
            dpParams.weight = 0f
            dpParams.height = 0
            spParams.weight = 1f
            spParams.height = 0
            b.displayContainer.minimumHeight = 0
        }
        b.displayContainer.layoutParams = dpParams
        b.fixedWidgetScroll.layoutParams = spParams

        buildList(b.fixedWidgetList, WidgetStore.snapshot(WidgetStore.REGION_MAIN), false)
        buildList(b.widgetList, WidgetStore.snapshot(WidgetStore.REGION_PANEL), true)

        rendering = false
    }

    private fun buildList(
        container: LinearLayout,
        items: List<WidgetStore.Model>,
        showEmptyHint: Boolean
    ) {
        val inflater = LayoutInflater.from(requireContext())
        container.removeAllViews()

        // display 类型的控件就是 displayView 本身，不用再建视图
        val visible = items.filter { it.type != WidgetStore.TYPE_DISPLAY && it.visible }

        if (visible.isEmpty()) {
            if (showEmptyHint) {
                val hint = TextView(requireContext()).apply {
                    text = getString(R.string.no_widgets)
                    setTextColor(requireContext().getColor(R.color.ink_disabled))
                    textSize = 12f
                    setPadding(0, 8, 0, 8)
                }
                container.addView(hint)
            }
            return
        }

        for (m in visible) {
            when (m.type) {
                WidgetStore.TYPE_SLIDER -> {
                    val item = ItemWidgetSliderBinding.inflate(inflater, container, false)
                    item.widgetLabel.text = m.label
                    val steps = ((m.max - m.min) / m.step).toInt().coerceAtLeast(1)
                    item.widgetSeek.max = steps
                    item.widgetSeek.progress = ((m.number - m.min) / m.step).toInt().coerceIn(0, steps)
                    item.widgetValue.text = format(m.number, m.step)
                    // 网页拖滑块时只移动进度与数值
                    liveUpdaters[m.id] = {
                        val v = WidgetStore.value(m.id)
                        val pos = ((v - m.min) / m.step).toInt().coerceIn(0, steps)
                        if (item.widgetSeek.progress != pos) item.widgetSeek.progress = pos
                        item.widgetValue.text = format(v, m.step)
                    }
                    item.widgetSeek.setOnSeekBarChangeListener(object :
                        SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                            if (!fromUser || rendering) return
                            val v = m.min + p * m.step
                            WidgetStore.setNumber(m.id, v)
                            item.widgetValue.text = format(v, m.step)
                        }

                        override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                        override fun onStopTrackingTouch(sb: SeekBar?) = Unit
                    })
                    container.addView(item.root)
                }

                WidgetStore.TYPE_BUTTON -> {
                    val item = ItemWidgetButtonBinding.inflate(inflater, container, false)
                    item.widgetButton.text = m.label
                    item.widgetButton.setOnClickListener {
                        WidgetStore.markClicked(m.id)
                    }
                    container.addView(item.root)
                }

                WidgetStore.TYPE_TOGGLE -> {
                    // 真正的 Switch 控件（带滑块动画）；整行也可点，
                    // Switch 自身设成不可点，避免和行点击冲突。
                    val item = ItemWidgetToggleBinding.inflate(inflater, container, false)
                    item.toggleLabel.text = m.label
                    item.toggleSwitch.isChecked = m.flag
                    item.toggleRow.setOnClickListener {
                        val next = !WidgetStore.boolValue(m.id)
                        WidgetStore.setFlag(m.id, next)
                        item.toggleSwitch.isChecked = next
                    }
                    // 网页拨开关时只改 Switch 状态
                    liveUpdaters[m.id] = {
                        item.toggleSwitch.isChecked = WidgetStore.boolValue(m.id)
                    }
                    container.addView(item.root)
                }

                WidgetStore.TYPE_TEXT_INPUT -> {
                    val item = ItemWidgetTextInputBinding.inflate(inflater, container, false)
                    item.inputLabel.text = m.label
                    item.inputLabel.visibility =
                        if (m.label.isBlank()) View.GONE else View.VISIBLE
                    inputViews[m.id] = item.inputEdit
                    item.inputEdit.setText(m.text)
                    item.inputEdit.setSelection(m.text.length)
                    item.inputEdit.maxHeight = dp(m.maxHeightDp)
                    item.inputEdit.addTextChangedListener(
                        object : android.text.TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                                // 实时回写给 Python，脚本读 .value 拿到的是最新内容
                                WidgetStore.setInputText(m.id, s?.toString() ?: "")
                            }

                            override fun afterTextChanged(s: android.text.Editable?) = Unit
                        }
                    )
                    item.inputConfirm.setOnClickListener {
                        WidgetStore.markSubmitted(m.id)
                    }
                    container.addView(item.root)
                }

                WidgetStore.TYPE_TEXT_OUTPUT -> {
                    val item = ItemWidgetTextOutputBinding.inflate(inflater, container, false)
                    item.outputText.text = m.text
                    outputViews[m.id] = item.outputText
                    outputScrolls[m.id] = item.outputScroll
                    outputMaxDp[m.id] = m.maxHeightDp
                    // 高度 = min(内容高度, 最大高度)；内容更多时靠 ScrollView 滚动
                    adjustOutputHeight(m.id)
                    container.addView(item.root)
                }

                WidgetStore.TYPE_JOYSTICK_1 -> {
                    val item = ItemWidgetJoystick1Binding.inflate(inflater, container, false)
                    item.stickLabel.text = m.label
                    item.stickLabel.visibility = if (m.label.isBlank()) View.GONE else View.VISIBLE
                    item.stick.normX = m.jx
                    item.stick.normY = m.jy
                    item.stick.onMove = { x, y ->
                        WidgetStore.setJoystick(m.id, x, y, false)
                        item.stickValue.text = requireContext().getString(R.string.joystick_value, x, y)
                    }
                    item.stickValue.text = requireContext().getString(R.string.joystick_value, m.jx, m.jy)
                    // 网页拖动摇杆时也只移动摇杆头，不重建
                    liveUpdaters[m.id] = {
                        item.stick.normX = WidgetStore.joystickX(m.id, false)
                        item.stick.normY = WidgetStore.joystickY(m.id, false)
                        item.stickValue.text = requireContext().getString(
                            R.string.joystick_value, item.stick.normX, item.stick.normY
                        )
                    }
                    container.addView(item.root)
                }

                WidgetStore.TYPE_JOYSTICK_2 -> {
                    val item = ItemWidgetJoystick2Binding.inflate(inflater, container, false)
                    item.stickLabel.text = m.label
                    item.stickLabel.visibility = if (m.label.isBlank()) View.GONE else View.VISIBLE
                    item.stickA.normX = m.jx
                    item.stickA.normY = m.jy
                    item.stickB.normX = m.jx2
                    item.stickB.normY = m.jy2
                    item.stickA.onMove = { x, y ->
                        WidgetStore.setJoystick(m.id, x, y, false)
                        item.stickAValue.text = getString(R.string.joystick_left, "%+.2f".format(x), "%+.2f".format(y))
                    }
                    item.stickB.onMove = { x, y ->
                        WidgetStore.setJoystick(m.id, x, y, true)
                        item.stickBValue.text = getString(R.string.joystick_right, "%+.2f".format(x), "%+.2f".format(y))
                    }
                    item.stickAValue.text = getString(R.string.joystick_left, "%+.2f".format(m.jx), "%+.2f".format(m.jy))
                    item.stickBValue.text = getString(R.string.joystick_right, "%+.2f".format(m.jx2), "%+.2f".format(m.jy2))
                    // 网页拖动摇杆时只移动摇杆头，不重建整块
                    liveUpdaters[m.id] = {
                        item.stickA.normX = WidgetStore.joystickX(m.id, false)
                        item.stickA.normY = WidgetStore.joystickY(m.id, false)
                        item.stickB.normX = WidgetStore.joystickX(m.id, true)
                        item.stickB.normY = WidgetStore.joystickY(m.id, true)
                        item.stickAValue.text = getString(
                            R.string.joystick_left,
                            "%+.2f".format(item.stickA.normX),
                            "%+.2f".format(item.stickA.normY)
                        )
                        item.stickBValue.text = getString(
                            R.string.joystick_right,
                            "%+.2f".format(item.stickB.normX),
                            "%+.2f".format(item.stickB.normY)
                        )
                    }
                    container.addView(item.root)
                }

                WidgetStore.TYPE_COMBO -> {
                    val item = ItemWidgetComboBinding.inflate(inflater, container, false)
                    item.comboLabel.text = m.label
                    item.comboLabel.visibility =
                        if (m.label.isBlank()) View.GONE else View.VISIBLE
                    comboValues[m.id] = item.comboValue
                    fun render() {
                        val list = WidgetStore.comboItems(m.id)
                        val idx = WidgetStore.comboIndex(m.id)
                        item.comboValue.text = list.getOrNull(idx) ?: getString(R.string.empty_value)
                    }
                    render()
                    // 网页改选中项时也只刷新这一行，不重建整块
                    liveUpdaters[m.id] = { render() }
                    item.comboRow.setOnClickListener {
                        val list = WidgetStore.comboItems(m.id)
                        if (list.isEmpty()) return@setOnClickListener
                        val current = WidgetStore.comboIndex(m.id)
                        androidx.appcompat.app.AlertDialog.Builder(container.context)
                            .setTitle(m.label.ifBlank { getString(R.string.please_choose) })
                            .setSingleChoiceItems(
                                list.toTypedArray(), current
                            ) { dialog, which ->
                                WidgetStore.setComboIndex(m.id, which)
                                render()
                                dialog.dismiss()
                            }
                            .setNegativeButton(getString(R.string.cancel), null)
                            .show()
                    }
                    container.addView(item.root)
                }

                WidgetStore.TYPE_PROGRESS -> {
                    container.addView(
                        WidgetViews.progress(container.context, m) { updater ->
                            liveUpdaters[m.id] = updater
                        }
                    )
                }

                WidgetStore.TYPE_IMAGE -> {
                    container.addView(
                        WidgetViews.image(container.context, m) { updater ->
                            liveUpdaters[m.id] = updater
                        }
                    )
                }

                WidgetStore.TYPE_PLOT -> {
                    container.addView(
                        WidgetViews.plot(container.context, m) { updater ->
                            liveUpdaters[m.id] = updater
                        }
                    )
                }

                WidgetStore.TYPE_CHECKBOX -> {
                    container.addView(
                        WidgetViews.checkbox(
                            container.context, m.label,
                            isChecked = { WidgetStore.boolValue(m.id) },
                            onToggle = {
                                WidgetStore.setFlag(m.id, !WidgetStore.boolValue(m.id))
                                liveUpdaters[m.id]?.invoke()
                            },
                            register = { updater -> liveUpdaters[m.id] = updater }
                        )
                    )
                }

                WidgetStore.TYPE_RADIO -> {
                    container.addView(
                        WidgetViews.radio(
                            container.context, m.label, m.items,
                            getIndex = { WidgetStore.comboIndex(m.id) },
                            onPick = { i ->
                                WidgetStore.setComboIndex(m.id, i)
                                liveUpdaters[m.id]?.invoke()
                            },
                            register = { updater -> liveUpdaters[m.id] = updater }
                        )
                    )
                }

                WidgetStore.TYPE_TEXT_FIELD -> {
                    val view = WidgetViews.textField(
                        container.context, m.label, m.text,
                        onText = { WidgetStore.setInputText(m.id, it) },
                        onSubmit = { WidgetStore.markSubmitted(m.id) }
                    )
                    container.addView(view)
                }

                WidgetStore.TYPE_DIVIDER -> {
                    container.addView(WidgetViews.divider(container.context))
                }

                WidgetStore.TYPE_HEADER -> {
                    container.addView(WidgetViews.header(container.context, m.text))
                }

                else -> {
                    val item = ItemWidgetLabelBinding.inflate(inflater, container, false)
                    item.widgetText.text = m.text
                    labelViews[m.id] = item.widgetText
                    container.addView(item.root)
                }
            }

            // 统一的 enabled 处理（visible 在过滤时已处理）
            container.getChildAt(container.childCount - 1)?.let { v ->
                v.isEnabled = m.enabled
                v.alpha = if (m.enabled) 1f else 0.4f
            }
        }
    }

    private fun format(v: Double, step: Double): String =
        if (step >= 1.0) "%.0f".format(v) else "%.2f".format(v)

    private fun toggleWidgets() {
        expanded = !expanded
        applyExpanded()
    }

    private fun applyExpanded() {
        binding.widgetScroll.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.widgetChevron.setImageResource(
            if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up
        )
    }

    // ------------------------------------------------------------ DisplaySink

    override fun onDisplayReady(width: Int, height: Int) {
        val container = _binding?.displayContainer ?: return
        val mode = Storage.displayScale(requireContext())
        container.post {
            val b = _binding ?: return@post
            val availW = b.displayContainer.width
            val availH = b.displayContainer.height
            if (availW <= 0 || availH <= 0 || width <= 0 || height <= 0) return@post

            if (mode != "fit") {
                // 裁切 / 拉伸：铺满整个显示区，具体由 ImageView 的 scaleType 决定
                b.displayView.scaleType = if (mode == "crop") {
                    android.widget.ImageView.ScaleType.CENTER_CROP
                } else {
                    android.widget.ImageView.ScaleType.FIT_XY
                }
                val lp = b.displayView.layoutParams
                if (lp.width != availW || lp.height != availH) {
                    lp.width = availW
                    lp.height = availH
                    b.displayView.layoutParams = lp
                }
                return@post
            }

            b.displayView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            val ratio = height.toFloat() / width.toFloat()
            val targetW: Int
            val targetH: Int
            if (availW * ratio <= availH) {
                targetW = availW
                targetH = (availW * ratio).toInt()
            } else {
                targetH = availH
                targetW = (availH / ratio).toInt()
            }
            val lp = b.displayView.layoutParams
            if (lp.width != targetW || lp.height != targetH) {
                lp.width = targetW
                lp.height = targetH
                b.displayView.layoutParams = lp
            }
        }
    }

    override fun onFrame(bitmap: Bitmap) {
        val b = _binding ?: return
        b.displayView.setImageBitmap(bitmap)
        b.displayPlaceholder.visibility = View.GONE
    }

    override fun onLog(line: String) {
        val b = _binding ?: return
        b.statusLine.text = line
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_APP = "app"
        private const val ARG_SCRIPT = "script"

        fun newInstance(appName: String?, scriptPath: String?): AppFragment {
            val f = AppFragment()
            f.arguments = Bundle().apply {
                putString(ARG_APP, appName)
                putString(ARG_SCRIPT, scriptPath)
            }
            return f
        }
    }
}
