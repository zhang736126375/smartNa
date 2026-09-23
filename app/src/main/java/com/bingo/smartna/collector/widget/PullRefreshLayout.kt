package com.bingo.smartna.collector.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.core.view.ViewCompat
import kotlin.math.min

/** 轻量下拉刷新，避免额外依赖 SwipeRefreshLayout。 */
class PullRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var onRefresh: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var startY = 0f
    private var pulling = false
    private var refreshing = false
    private val indicator = ProgressBar(context).apply {
        isIndeterminate = true
        visibility = GONE
    }

    init {
        addView(
            indicator,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                topMargin = (8 * resources.displayMetrics.density).toInt()
            }
        )
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        if (childCount > 1) {
            bringChildToFront(indicator)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (refreshing) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = ev.y
                pulling = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - startY
                if (dy > touchSlop && !canScrollUp()) {
                    pulling = true
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (refreshing) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (pulling) {
                    val dy = min((event.y - startY) * 0.5f, 120f)
                    if (dy > 0) {
                        indicator.visibility = VISIBLE
                        indicator.translationY = dy * 0.3f
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pulling) {
                    pulling = false
                    indicator.translationY = 0f
                    if (indicator.visibility == VISIBLE) {
                        startRefresh()
                    }
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    fun setRefreshing(refresh: Boolean) {
        refreshing = refresh
        indicator.visibility = if (refresh) VISIBLE else GONE
        indicator.translationY = 0f
    }

    private fun startRefresh() {
        refreshing = true
        indicator.visibility = VISIBLE
        onRefresh?.invoke()
    }

    private fun canScrollUp(): Boolean {
        val content = contentView() ?: return false
        return ViewCompat.canScrollVertically(content, -1)
    }

    private fun contentView(): View? {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child !== indicator) return child
        }
        return null
    }
}
