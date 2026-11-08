package com.ansylab.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 折线图：最多三条曲线（x / y / z），自动缩放。
 *
 * 只负责画，数据由 WidgetStore 里的 Model 提供：
 * 每次 push 后调用 [setData] 重绘。
 */
class PlotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
        color = context.getColor(R.color.line)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = context.getColor(R.color.line)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink_secondary)
        textSize = dp(10f)
    }
    private val linePaints = arrayOf(
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.6f)
            color = 0xFF111111.toInt()
            isAntiAlias = true
        },
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.6f)
            color = 0xFFE05252.toInt()
            isAntiAlias = true
        },
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.6f)
            color = 0xFF4A90D9.toInt()
            isAntiAlias = true
        },
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.6f)
            color = 0xFF2E9E5B.toInt()
            isAntiAlias = true
        },
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.6f)
            color = 0xFFD98324.toInt()
            isAntiAlias = true
        }
    )

    private var data: List<FloatArray> = emptyList()
    private var channels = 1
    private var maxPoints = 200
    private var yMinFixed: Float? = null
    private var yMaxFixed: Float? = null
    private var windowSec = 0f
    private var times: List<Long> = emptyList()
    private val path = Path()

    private fun dp(v: Float) = v * resources.displayMetrics.density

    /** 传进来的每项是 [x] 或 [x,y] 或 [x,y,z]。 */
    fun setData(
        points: List<FloatArray>, channelCount: Int, window: Int = 0,
        yMin: Float? = null, yMax: Float? = null, windowSeconds: Float = 0f,
        timeStamps: List<Long> = emptyList()
    ) {
        // 时间窗口：只保留最近 windowSeconds 秒内的点
        if (windowSeconds > 0f && timeStamps.size == points.size && timeStamps.isNotEmpty()) {
            val cutoff = timeStamps.last() - (windowSeconds * 1000).toLong()
            var i = 0
            while (i < timeStamps.size && timeStamps[i] < cutoff) i++
            data = if (i > 0) points.subList(i, points.size) else points
            times = if (i > 0) timeStamps.subList(i, timeStamps.size) else timeStamps
        } else {
            data = points
            times = timeStamps
        }
        channels = channelCount.coerceIn(1, 5)
        if (window > 1) maxPoints = window
        yMinFixed = yMin
        yMaxFixed = yMax
        windowSec = windowSeconds
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val padL = dp(6f)
        val padR = dp(6f)
        val padT = dp(16f)
        val padB = dp(6f)
        val w = width - padL - padR
        val h = height - padT - padB
        if (w <= 0 || h <= 0) return

        // 网格 + 外框
        for (i in 1..3) {
            val y = padT + h * i / 4f
            canvas.drawLine(padL, y, padL + w, y, gridPaint)
        }
        canvas.drawRect(padL, padT, padL + w, padT + h, borderPaint)

        if (data.size < 2) {
            canvas.drawText("等待数据…", padL + dp(6f), padT + dp(14f), textPaint)
            return
        }

        // 自适应范围
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (p in data) {
            for (c in 0 until min(channels, p.size)) {
                lo = min(lo, p[c])
                hi = max(hi, p[c])
            }
        }
        // Y 轴锁定范围优先
        val fy0 = yMinFixed
        val fy1 = yMaxFixed
        if (fy0 != null && fy1 != null && fy1 > fy0) {
            lo = fy0
            hi = fy1
        }
        // 最小跨度：陀螺仪静止时噪声很小，不设下限会被放大成满屏方波
        val minSpan = if (fy0 != null && fy1 != null) 1e-6f else 0.5f
        if (hi - lo < minSpan) {
            val mid = (hi + lo) / 2f
            lo = mid - minSpan / 2f
            hi = mid + minSpan / 2f
        }
        val pad = (hi - lo) * 0.12f
        lo -= pad
        hi += pad
        val span = hi - lo

        // X 轴：时间窗口模式下按实际点数铺满，否则按 maxPoints 固定窗口
        val spanX = if (windowSec > 0f) (data.size - 1).coerceAtLeast(1).toFloat()
                    else (maxPoints - 1).coerceAtLeast(1).toFloat()
        val stepX = w / spanX

        for (c in 0 until channels) {
            path.reset()
            var started = false
            for (i in data.indices) {
                val p = data[i]
                if (c >= p.size) continue
                val px = padL + i * stepX
                val py = padT + h - (p[c] - lo) / span * h
                if (!started) {
                    path.moveTo(px, py)
                    started = true
                } else {
                    path.lineTo(px, py)
                }
            }
            if (started) canvas.drawPath(path, linePaints[c])
        }

        // 右上角显示最新值
        val lastPoint = data.last()
        val sb = StringBuilder()
        val names = arrayOf("x", "y", "z", "c4", "c5")
        for (c in 0 until min(channels, lastPoint.size)) {
            if (c > 0) sb.append("  ")
            sb.append(names[c]).append('=')
            sb.append(String.format("%.2f", lastPoint[c]))
        }
        canvas.drawText(sb.toString(), padL + dp(4f), padT - dp(5f), textPaint)
    }
}
