package com.bingo.smartna.collector.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import com.bingo.smartna.R

/** 空状态：纸箱示意 + 文案 + 可选按钮。 */
class EmptyStateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val messageView: TextView
    private val ctaButton: AppCompatButton

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val box = BoxCanvasView(context).apply {
            layoutParams = LayoutParams(dp(170), dp(140))
        }
        messageView = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.text_gray))
            textSize = 15f
            gravity = Gravity.CENTER
        }
        ctaButton = AppCompatButton(context).apply {
            visibility = GONE
            setBackgroundResource(R.drawable.bg_btn_primary)
            setTextColor(ContextCompat.getColor(context, R.color.card_white))
            textSize = 16f
            isAllCaps = false
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(46)).apply {
                topMargin = dp(20)
            }
            minWidth = dp(160)
            setPadding(dp(24), 0, dp(24), 0)
        }
        addView(box)
        addView(messageView)
        addView(ctaButton)
    }

    fun bind(text: String, ctaText: String? = null, onCta: (() -> Unit)? = null) {
        messageView.text = text
        if (ctaText.isNullOrBlank()) {
            ctaButton.visibility = GONE
            ctaButton.setOnClickListener(null)
        } else {
            ctaButton.visibility = VISIBLE
            ctaButton.text = ctaText
            ctaButton.setOnClickListener { onCta?.invoke() }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

private class BoxCanvasView(context: Context) : android.view.View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.card_white)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.divider_gray)
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.divider_gray)
        pathEffect = DashPathEffect(floatArrayOf(6f, 8f), 0f)
    }
    private val blueLight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.blue_light)
    }
    private val blueDeep = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.blue_deep)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        linePaint.strokeWidth = w * 0.018f
        dashPaint.strokeWidth = linePaint.strokeWidth

        fun path(block: Path.() -> Unit) = Path().apply(block)

        val leftLid = path {
            moveTo(w * 0.10f, h * 0.46f)
            lineTo(w * 0.02f, h * 0.28f)
            lineTo(w * 0.34f, h * 0.20f)
            lineTo(w * 0.40f, h * 0.38f)
            close()
        }
        canvas.drawPath(leftLid, fillPaint)
        canvas.drawPath(leftLid, linePaint)

        val rightLid = path {
            moveTo(w * 0.90f, h * 0.46f)
            lineTo(w * 0.98f, h * 0.28f)
            lineTo(w * 0.66f, h * 0.20f)
            lineTo(w * 0.60f, h * 0.38f)
            close()
        }
        canvas.drawPath(rightLid, fillPaint)
        canvas.drawPath(rightLid, linePaint)

        val body = path {
            moveTo(w * 0.16f, h * 0.46f)
            lineTo(w * 0.84f, h * 0.46f)
            lineTo(w * 0.84f, h * 0.88f)
            lineTo(w * 0.16f, h * 0.88f)
            close()
        }
        canvas.drawPath(body, fillPaint)
        canvas.drawPath(body, linePaint)
        canvas.drawLine(w * 0.5f, h * 0.46f, w * 0.5f, h * 0.88f, linePaint)
        canvas.drawLine(w * 0.40f, h * 0.40f, w * 0.60f, h * 0.40f, linePaint)

        val dash = path {
            moveTo(w * 0.52f, h * 0.36f)
            quadTo(w * 0.78f, h * 0.18f, w * 0.86f, h * 0.08f)
        }
        canvas.drawPath(dash, dashPaint)
        canvas.drawCircle(w * 0.83f, h * 0.10f, w * 0.045f, blueLight)
        canvas.drawCircle(w * 0.88f, h * 0.065f, w * 0.030f, blueDeep)
    }
}
