package com.bingo.smartna.collector.device

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.bingo.smartna.R

/** 扫码取景四角框。 */
class ScanFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        color = ContextCompat.getColor(context, R.color.blue_primary)
        strokeCap = Paint.Cap.SQUARE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val len = dp(28f)
        val t = paint.strokeWidth / 2f
        val r = width - t
        val b = height - t
        canvas.drawLine(t, t, t + len, t, paint)
        canvas.drawLine(t, t, t, t + len, paint)
        canvas.drawLine(r, t, r - len, t, paint)
        canvas.drawLine(r, t, r, t + len, paint)
        canvas.drawLine(t, b, t + len, b, paint)
        canvas.drawLine(t, b, t, b - len, paint)
        canvas.drawLine(r, b, r - len, b, paint)
        canvas.drawLine(r, b, r, b - len, paint)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
