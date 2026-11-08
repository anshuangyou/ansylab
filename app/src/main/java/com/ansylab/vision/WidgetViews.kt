package com.ansylab.vision

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 第二批控件的视图构建（用代码拼，省掉一堆 layout 文件）。
 *
 * 统一风格：白底 + 1dp 描边 + 10dp 圆角 + 16dp 内边距，和第一版控件一致。
 */
object WidgetViews {

    private fun dp(ctx: Context, v: Float): Int =
        (v * ctx.resources.displayMetrics.density).roundToInt()

    /** 系统的按压反馈背景（selectableItemBackground）。 */
    private fun selectableForeground(ctx: Context): android.graphics.drawable.Drawable? {
        val tv = android.util.TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return androidx.core.content.ContextCompat.getDrawable(ctx, tv.resourceId)
    }

    private fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = ctx.getDrawable(R.drawable.bg_widget_row)
        setPadding(dp(ctx, 14f), dp(ctx, 10f), dp(ctx, 14f), dp(ctx, 10f))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(ctx, 6f)
        layoutParams = lp
    }

    private fun title(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(ctx.getColor(R.color.ink))
        textSize = 14f
        visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun valueText(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(ctx.getColor(R.color.ink_secondary))
        textSize = 12f
    }

    // ------------------------------------------------------------ 进度条
    /**
     * 进度条。@param register 传入一个「就地刷新」lambda，用于每帧更新数值
     * 而不重建整个控件区（重建会导致其它控件点击丢失）。
     */
    fun progress(ctx: Context, m: WidgetStore.Model, register: (() -> Unit) -> Unit): View {
        val root = card(ctx)
        root.addView(title(ctx, m.label))
        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = m.max.toInt().coerceAtLeast(1)
            progress = m.number.toInt()
            isIndeterminate = m.indeterminate
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 8f)
            )
            lp.topMargin = dp(ctx, if (m.label.isBlank()) 2f else 8f)
            layoutParams = lp
        }
        root.addView(bar)
        val valueView = valueText(ctx, "").apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(ctx, 4f)
            layoutParams = lp
            gravity = Gravity.END
            visibility = if (m.indeterminate) View.GONE else View.VISIBLE
        }
        root.addView(valueView)

        register {
            bar.isIndeterminate = m.indeterminate
            if (!m.indeterminate) {
                bar.progress = m.number.toInt()
                valueView.visibility = View.VISIBLE
                valueView.text = "%.0f / %.0f".format(m.number, m.max)
            } else {
                valueView.visibility = View.GONE
            }
        }
        // 初始值也走一次同样的逻辑
        bar.progress = m.number.toInt()
        valueView.text = if (m.indeterminate) "" else "%.0f / %.0f".format(m.number, m.max)
        return root
    }

    // ------------------------------------------------------------ 图片
    fun image(ctx: Context, m: WidgetStore.Model, register: (() -> Unit) -> Unit): View {
        val root = card(ctx)
        if (m.label.isNotBlank()) root.addView(title(ctx, m.label))
        val iv = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, m.maxHeightDp.toFloat())
            )
            lp.topMargin = dp(ctx, if (m.label.isBlank()) 0f else 6f)
            layoutParams = lp
        }
        root.addView(iv)

        fun applyImage() {
            val bytes = m.imageBytes
            if (bytes == null) {
                iv.setBackgroundColor(ctx.getColor(R.color.surface))
                iv.setImageDrawable(null)
            } else {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) iv.setImageBitmap(bmp)
            }
        }
        register { applyImage() }
        applyImage()
        return root
    }

    // ------------------------------------------------------------ 勾选框
    /** 勾选框：点击只改这一行，不重建整块（重建会导致画面抽搐）。 */
    fun checkbox(
        ctx: Context, label: String, isChecked: () -> Boolean,
        onToggle: () -> Unit, register: (() -> Unit) -> Unit
    ): View {
        val tv = TextView(ctx).apply {
            setTextColor(ctx.getColor(R.color.ink))
            textSize = 14f
            background = ctx.getDrawable(R.drawable.bg_clickable_row)
            setPadding(dp(ctx, 14f), dp(ctx, 14f), dp(ctx, 14f), dp(ctx, 14f))
            isClickable = true
            isFocusable = false
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(ctx, 6f)
            layoutParams = lp
            setOnClickListener { onToggle() }
        }
        fun render() {
            tv.text = "%s    %s".format(if (isChecked()) "☑" else "☐", label)
        }
        register { render() }
        render()
        return tv
    }

    // ------------------------------------------------------------ 单选组
    /** 单选组：点选只刷新本组的圆点与颜色，不重建整块。 */
    fun radio(
        ctx: Context, label: String, options: List<String>, getIndex: () -> Int,
        onPick: (Int) -> Unit, register: (() -> Unit) -> Unit
    ): View {
        val root = card(ctx)
        if (label.isNotBlank()) root.addView(title(ctx, label))
        val rows = ArrayList<TextView>()
        fun render() {
            val idx = getIndex()
            rows.forEachIndexed { i, tv ->
                tv.text = "%s %s".format(if (i == idx) "◉" else "○", options[i])
                tv.setTextColor(ctx.getColor(if (i == idx) R.color.ink else R.color.ink_secondary))
            }
        }
        options.forEachIndexed { i, opt ->
            val row = TextView(ctx).apply {
                setTextColor(ctx.getColor(R.color.ink_secondary))
                textSize = 13f
                setPadding(0, dp(ctx, 6f), 0, dp(ctx, 6f))
                isClickable = true
                isFocusable = false
                foreground = selectableForeground(ctx)
                setOnClickListener { onPick(i) }
            }
            rows.add(row)
            root.addView(row)
        }
        register { render() }
        render()
        return root
    }

    // ------------------------------------------------------------ 单行输入
    fun textField(
        ctx: Context, label: String, value: String,
        onText: (String) -> Unit, onSubmit: () -> Unit
    ): View {
        val root = card(ctx)
        if (label.isNotBlank()) root.addView(title(ctx, label))

        // 一行：左边输入框（占满剩余宽度）+ 右边「确定」按钮
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(ctx, if (label.isBlank()) 0f else 6f)
            layoutParams = lp
        }
        val et = EditText(ctx).apply {
            setText(value)
            setSelection(value.length)
            setTextColor(ctx.getColor(R.color.ink))
            textSize = 14f
            background = null
            maxLines = 1
            isSingleLine = true
            hint = "输入内容"
            setHintTextColor(ctx.getColor(R.color.ink_disabled))
            setPadding(0, dp(ctx, 8f), 0, dp(ctx, 8f))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    onText(s?.toString() ?: "")
                }

                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
            // 回车也能提交（软键盘「完成」/物理回车）
            setOnEditorActionListener { _, _, _ ->
                onSubmit()
                true
            }
        }
        val confirm = TextView(ctx).apply {
            text = "确定"
            setTextColor(ctx.getColor(R.color.ink))
            textSize = 14f
            gravity = Gravity.CENTER
            background = ctx.getDrawable(R.drawable.bg_clickable_row)
            isClickable = true
            isFocusable = false
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(ctx, 40f)
            )
            lp.leftMargin = dp(ctx, 8f)
            layoutParams = lp
            setPadding(dp(ctx, 16f), 0, dp(ctx, 16f), 0)
            setOnClickListener { onSubmit() }
        }
        row.addView(et)
        row.addView(confirm)
        root.addView(row)
        return root
    }

    // ------------------------------------------------------------ 分隔线 / 标题
    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(ctx.getColor(R.color.line))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1f))
        lp.topMargin = dp(ctx, 4f)
        lp.bottomMargin = dp(ctx, 10f)
        layoutParams = lp
    }

    fun header(ctx: Context, text: String): View = TextView(ctx).apply {
        this.text = text
        setTextColor(ctx.getColor(R.color.ink))
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(ctx, 4f), dp(ctx, 10f), dp(ctx, 4f), dp(ctx, 6f))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        layoutParams = lp
    }


    // ------------------------------------------------------------ 折线图
    fun plot(
        ctx: Context, m: WidgetStore.Model, register: (() -> Unit) -> Unit
    ): View {
        val root = card(ctx)
        if (m.label.isNotBlank()) root.addView(title(ctx, m.label))
        val pv = PlotView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 140f)
            )
        }
        root.addView(pv)
        fun apply() {
            pv.setData(
                WidgetStore.plotSnapshot(m.id), m.plotChannels, m.plotMax,
                m.plotYMin, m.plotYMax, m.plotWindowSec,
                WidgetStore.plotTimes(m.id)
            )
        }
        register { apply() }
        apply()
        return root
    }

}
