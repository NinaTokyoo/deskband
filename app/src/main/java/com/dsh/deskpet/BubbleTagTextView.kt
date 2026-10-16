package com.dsh.deskpet

import android.content.Context
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.widget.TextView

/**
 * 「气泡内容」标签里的内容文字：**最多两行，放不下就截断并补三个点（...）**。
 *
 * 用户 2026-10-16 要求"标签中不能显示完全的用省略号表示"，且明确是**三个点**——
 * 系统 `ellipsize` 画的是单个"…"字形，点数不是三个，所以得自己截。
 *
 * ⚠️ 关键在于**在 [onSizeChanged] 里算**（也就是布局阶段、第一次绘制之前）：
 *    早先是在 `post {}` 里算的，那时第一帧已经画过了 —— 于是标签先按系统的省略号
 *    显示一帧、下一帧又被截短改写，看起来就是"省略号闪一下、文字瞬移"
 *    （用户 2026-10-17："其他标签的省略号有闪烁瞬移的情况"）。
 *
 * ⚠️ 宽度未知（还没布局）时**什么都不做**：宁可先让系统省略号顶着，也不能凭空截。
 */
class BubbleTagTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : TextView(context, attrs, defStyle) {

    /** 完整内容（截断后仍保留原文，宽度变化时可以重算） */
    private var fullText: CharSequence = ""

    init {
        maxLines = MAX_LINES
        ellipsize = TextUtils.TruncateAt.END
    }

    /** 设置**完整**内容（截断由本类负责） */
    fun setFullText(text: CharSequence) {
        fullText = text
        super.setText(text)
        fit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    private fun fit() {
        val avail = width - paddingStart - paddingEnd
        if (avail <= 0 || fullText.isEmpty()) return
        val fitted = fitLines(fullText.toString(), paint, avail, lineSpacingExtra, MAX_LINES)
        val shown = if (fitted == fullText.toString()) fullText else fitted + "..."
        if (text?.toString() != shown.toString()) super.setText(shown)
    }

    companion object {
        /** 标签内容最多显示几行（用户 2026-10-16：从三行改回两行） */
        const val MAX_LINES = 2

        /**
         * 把 [text] 截到"在 [width] 宽、[maxLines] 行以内放得下"的最长前缀。
         *
         * 用系统的 [StaticLayout] 试排一次（与 TextView 自己同一套折行规则），
         * 行数没超就原样返回；超了取前 maxLines 行能放下的部分。
         */
        fun fitLines(
            text: String,
            paint: TextPaint,
            width: Int,
            extraSpacing: Float,
            maxLines: Int
        ): String {
            if (width <= 0) return text
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, width)
                .setLineSpacing(extraSpacing, 1f)
                .build()
            if (layout.lineCount <= maxLines) return text
            // ⚠️ 少留一个字：后面还要补"..."（三个点）—— 正好一个汉字的宽度
            val end = (layout.getLineEnd(maxLines - 1) - 1).coerceIn(1, text.length)
            return text.substring(0, end).trimEnd()
        }
    }
}
