package com.ansylab.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * 一个极简摇杆：底盘圆 + 可拖动的摇杆头。
 *
 * 输出坐标归一化到 [-1, 1]：
 *   x 向右为正，y 向上为正（和常见的数学坐标一致，屏幕坐标是反的）。
 * 松手自动回中。
 */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = context.getColor(R.color.ink)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = context.getColor(R.color.line)
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.ink)
    }

    /** 归一化坐标，x/y ∈ [-1, 1]。外部写入只用于恢复状态。 */
    var normX: Float = 0f
        set(value) {
            field = value
            updateKnob()
        }

    var normY: Float = 0f
        set(value) {
            field = value
            updateKnob()
        }

    /** 拖动时回调（主线程）。 */
    var onMove: ((Float, Float) -> Unit)? = null

    private val knob = PointF()
    private var active = false

    init {
        isClickable = true
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = min(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        ).coerceAtLeast(dp(80f).toInt())
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateKnob()
    }

    private fun radius() = min(width, height) / 2f
    private fun knobRadius() = radius() * 0.32f

    private fun updateKnob() {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = radius() - knobRadius() - dp(2f)
        knob.set(cx + normX * maxR, cy - normY * maxR)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = radius() - dp(1f)

        canvas.drawCircle(cx, cy, r, basePaint)
        // 十字参考线
        canvas.drawLine(cx - r, cy, cx + r, cy, crossPaint)
        canvas.drawLine(cx, cy - r, cx, cy + r, crossPaint)
        // 摇杆头
        canvas.drawCircle(knob.x, knob.y, knobRadius(), knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (!active && event.actionMasked == MotionEvent.ACTION_DOWN) {
                    active = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                val cx = width / 2f
                val cy = height / 2f
                val maxR = radius() - knobRadius() - dp(2f)
                var dx = event.x - cx
                var dy = event.y - cy
                val dist = hypot(dx, dy)
                if (dist > maxR && dist > 0f) {
                    dx = dx / dist * maxR
                    dy = dy / dist * maxR
                }
                normX = if (maxR > 0f) (dx / maxR).coerceIn(-1f, 1f) else 0f
                normY = if (maxR > 0f) (-dy / maxR).coerceIn(-1f, 1f) else 0f
                updateKnob()
                onMove?.invoke(normX, normY)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = false
                normX = 0f
                normY = 0f
                updateKnob()
                onMove?.invoke(0f, 0f)
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
