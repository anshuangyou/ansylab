package com.ansylab.vision

import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 控件模型仓库：Python 建控件 → 这里存状态 → 界面按状态渲染。
 *
 * 约定：
 *  1. **控件创建是同步的**：`addXxx()` 返回时模型已经在表里，脚本可以立刻读 `.value`。
 *     只有界面刷新是异步的（post 到主线程）。
 *  2. 按钮用「取走即清零」的轮询语义（[takeClick]），因为 Python 侧是阻塞循环，
 *     没有稳定的回调时机；每帧查一次最省心。
 *  3. 高频变化（进度条、图片）走 [onLiveChanged] 就地更新，
 *     避免整块重建把其它控件的点击吃掉。
 */
object WidgetStore {

    const val TYPE_SLIDER = "slider"
    const val TYPE_BUTTON = "button"
    const val TYPE_TOGGLE = "toggle"
    const val TYPE_LABEL = "label"
    const val TYPE_DISPLAY = "display"
    const val TYPE_TEXT_INPUT = "text_input"
    const val TYPE_TEXT_OUTPUT = "text_output"
    const val TYPE_JOYSTICK_1 = "joystick1"
    const val TYPE_JOYSTICK_2 = "joystick2"
    const val TYPE_COMBO = "combo"

    // 第二批控件
    const val TYPE_PROGRESS = "progress"
    const val TYPE_PLOT = "plot"
    const val TYPE_IMAGE = "image"
    const val TYPE_CHECKBOX = "checkbox"
    const val TYPE_RADIO = "radio"
    const val TYPE_TEXT_FIELD = "text_field"
    const val TYPE_DIVIDER = "divider"
    const val TYPE_HEADER = "header"

    const val REGION_MAIN = "main"
    const val REGION_PANEL = "panel"

