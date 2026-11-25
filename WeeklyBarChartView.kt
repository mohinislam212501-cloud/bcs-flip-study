package com.bcs.flipstudy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class WeeklyBarChartView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private var bars: List<ChartBar> = emptyList()

    private val studyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0F766E") }
    private val wastePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E11D48") }
    private val sleepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4F46E5") }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#57534E")
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E7E5E4")
        strokeWidth = 2f
    }

    fun setBars(list: List<ChartBar>) {
        bars = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bars.isEmpty()) return

        val padTop = 20f
        val padBottom = 48f
        val padSide = 14f
        val chartH = (height - padTop - padBottom).coerceAtLeast(40f)
        val chartW = (width - padSide * 2).coerceAtLeast(100f)

        for (i in 0..3) {
            val y = padTop + (chartH * i / 3f)
            canvas.drawLine(padSide, y, width - padSide, y, gridPaint)
        }

        val maxMin = bars.maxOf { maxOf(it.studyMin, it.wasteMin, it.sleepMin, 60) }.toFloat()
        val groupW = chartW / bars.size
        val barW = (groupW * 0.25f).coerceIn(3f, 22f)
        val labelStep = when {
            bars.size > 20 -> 5
            bars.size > 10 -> 2
            else -> 1
        }
        labelPaint.textSize = if (bars.size > 12) 20f else 24f

        bars.forEachIndexed { idx, b ->
            val cx = padSide + groupW * idx + groupW / 2f
            val baseY = padTop + chartH

            val sH = (b.studyMin / maxMin) * chartH
            val wH = (b.wasteMin / maxMin) * chartH
            val slH = (b.sleepMin / maxMin) * chartH

            canvas.drawRoundRect(cx - barW * 1.55f, baseY - sH, cx - barW * 0.55f, baseY, 4f, 4f, studyPaint)
            canvas.drawRoundRect(cx - barW * 0.5f, baseY - wH, cx + barW * 0.5f, baseY, 4f, 4f, wastePaint)
            canvas.drawRoundRect(cx + barW * 0.55f, baseY - slH, cx + barW * 1.55f, baseY, 4f, 4f, sleepPaint)

            if (idx % labelStep == 0 || idx == bars.size - 1) {
                canvas.drawText(b.label, cx, height - 12f, labelPaint)
            }
        }
    }
}
