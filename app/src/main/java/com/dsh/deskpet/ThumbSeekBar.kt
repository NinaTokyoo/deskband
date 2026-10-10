package com.dsh.deskpet

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.SeekBar

/**
 * 只允许**按住滑块**拖动的 SeekBar：点轨道不改变数值。
 *
 * 用户要求："所有滑块条只要按住滑块才能拖动，不允许存在点击滑块条改变数值的操作。"
 *
 * Android 的 SeekBar 默认在 [onTouchEvent] 里，只要 `isInScrollingContainer()` 为
 * false 就无条件按坐标 `setProgress()` —— 点轨道任意位置都会跳值。
 *
 * ## 为什么是子类，而不是 `setOnTouchListener`
 *
 * 先试过在 Activity 里 `setOnTouchListener`：**不行**。View 的分发逻辑是
 * "监听器返回 true 就吃掉；返回 false 就交给自己的 onTouchEvent，并且此后
 * 不再调用监听器"（`dispatchTouchEvent` 里那个 `mOnTouchListener == null` 的分支
 * 要求 `!pressed`，而 SeekBar 的 onTouchEvent 会 `setPressed(true)`）。
 * 结果拖动（需要放行）把监听器"关掉"了，后续 MOVE 直接进 onTouchEvent，
 * 轨道点击照样生效 —— 实测就是如此。
 *
 * 覆写 [onTouchEvent] 是唯一能拦住所有事件的位置。
 *
 * ## 判定
 *
 * 用滑块**实际**位置，不拿 `paddingLeft + progress/max * (w - padding)` 估 ——
 * SeekBar 的滑块中心并不严格等于这个线性插值（两端受 thumbOffset 影响），
 * 估算偏一点就会出现"明明按住滑块却拖不动"。这里横向取滑块中心 ± 一个滑块宽度，
 * 纵向放宽到整个控件高度：手指按下不可能精确落在滑块正中。
 */
class ThumbSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle
) : SeekBar(context, attrs, defStyleAttr) {

    /** 本次手势是否放行（DOWN 时判定一次，之后整段沿用） */
    private var allowGesture = false

    /**
     * 关键：**整个手势**一起放行或一起吃掉，不能只吃 ACTION_DOWN。
     *
     * ⚠️ 踩过的坑：只在 DOWN 时拦截是不够的。AOSP 的 AbsSeekBar.onTouchEvent 里
     * `ACTION_UP` 分支会**无条件**执行
     *     onStartTrackingTouch(); trackTouchEvent(event); onStopTrackingTouch();
     * 而 trackTouchEvent 会按坐标 `setProgress()` —— 与 DOWN 有没有被处理无关。
     * 实测日志：`DOWN x=191.0 w=1278 prog=719 hit=false`，判定正确、DOWN 也返回了
     * true，但抬手时数值照样从 2.618 跳到 0.653。
     * （另外没走 DOWN 时 `mTouchProgressOffset` 是 0，左端 padding 的偏移也没被消掉。）
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                allowGesture = hitThumb(event.x, event.y)
                if (!allowGesture) {
                    // 没按在滑块上 —— 吃掉。返回 true 而不是 false：
                    // 返回 false 会让事件继续上传，可能被父容器当成滚动起手。
                    // 这里**不** setPressed(true)：避免出现"按在空白处却高亮"的观感。
                    return true
                }
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!allowGesture) return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 触点是否落在滑块上 */
    private fun hitThumb(x: Float, y: Float): Boolean {
        val w = width
        if (w <= 0) return false
        val span = (w - paddingLeft - paddingRight).toFloat()
        if (span <= 0f) return false
        val tw = thumb?.intrinsicWidth?.toFloat()
            ?: (resources.displayMetrics.density * 20f)
        if (max <= 0) return false
        val frac = progress / max.toFloat()
        val cx = paddingLeft + (span - tw) * frac + tw / 2f + thumbOffset
        return x >= cx - tw && x <= cx + tw && y >= 0f && y <= height.toFloat()
    }
}
