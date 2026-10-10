package com.dsh.deskpet

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
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
 * 界面配色：**明暗模式** + 从角色素材提取的**强调色**。
 *
 * 强调色的适用范围被刻意收窄（用户要求）：
 *   只有 **最上方的标题** + **各种边框**（卡片边框、按钮边框）跟着发色变。
 *   其余文字一律保持中性：深色模式白字、浅色模式黑字。
 *
 * 明暗模式支持"跟随系统 / 强制浅色 / 强制深色"，切换后立刻重绘，不重建 Activity。
 */
object AccentTheme {

    /** 找不到可用发色时的兜底（原主题青蓝） */
    const val FALLBACK = 0xFF5FD3F3.toInt()

    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2

    /** 卡片容器在布局里标的 tag */
    const val TAG_CARD = "card"

    // ------------------------------------------------------------ 取色

    /**
     * 从素材里估一个"发色"。
     *
     * 头发基本都在**头部**，而头部在图上偏上，所以只看上 45%；
     * 再滤掉透明、近灰（饱和度低，含黑白描边）、过暗、过亮（高光），
     * 剩下的按 5bit/通道量化后取**出现次数最多**的那一桶。
     */
    fun extractHairColor(src: Bitmap?): Int? {
        if (src == null || src.width <= 0 || src.height <= 0) return null

        val w = src.width
        val top = (src.height * 0.45f).toInt().coerceAtLeast(1)
        val stepX = (w / 96).coerceAtLeast(1)
        val stepY = (top / 96).coerceAtLeast(1)

        val buckets = HashMap<Int, IntArray>()
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
        if (best[0] < 12) return null
        return Color.rgb(best[1] / best[0], best[2] / best[0], best[3] / best[0])
    }

    /**
     * 把原始发色调成适合当强调色的版本。
     *
     * 深色底上要够亮；浅色底上要够深，否则描边会糊在白底上 ——
     * 所以明暗两套取不同的明度区间。
     */
    fun accentFrom(hair: Int, light: Boolean): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(hair, hsv)
        if (light) {
            hsv[1] = hsv[1].coerceAtLeast(0.45f).coerceAtMost(0.95f)
            hsv[2] = hsv[2].coerceAtMost(0.72f).coerceAtLeast(0.30f)
        } else {
            hsv[1] = hsv[1].coerceAtLeast(0.50f).coerceAtMost(0.95f)
            hsv[2] = hsv[2].coerceAtLeast(0.86f)
        }
        return Color.HSVToColor(hsv)
    }

    fun accentFor(src: Bitmap?, light: Boolean): Int =
        extractHairColor(src)?.let { accentFrom(it, light) } ?: FALLBACK

    /** 明暗模式下由系统配置判断（mode 为跟随系统时用） */
    fun systemIsLight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
            Configuration.UI_MODE_NIGHT_YES

    fun isLight(ctx: Context, mode: Int): Boolean = when (mode) {
        MODE_LIGHT -> true
        MODE_DARK -> false
        else -> systemIsLight(ctx)
    }

    // ------------------------------------------------------------ 调色板

    private class Palette(light: Boolean) {
        val bg = if (light) 0xFFFFFFFF.toInt() else 0xFF0E1116.toInt()
        val card = if (light) 0xFFFFFFFF.toInt() else 0xFF171C24.toInt()
        val textPrimary = if (light) 0xFF000000.toInt() else 0xFFE8EDF2.toInt()
        val textSecondary = if (light) 0xFF5A6672.toInt() else 0xFF93A1B0.toInt()
        /** 中性描边（滑条、输入框下划线等非强调色边框） */
        val neutralStroke = if (light) 0x33000000 else 0x33FFFFFF
    }

    // ------------------------------------------------------------ 应用

    /**
     * 把整套配色应用到视图树。
     *
     * 强调色只用于：顶部标题、卡片边框、按钮边框。
     * 其余一律中性色 —— 保证两种模式下文字都清晰可读。
     */
    fun apply(ctx: Context, root: View, accent: Int, light: Boolean) {
        val dm = ctx.resources.displayMetrics
        val p = Palette(light)

        root.setBackgroundColor(p.bg)

        // 卡片：中性底 + 强调色边框
        val cardDrawable = rounded(dm, p.card, accent, 18f)
        // 镂空按钮：只有边框用强调色，文字保持中性
        val ghost = ghostSelector(dm, accent, light)

        val neutralTint = ColorStateList.valueOf(p.neutralStroke)

        walk(root) { v ->
            when {
                v is Button -> {
                    v.background = ghost.constantState?.newDrawable()?.mutate()
                    v.setTextColor(p.textPrimary)
                }
                v is SeekBar -> {
                    // 滑条不是"边框"，保持中性，免得整页花掉
                    v.progressTintList = ColorStateList.valueOf(p.textSecondary)
                    v.thumbTintList = ColorStateList.valueOf(p.textSecondary)
                }
                v is EditText -> {
                    v.setTextColor(p.textPrimary)
                    v.setHintTextColor(p.textSecondary)
                    v.backgroundTintList = neutralTint
                }
                v.tag == TAG_CARD ->
                    v.background = cardDrawable.constantState?.newDrawable()?.mutate()

                v is TextView -> when (v.id) {
                    // 只有标题吃强调色
                    R.id.txtTitle -> v.setTextColor(accent)

                    // 权限入口：之前是强调色，按要求改回中性（只有边框/标题变色）
                    R.id.txtPermOverlay,
                    R.id.txtPermNotify -> v.setTextColor(p.textPrimary)

                    else -> v.setTextColor(p.textPrimary)
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
        setStroke(dp(dm, 1.5f).toInt().coerceAtLeast(1), stroke)
    }

    /** 镂空按钮：边框使用强调色，文字中性（由调用处设置） */
    private fun ghostSelector(
        dm: android.util.DisplayMetrics, accent: Int, light: Boolean
    ): StateListDrawable = StateListDrawable().apply {
        val pressed = if (light) 0x1A000000 else 0x24FFFFFF
        val dim = Color.argb(90, Color.red(accent), Color.green(accent), Color.blue(accent))
        addState(intArrayOf(android.R.attr.state_pressed), rounded(dm, pressed, accent, 12f))
        addState(intArrayOf(-android.R.attr.state_enabled), rounded(dm, Color.TRANSPARENT, dim, 12f))
        addState(intArrayOf(), rounded(dm, Color.TRANSPARENT, accent, 12f))
    }

    /** 下拉列表弹窗背景：加一圈描边（用户要求），底色跟随明暗 */
    fun dropdownBackground(ctx: Context, accent: Int, light: Boolean): GradientDrawable {
        val dm = ctx.resources.displayMetrics
        return rounded(dm, if (light) 0xFFFFFFFF.toInt() else 0xFF1B212A.toInt(), accent, 14f)
    }
}
