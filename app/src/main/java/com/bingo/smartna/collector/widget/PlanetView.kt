package com.bingo.smartna.collector.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
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
        strokeWidth = dp(1.2f)
    }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val planetPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height * 0.44f
        val planetR = min(width, height) * 0.17f

        for (i in 1..6) {
            orbitPaint.color = Color.argb((255 * (0.10f + i * 0.035f)).toInt(), 255, 255, 255)
            canvas.drawCircle(cx, cy, planetR * (1.35f + i * 0.55f), orbitPaint)
        }

        val random = Random(42)
        repeat(46) {
            val x = random.nextFloat() * width
            val y = random.nextFloat() * height
            val r = 1f + random.nextFloat() * 2.2f
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy > planetR * planetR * 1.2f) {
                starPaint.color = Color.argb(
                    (255 * (0.15f + random.nextFloat() * 0.55f)).toInt(),
                    255,
                    255,
                    255
                )
                canvas.drawCircle(x, y, r, starPaint)
            }
        }

        glowPaint.shader = RadialGradient(
            cx,
            cy,
            planetR * 2.1f,
            intArrayOf(Color.argb(89, 37, 99, 235), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, planetR * 2.1f, glowPaint)

        planetPaint.shader = RadialGradient(
            cx - planetR * 0.15f,
            cy + planetR * 0.35f,
            planetR * 1.6f,
            intArrayOf(Color.parseColor("#9EC9FF"), Color.parseColor("#2563EB"), Color.parseColor("#0F3FA8")),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, planetR, planetPaint)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
