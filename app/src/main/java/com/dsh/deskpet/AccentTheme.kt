package com.dsh.deskpet

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView

/**
 * 从角色素材里提取**发色**，并据此给整个界面换肤。
 *
 * 设计取舍：
 *  - 不引入 AndroidX / Material 动态取色，纯平台实现，自己算。
 *  - 只产生**一个强调色**，再由它派生卡片底 / 页面底 / 描边 / 次要文字，
 *    行为可预期，也不会出现配色失控。
 *  - 换肤靠遍历视图树：按钮统一走镂空样式 + 强调色文字；
 *    容器标 tag=card；登记的正文文字跟随。新增控件只要还是 Button
 *    或登记过的文字，就会自动跟着变。
 */
object AccentTheme {

    /** 找不到可用发色时的兜底（原主题青蓝） */
    const val FALLBACK = 0xFF5FD3F3.toInt()

    private const val BG = 0xFF0E1116.toInt()
    private const val CARD = 0xFF171C24.toInt()
    private const val CARD_STROKE = 0x22FFFFFF
    private const val TEXT_SECONDARY = 0xFF93A1B0.toInt()

    /** 卡片容器在布局里标的 tag */
    const val TAG_CARD = "card"

    // ------------------------------------------------------------ 取色

    /**
     * 从素材里估一个"发色"。
     *
     * 头发基本都在**头部**，而头部在图上偏上，所以只看上 45%；
     * 再滤掉透明、近灰（饱和度低，含黑白描边）、过暗、过亮（高光），
     * 剩下的按 5bit/通道量化后取**出现次数最多**的那一桶。
     *
     * 对动漫立绘很有效：发色通常是大面积、饱和、非白非黑的色块。
     */
    fun extractHairColor(src: Bitmap?): Int? {
        if (src == null || src.width <= 0 || src.height <= 0) return null

        val w = src.width
        val top = (src.height * 0.45f).toInt().coerceAtLeast(1)
        val stepX = (w / 96).coerceAtLeast(1)
        val stepY = (top / 96).coerceAtLeast(1)

        val buckets = HashMap<Int, IntArray>()   // key -> [count, sumR, sumG, sumB]
        val row = IntArray(w)
        var y = 0
        while (y < top) {
            if (!runCatching { src.getPixels(row, 0, w, 0, y, w, 1) }.isSuccess) break
            var x = 0
            while (x < w) {
                val p = row[x]
                if (((p ushr 24) and 0xFF) >= 200) {
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val mx = maxOf(r, g, b)
                    val mn = minOf(r, g, b)
                    if (mx - mn >= 34 && mx >= 60 && mn <= 235) {
                        val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
                        val e = buckets.getOrPut(key) { IntArray(4) }
                        e[0]++; e[1] += r; e[2] += g; e[3] += b
                    }
                }
                x += stepX
            }
            y += stepY
        }

        val best = buckets.values.maxByOrNull { it[0] } ?: return null
        if (best[0] < 12) return null   // 样本太少，不可信
        return Color.rgb(best[1] / best[0], best[2] / best[0], best[3] / best[0])
    }

    /**
     * 把原始发色调成适合当强调色的版本。
     *
     * 抬高饱和度与明度下限：很多角色是浅粉、亚麻色，直接用会糊在深色底上。
     */
    fun accentFrom(hair: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(hair, hsv)
        hsv[1] = hsv[1].coerceAtLeast(0.50f).coerceAtMost(0.95f)
        hsv[2] = hsv[2].coerceAtLeast(0.86f)
        return Color.HSVToColor(hsv)
    }

    /** 一步到位：素材 -> 强调色（取不到就返回 [FALLBACK]） */
    fun accentFor(src: Bitmap?): Int =
        extractHairColor(src)?.let { accentFrom(it) } ?: FALLBACK

    // ------------------------------------------------------------ 派生色

    private fun blend(base: Int, color: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        fun mix(a: Int, b: Int) = (a + (b - a) * r).toInt().coerceIn(0, 255)
        return Color.argb(
            255,
            mix(Color.red(base), Color.red(color)),
            mix(Color.green(base), Color.green(color)),
            mix(Color.blue(base), Color.blue(color))
        )
    }

    fun pageBg(accent: Int): Int = blend(BG, accent, 0.07f)

    /**
     * 卡片底色**不跟着角色变**（用户要求）。
     *
     * 保持中性深底，只让描边、按钮、文字跟发色走 ——
     * 卡片本身安静一点，整页也不会因为发色太艳而糊成一片。
     */
    fun cardBg(accent: Int): Int = CARD

    fun dimText(accent: Int): Int = blend(TEXT_SECONDARY, accent, 0.42f)

    // ------------------------------------------------------------ 应用

    /** 把整套皮肤应用到视图树 */
    fun apply(ctx: Context, root: View, accent: Int) {
        val dm = ctx.resources.displayMetrics
        root.setBackgroundColor(pageBg(accent))
        val cardDrawable = rounded(dm, cardBg(accent), blend(CARD_STROKE, accent, 0.45f), 18f)
        val ghost = ghostSelector(dm, accent)
        val cs = ColorStateList.valueOf(accent)
        val underline = ColorStateList.valueOf(Color.argb(150, Color.red(accent), Color.green(accent), Color.blue(accent)))

        walk(root) { v ->
            when {
                v is Button -> {
                    v.background = ghost.constantState?.newDrawable()?.mutate()
                    v.setTextColor(accent)
                }
                // 滑条的进度条与滑块也要跟着变色，否则会留一条旧主题的青蓝
                v is SeekBar -> {
                    v.progressTintList = cs
                    v.thumbTintList = cs
                }
                // 输入框的默认下划线同样染色
                v is EditText -> v.backgroundTintList = underline

                v.tag == TAG_CARD ->
                    v.background = cardDrawable.constantState?.newDrawable()?.mutate()

                v is TextView -> when (v.id) {
                    R.id.txtTitle,
                    R.id.txtPermOverlay,
                    R.id.txtPermNotify -> v.setTextColor(accent)

                    R.id.txtSize,
                    R.id.txtBounceAmp,
                    R.id.txtBounceMs,
                    R.id.txtNotifyCount -> v.setTextColor(dimText(accent))
                    // 权限行的勾/叉由 setPermMark 按开启状态着色，这里不覆盖
                }
            }
        }
    }

    private fun walk(v: View?, f: (View) -> Unit) {
        if (v == null) return
        f(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), f)
    }

    private fun dp(dm: android.util.DisplayMetrics, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)

    private fun rounded(
        dm: android.util.DisplayMetrics, fill: Int, stroke: Int, radiusDp: Float
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(dm, radiusDp)
        setColor(fill)
        setStroke(dp(dm, 1f).toInt().coerceAtLeast(1), stroke)
    }

    /** 镂空按钮背景：按下时加一层淡淡的同色底 */
    private fun ghostSelector(dm: android.util.DisplayMetrics, accent: Int): StateListDrawable =
        StateListDrawable().apply {
            val tint = Color.argb(46, Color.red(accent), Color.green(accent), Color.blue(accent))
            val dim = Color.argb(90, Color.red(accent), Color.green(accent), Color.blue(accent))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(dm, tint, accent, 12f))
            addState(intArrayOf(-android.R.attr.state_enabled), rounded(dm, Color.TRANSPARENT, dim, 12f))
            addState(intArrayOf(), rounded(dm, Color.TRANSPARENT, accent, 12f))
        }
}
