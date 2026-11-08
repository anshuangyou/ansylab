package com.ansylab.vision

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.ansylab.vision.databinding.ItemSettingRowBinding

/** 设置列表的行构造：统一处理分隔线、右侧值和箭头。 */
object SettingsUi {

    private fun inflater(container: LinearLayout) =
        LayoutInflater.from(container.context)

    /**
     * 加一行可点击的设置项。
     *
     * @param value 右侧灰色当前值，null 时不显示
     * @param chevron 是否显示右侧箭头
     */
    fun addRow(
        container: LinearLayout,
        title: String,
        value: String? = null,
        chevron: Boolean = true,
        onClick: (() -> Unit)? = null
    ): View {
        if (container.childCount > 0) addDivider(container)
        val item = ItemSettingRowBinding.inflate(inflater(container), container, false)
        item.rowTitle.text = title
        if (value.isNullOrEmpty()) {
            item.rowValue.visibility = View.GONE
        } else {
            item.rowValue.visibility = View.VISIBLE
            item.rowValue.text = value
        }
        item.rowChevron.visibility = if (chevron) View.VISIBLE else View.GONE
        if (onClick != null) {
            item.rowRoot.setOnClickListener { onClick() }
        } else {
            item.rowRoot.isClickable = false
            item.rowRoot.isFocusable = false
        }
        container.addView(item.root)
        return item.root
    }

    /** 只读的「名称 / 值」行（设备信息用，没有箭头、不可点）。 */
    fun addValueRow(container: LinearLayout, name: String, value: String) {
        addRow(container, name, value, chevron = false, onClick = null)
    }

    /** 带开关的一行：整行可点，右侧是 Switch。 */
    fun addSwitchRow(
        container: LinearLayout,
        title: String,
        checked: Boolean,
        onToggle: (Boolean) -> Unit
    ) {
        if (container.childCount > 0) addDivider(container)
        val item = com.ansylab.vision.databinding.ItemSettingSwitchBinding
            .inflate(inflater(container), container, false)
        item.rowTitle.text = title
        item.rowSwitch.isChecked = checked
        item.rowRoot.setOnClickListener {
            val next = !item.rowSwitch.isChecked
            item.rowSwitch.isChecked = next
            onToggle(next)
        }
        container.addView(item.root)
    }

    fun addDivider(container: LinearLayout) {
        val ctx = container.context
        val line = View(ctx)
        line.setBackgroundColor(ctx.getColor(R.color.line))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (1 * ctx.resources.displayMetrics.density).toInt()
        )
        lp.marginStart = (16 * ctx.resources.displayMetrics.density).toInt()
        container.addView(line, lp)
    }
}
