package com.bingo.smartna.collector.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.bingo.smartna.R
import kotlin.math.min
import kotlin.random.Random

/** 登录页上半屏：黑色宇宙 + 蓝色星球轨道。 */
class PlanetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val orbitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.1f)
    }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val planetPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height * 0.42f
        val planetR = min(width, height) * 0.19f

        for (i in 1..6) {
            orbitPaint.color = Color.argb((255 * (0.12f + i * 0.028f)).toInt(), 255, 255, 255)
            canvas.drawCircle(cx, cy, planetR * (1.32f + i * 0.52f), orbitPaint)
        }

        val random = Random(42)
        repeat(52) {
            val x = random.nextFloat() * width
            val y = random.nextFloat() * height
            val r = 1f + random.nextFloat() * 2.4f
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy > planetR * planetR * 1.15f) {
                starPaint.color = Color.argb(
                    (255 * (0.18f + random.nextFloat() * 0.62f)).toInt(),
                    255,
                    255,
                    255
                )
                canvas.drawCircle(x, y, r, starPaint)
            }
        }

        glowPaint.shader = RadialGradient(
            cx,
            cy + planetR * 0.25f,
            planetR * 2.2f,
            intArrayOf(Color.argb(110, 37, 99, 235), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, planetR * 2.2f, glowPaint)

        planetPaint.shader = RadialGradient(
            cx - planetR * 0.12f,
            cy + planetR * 0.42f,
            planetR * 1.55f,
            intArrayOf(
                ContextCompat.getColor(context, R.color.brand_orange_light),
                ContextCompat.getColor(context, R.color.brand_orange),
                Color.parseColor("#0A1A4D")
            ),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, planetR, planetPaint)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
