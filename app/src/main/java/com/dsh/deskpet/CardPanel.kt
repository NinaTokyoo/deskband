package com.dsh.deskpet

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout

/**
 * 一张可展开卡片的**展开区**容器。
 *
 * 唯一的作用：把"用户碰过这张卡"这件事告诉外面 —— 右下角那个按钮的文案要在
 * 「取消 / 收起」之间切（用户要求：操作完成并点过保存之后，取消变成收起）。
 *
 * 为什么用容器而不是逐个控件挂监听：
 *   · 一张卡里有滑条、数字输入框、按钮，逐个挂要十几处，漏一个就出错；
 *   · `setOnTouchListener` 挂在容器上没用 —— 触摸目标是子控件，容器收不到
 *     （二级菜单那边踩过同样的坑）；`dispatchTouchEvent` 才是必经之路。
 *
 * 只在 ACTION_DOWN 上报一次：一次交互算一次"操作"，MOVE 会刷屏。
 */
class CardPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 用户按下了这张卡里的任何地方（含空白处） */
    var onUserTouch: (() -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) onUserTouch?.invoke()
        return super.dispatchTouchEvent(ev)
    }
}