    class Model(
        val id: String,
        val type: String,
        val region: String,
        @Volatile var label: String,
        @Volatile var min: Double = 0.0,
        @Volatile var max: Double = 1.0,
        @Volatile var step: Double = 0.01,
        @Volatile var number: Double = 0.0,
        @Volatile var flag: Boolean = false,
        @Volatile var text: String = "",
        /** 多行/列表控件的最大高度（dp）。 */
        @Volatile var maxHeightDp: Int = 0,
        @Volatile var jx: Float = 0f,
        @Volatile var jy: Float = 0f,
        @Volatile var jx2: Float = 0f,
        @Volatile var jy2: Float = 0f,
        /** 下拉/单选/列表的选项。 */
        @Volatile var items: List<String> = emptyList(),
        @Volatile var index: Int = 0,
        /** 所有控件通用。 */
        @Volatile var visible: Boolean = true,
        @Volatile var enabled: Boolean = true,
        @Volatile var indeterminate: Boolean = false,
        @Volatile var imageBytes: ByteArray? = null,
        /** 折线图：最多保留多少个点、几条曲线。 */
        @Volatile var plotMax: Int = 200,
        @Volatile var plotChannels: Int = 1,
        /** Y 轴锁定范围；两个都为 null 时自动缩放。 */
        @Volatile var plotYMin: Float? = null,
        @Volatile var plotYMax: Float? = null,
        /** >0 时按时间窗口显示（秒），0 表示按点数。 */
        @Volatile var plotWindowSec: Float = 0f
    ) {
        /** 折线图数据，每项是 [x] / [x,y] / [x,y,z]。 */
        val plotData = java.util.ArrayDeque<FloatArray>()

        /** 与 plotData 对应的时间戳（毫秒），用于时间窗口。 */
        val plotTimes = java.util.ArrayDeque<Long>()
        @Volatile
        var clicked: Boolean = false

        @Volatile
        var submitted: Boolean = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val items = Collections.synchronizedMap(LinkedHashMap<String, Model>())
    private val seq = AtomicInteger(0)

    @Volatile
    var onChanged: (() -> Unit)? = null

    @Volatile
    var onTextChanged: ((String, String) -> Unit)? = null

    /** 高频数值变化（进度条 / 图片）就地更新，不触发整块重建。 */
    @Volatile
    var onLiveChanged: ((String) -> Unit)? = null

    /**
     * 控件内容版本号：任何变化都会 +1。
     *
     * 网页推送靠它判断「有没有变」，不用每次重建整份 JSON。
     */
    @Volatile
    var revision: Long = 0L
        private set

    private fun bump() {
        revision += 1
    }

    // ------------------------------------------------------------ 基础设施

    private fun nextId(): String = "w" + seq.incrementAndGet()

    private fun normRegion(region: String): String =
        if (region == REGION_MAIN) REGION_MAIN else REGION_PANEL

    /** 同步建模型 + 异步刷新界面。 */
    private fun create(model: Model): String {
        synchronized(items) { items[model.id] = model }
        bump()
        bump()
    main.post { onChanged?.invoke() }
        return model.id
    }

    fun clear() {
        val run = {
            synchronized(items) {
                items.clear()
                seq.set(0)
            }
            onChanged?.invoke()
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run() else main.post(run)
    }

    fun snapshot(): List<Model> = synchronized(items) { items.values.toList() }

    fun snapshot(region: String): List<Model> =
        synchronized(items) { items.values.filter { it.region == region } }

    fun hasDisplay(): Boolean =
        synchronized(items) { items.values.any { it.type == TYPE_DISPLAY } }

    fun find(id: String): Model? = synchronized(items) { items[id] }

    // ------------------------------------------------------------ 创建控件

    fun addSlider(
        label: String, min: Double, max: Double, step: Double, value: Double,
        region: String = REGION_PANEL
    ): String = create(
        Model(
            id = nextId(), type = TYPE_SLIDER, region = normRegion(region), label = label,
            min = min, max = if (max > min) max else min + 1e-6,
            step = if (step > 0) step else 0.01,
            number = value.coerceIn(min, max)
        )
    )

    fun addButton(label: String, region: String = REGION_PANEL): String = create(
        Model(id = nextId(), type = TYPE_BUTTON, region = normRegion(region), label = label)
    )

    fun addToggle(label: String, value: Boolean, region: String = REGION_PANEL): String = create(
        Model(
            id = nextId(), type = TYPE_TOGGLE, region = normRegion(region),
            label = label, flag = value
        )
    )

    fun addLabel(text: String, region: String = REGION_PANEL): String = create(
        Model(
            id = nextId(), type = TYPE_LABEL, region = normRegion(region),
            label = text, text = text
        )
    )

    fun addTextInput(
        label: String, value: String, maxHeightDp: Int, region: String = REGION_PANEL
    ): String = create(
        Model(
            id = nextId(), type = TYPE_TEXT_INPUT, region = normRegion(region),
            label = label, text = value,
            maxHeightDp = if (maxHeightDp > 0) maxHeightDp else 120
        )
    )

    fun addTextOutput(
        text: String, maxHeightDp: Int, region: String = REGION_PANEL
    ): String = create(
        Model(
            id = nextId(), type = TYPE_TEXT_OUTPUT, region = normRegion(region),
            label = text, text = text,
            maxHeightDp = if (maxHeightDp > 0) maxHeightDp else 160
        )
    )

    fun addJoystick(label: String, region: String = REGION_PANEL): String = create(
        Model(id = nextId(), type = TYPE_JOYSTICK_1, region = normRegion(region), label = label)
    )

    fun addDoubleJoystick(label: String, region: String = REGION_PANEL): String = create(
        Model(id = nextId(), type = TYPE_JOYSTICK_2, region = normRegion(region), label = label)
    )

    fun addCombo(
        label: String, items: List<String>, index: Int, region: String = REGION_PANEL
    ): String {
        val list = items.toList()
        val safe = if (list.isEmpty()) 0 else index.coerceIn(0, list.size - 1)
        return create(
            Model(
                id = nextId(), type = TYPE_COMBO, region = normRegion(region),
                label = label, text = list.getOrNull(safe) ?: "",
                items = list, index = safe
            )
        )
    }

    fun addProgress(
        label: String, value: Double, max: Double, indeterminate: Boolean,
        region: String = REGION_PANEL
    ): String = create(
        Model(
            id = nextId(), type = TYPE_PROGRESS, region = normRegion(region),
            label = label, number = value, min = 0.0,
            max = if (max <= 0.0) 100.0 else max,
            indeterminate = indeterminate
        )
    )

    fun addImage(label: String, heightDp: Int, region: String = REGION_MAIN): String = create(
        Model(
            id = nextId(), type = TYPE_IMAGE, region = normRegion(region),
            label = label, maxHeightDp = if (heightDp > 0) heightDp else 200
        )
    )

    fun addCheckbox(label: String, checked: Boolean, region: String = REGION_PANEL): String = create(
        Model(
            id = nextId(), type = TYPE_CHECKBOX, region = normRegion(region),
            label = label, flag = checked
        )
    )

    fun addRadio(
        label: String, options: List<String>, index: Int, region: String = REGION_PANEL
    ): String {
        val list = options.toList()
        val safe = if (list.isEmpty()) 0 else index.coerceIn(0, list.size - 1)
        return create(
            Model(
                id = nextId(), type = TYPE_RADIO, region = normRegion(region),
                label = label, items = list, index = safe,
                text = list.getOrNull(safe) ?: ""
            )
        )
    }

    fun addTextField(
        label: String, value: String, region: String = REGION_PANEL
    ): String = create(
        Model(
            id = nextId(), type = TYPE_TEXT_FIELD, region = normRegion(region),
            label = label, text = value
        )
    )


    fun addDivider(region: String = REGION_PANEL): String = create(
        Model(id = nextId(), type = TYPE_DIVIDER, region = normRegion(region), label = "")
    )

    fun addHeader(text: String, region: String = REGION_PANEL): String = create(
        Model(
            id = nextId(), type = TYPE_HEADER, region = normRegion(region),
            label = text, text = text
        )
    )

    /** 视频显示控件。整个 App 只支持一个，重复创建会复用已有 id。 */
    fun addPlot(
        label: String, points: Int, channels: Int, region: String = REGION_PANEL,
        yMin: Double? = null, yMax: Double? = null, windowSec: Double = 0.0
    ): String = create(
        Model(
            id = nextId(), type = TYPE_PLOT, region = normRegion(region),
            label = label,
            maxHeightDp = 0,
            plotMax = if (points > 1) points else 200,
            plotChannels = channels.coerceIn(1, 5),
            plotYMin = yMin?.toFloat(),
            plotYMax = yMax?.toFloat(),
            plotWindowSec = if (windowSec > 0) windowSec.toFloat() else 0f
        )
    )

    fun addDisplay(region: String = REGION_MAIN): String {
        synchronized(items) {
            items.values.firstOrNull { it.type == TYPE_DISPLAY }?.let { return it.id }
        }
        return create(
            Model(id = nextId(), type = TYPE_DISPLAY, region = normRegion(region), label = "")
        )
    }

    // ------------------------------------------------------------ 事件读取

    fun value(id: String): Double = find(id)?.number ?: 0.0

    fun boolValue(id: String): Boolean = find(id)?.flag ?: false

    fun textValue(id: String): String = find(id)?.text ?: ""

    /** 按钮：读一次就把「被点过」标记清掉。 */
    fun takeClick(id: String): Boolean {
        val m = find(id) ?: return false
        if (!m.clicked) return false
        m.clicked = false
        return true
    }

    /** 输入框：读一次就把「点过确定」标记清掉。 */
    fun takeSubmit(id: String): Boolean {
        val m = find(id) ?: return false
        if (!m.submitted) return false
        m.submitted = false
        return true
    }

    fun joystickX(id: String, second: Boolean = false): Float {
        val m = find(id) ?: return 0f
        return if (second) m.jx2 else m.jx
    }

    fun joystickY(id: String, second: Boolean = false): Float {
        val m = find(id) ?: return 0f
        return if (second) m.jy2 else m.jy
    }

    fun comboIndex(id: String): Int = find(id)?.index ?: 0

    fun comboText(id: String): String {
        val m = find(id) ?: return ""
        return m.items.getOrNull(m.index) ?: ""
    }

    fun comboItems(id: String): List<String> = find(id)?.items ?: emptyList()

    fun progressValue(id: String): Double = find(id)?.number ?: 0.0

    // ------------------------------------------------------------ 界面 → 状态

    fun setNumber(id: String, v: Double) {
        find(id)?.number = v
    }

    fun setFlag(id: String, v: Boolean) {
        find(id)?.flag = v
    }

    fun markClicked(id: String) {
        find(id)?.clicked = true
    }

    fun markSubmitted(id: String) {
        find(id)?.submitted = true
    }

    fun setJoystick(id: String, x: Float, y: Float, second: Boolean) {
        val m = find(id) ?: return
        if (second) {
            m.jx2 = x
            m.jy2 = y
        } else {
            m.jx = x
            m.jy = y
        }
    }

    /** 外部（网页）改摇杆位置：就地移动摇杆头，不整块重建。 */
    fun setJoystickLive(id: String, x: Float, y: Float, second: Boolean) {
        setJoystick(id, x, y, second)
        bump()
        main.post { onLiveChanged?.invoke(id) }
    }

    fun setInputText(id: String, text: String) {
        find(id)?.text = text
    }

    /** 界面里选了某一项（下拉/单选/列表通用）。 */
    fun setComboIndex(id: String, index: Int) {
        val m = find(id) ?: return
        val clamped = if (m.items.isEmpty()) 0 else index.coerceIn(0, m.items.size - 1)
        m.index = clamped
        m.text = m.items.getOrNull(clamped) ?: ""
    }

    // ------------------------------------------------------------ 脚本 → 状态

    /** Python 改文字：模型同步更新（脚本立刻读得到），界面就地刷新。 */
    fun setText(id: String, text: String) {
        val m = find(id) ?: return
        // 内容没变就不刷新：脚本常常每帧都 set 一次相同的状态文字，
        // 无条件刷新会让整块区域反复重排，看起来就是在闪。
        if (m.text == text) return
        m.text = text
        m.label = text
        bump()
        main.post { onTextChanged?.invoke(id, text) }
    }

    fun setComboItems(id: String, newItems: List<String>, index: Int) {
        val m = find(id) ?: return
        val list = newItems.toList()
        m.items = list
        m.index = if (list.isEmpty()) 0 else index.coerceIn(0, list.size - 1)
        m.text = list.getOrNull(m.index) ?: ""
        bump()
        bump()
    main.post { onChanged?.invoke() }
    }

    /** 折线图推入一个点（脚本主循环里调用）。 */
    fun pushPlot(id: String, values: FloatArray) {
        val m = find(id) ?: return
        synchronized(m) {
            val now = android.os.SystemClock.elapsedRealtime()
            m.plotData.addLast(values)
            m.plotTimes.addLast(now)
            // 时间窗口：超出的点在这里就丢掉，避免把整条曲线一直留在内存里
            if (m.plotWindowSec > 0f) {
                val cutoff = now - (m.plotWindowSec * 1000).toLong()
                while (m.plotTimes.size > 1 && m.plotTimes.first() < cutoff) {
                    m.plotTimes.removeFirst()
                    if (m.plotData.isNotEmpty()) m.plotData.removeFirst()
                }
            }
            while (m.plotData.size > m.plotMax) {
                m.plotData.removeFirst()
                if (m.plotTimes.isNotEmpty()) m.plotTimes.removeFirst()
            }
        }
        bump()
        main.post { onLiveChanged?.invoke(id) }
    }

    /** 取 (values, times) 快照，渲染/导出用。 */
    fun plotTimes(id: String): List<Long> {
        val m = find(id) ?: return emptyList()
        synchronized(m) { return m.plotTimes.toList() }
    }

    /** 清空折线图数据。 */
    fun clearPlot(id: String) {
        val m = find(id) ?: return
        synchronized(m) {
            m.plotData.clear()
            m.plotTimes.clear()
        }
        bump()
        main.post { onLiveChanged?.invoke(id) }
    }

    /** 取折线图数据的快照（渲染用）。 */
    fun plotSnapshot(id: String): List<FloatArray> {
        val m = find(id) ?: return emptyList()
        synchronized(m) { return m.plotData.toList() }
    }

    /** 外部（网页）改开关/勾选：就地刷新，不整块重建。 */
    fun setFlagLive(id: String, v: Boolean) {
        setFlag(id, v)
        bump()
        main.post { onLiveChanged?.invoke(id) }
    }

    /** 外部（网页）拖滑块：就地刷新，不整块重建。 */
    fun setNumberLive(id: String, v: Double) {
        setNumber(id, v)
        bump()
        main.post { onLiveChanged?.invoke(id) }
    }

    /**
     * 外部（网页）改选中项：就地刷新那一行，不整块重建。
     *
     * 这里不能调 onChanged：那会重建整个控件区，
     * 固定区是 ScrollView，重建后滚动位置被重置 —— 看起来就是画面抖一下。
     */
    fun setIndex(id: String, index: Int) {
        setComboIndex(id, index)
        bump()
        main.post { onLiveChanged?.invoke(id) }
    }

    fun setProgress(id: String, value: Double, indeterminate: Boolean? = null) {
        val m = find(id) ?: return
        m.number = value.coerceIn(0.0, m.max)
        if (indeterminate != null) m.indeterminate = indeterminate
        bump()
        main.post { onLiveChanged?.invoke(id) }
    }

    fun setImageBytes(id: String, bytes: ByteArray) {
        val m = find(id) ?: return
        m.imageBytes = bytes
        bump()
        main.post { onLiveChanged?.invoke(id) }
    }

    fun setVisible(id: String, v: Boolean) {
        val m = find(id) ?: return
        if (m.visible == v) return
        m.visible = v
        bump()
        bump()
    main.post { onChanged?.invoke() }
    }

    fun setEnabled(id: String, v: Boolean) {
        val m = find(id) ?: return
        if (m.enabled == v) return
        m.enabled = v
        bump()
        bump()
    main.post { onChanged?.invoke() }
    }

    fun setLabelOf(id: String, label: String) {
        val m = find(id) ?: return
        m.label = label
        when (m.type) {
            TYPE_LABEL, TYPE_HEADER -> {
                m.text = label
                bump()
        main.post { onTextChanged?.invoke(id, label) }
            }
            else -> { bump(); main.post { onChanged?.invoke() } }
        }
    }

    fun remove(id: String) {
        synchronized(items) { items.remove(id) }
        bump()
        bump()
    main.post { onChanged?.invoke() }
    }


}
