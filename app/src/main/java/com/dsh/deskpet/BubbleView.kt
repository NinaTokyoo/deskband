package com.dsh.deskpet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View

/**
 * 思考气泡：三个部件（小圈 → 中圈 → 大气泡）的**独立悬浮窗**内容。
 *
 * ## 为什么单独开窗
 * 宠物窗口静止时会收紧到"刚好包住人物"，气泡画在里面一定被裁掉；
 * 临时把宠物窗口撑大又会让每次冒泡都 resize 一次（WM 重新布局、可能重建 Surface），
 * 观感是"闪一下"。所以气泡自己开一个 TYPE_APPLICATION_OVERLAY 小窗，用完就撤。
 *
 * ## 素材
 * `assets/bubble/{b0_small,b1_mid,b2_big}.png` —— 由 `tools/extract_bubble_parts.py`
 * 从用户给的气泡原图处理而来：顺时针 90° → 去掉外部白底（含角上的水印）→
 * 连通域切成三块。三块在**整组外接框**里的相对矩形 [PART_BOX] 也是同一个脚本算的，
 * 所以三块叠起来与用户确认过的合成图构图一致。
 *
 * ## 时间轴（用户规定）
 * ```
 *   0 ──── 0.15s ──────── 1.65s ──── 1.80s
 *   小→中→大依次出现      大气泡停留 1.5s   大→中→小依次消失
 * ```
 * 出现与消失都**只做缩放、不做透明度**：素材是实色描边 + 实色内部，
 * 淡入淡出会让描边中途发虚。
 */
class BubbleView(context: Context) : View(context) {

    /** 0 = 小圈、1 = 中圈、2 = 大气泡；下标即 [popOf] 的 index */
    private var parts = arrayOf(
        decode(context, "b0_small"),
        decode(context, "b1_mid"),
        decode(context, "b2_big")
    )

    /**
     * 深色模式：把素材里的**白底换成灰底**（用户 2026-10-16 指定的颜色，
     * 取自他给的那张深色气泡图的底色 #A9A9A9）。
     *
     * 由服务在建窗**之前**按 App 的「外观模式」下发（跟随系统 / 强制浅色 / 强制深色）。
     * ⚠️ 只换**填充色**：描边、透明通道、三块的几何（[PART_BOX]）全都不动 ——
     *    所以已冻结的位置/尺寸规则完全不受影响。
     */
    var grayFill = false
        set(value) {
            if (field == value) return
            field = value
            parts = arrayOf(
                decode(context, "b0_small"),
                decode(context, "b1_mid"),
                decode(context, "b2_big")
            )
            rebuildScaled()
            invalidate()
        }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()

    /**
     * 预缩放后的三块素材（按当前窗口尺寸算好，绘制时基本 1:1 用）。
     *
     * ⚠️ 为什么必须预缩放：素材原图比实际绘制尺寸大好几倍（大气泡那张最明显），
     * 若每帧都让 `drawBitmap` 现场做双线性缩小，出现/消失动画在 120Hz 屏上会明显掉帧
     * —— 用户反馈"动画帧率有点低"就是这个原因。缩放在 [onSizeChanged] 里只做一次。
     */
    private val scaled = arrayOfNulls<Bitmap>(PART_COUNT)

    private var startAt = 0L

    /**
     * 整组随**人物一起**旋转的角度（人物吸附右边框时 = -90，与 [PetState.rotationForSnap] 一致）。
     *
     * 用户要的效果是"合成图整体跟着人物转"：人物躺倒在右边框、头朝左时，
     * 思考气泡也一起倒 90° —— 大气泡整个飘到人物**上方**，两个小圈从下往上
     * 指着脑袋（气泡自己那圈也从竖椭圆变成横椭圆）。
     */
    var rotationDeg = 0f

    /**
     * 与人物素材**同一套**的左右镜像 / 上下翻转（旋转见 [rotationDeg]）。
     *
     * 整套变换链与 `PetSprite.bitmap` + `PetView` 的画布旋转严格一致：
     *   素材 -> 镜像(x) -> 上下翻转(y) -> 旋转(0/∓90)
     * 于是"人物素材怎么翻，气泡就怎么翻"，四种朝向（右下/左下/贴右/贴左 …）自动成立。
     *
     * ⚠️ 注意 [mirror] 已经含了"气泡摆左侧"那一层：摆左侧时整组要在素材系里反过来，
     *    由 [PetService.showBubble] 用 `素材镜像 xor 摆左侧` 算好再传进来。
     */
    var mirror = false
    var flipV = false

    /**
     * 要显示在气泡里的文字（用户 2026-10-16）。
     *
     * 空串 = 不画字（没内容时的默认样子）。排版规则见 [drawText]：
     * 大椭圆内部水平垂直居中、需要换行就换行、与椭圆四周留空隙。
     */
    var text: String = ""

    /** 文字颜色（= 人物发色经 accentFrom(light) 派生，由服务下发） */
    var textColor: Int = 0xFF000000.toInt()

    /** 粗黑体（与预览用的同一类：系统中粗黑体，不带衬线） */
    private val textPaint = android.text.TextPaint().apply {
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD
        )
        isAntiAlias = true
    }

    /** 是否已经转过 90°（窗口宽高与组自身宽高互换） */
    private val rotated: Boolean get() = (rotationDeg % 180f) != 0f

    /**
     * 窗口尺寸确定时把三块素材预缩放到"将要绘制的尺寸"。
     *
     * 只在窗口尺寸变化时跑一次（气泡窗口建成后尺寸不变），后续每帧都省掉双线性缩小。
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildScaled()
    }

    private fun rebuildScaled() {
        if (width <= 0 || height <= 0) return
        val gw = if (rotated) height.toFloat() else width.toFloat()
        val gh = if (rotated) width.toFloat() else height.toFloat()
        val boxes = PART_BOX
        for (i in parts.indices) {
            val src = parts[i] ?: continue
            val tw = (boxes[i][2] * gw).toInt().coerceAtLeast(1)
            val th = (boxes[i][3] * gh).toInt().coerceAtLeast(1)
            val old = scaled[i]
            if (old != null && old !== src && !old.isRecycled) old.recycle()
            scaled[i] = if (src.width == tw && src.height == th) src
            else Bitmap.createScaledBitmap(src, tw, th, true)
        }
    }

    /** 开始一次冒泡（重置时间轴） */
    fun start() {
        startAt = SystemClock.uptimeMillis()
        invalidate()
    }

    /** 时间轴是否已经走完（服务据此收掉窗口） */
    fun finished(now: Long = SystemClock.uptimeMillis()): Boolean =
        startAt != 0L && now - startAt >= TOTAL_MS

    /**
     * 立刻进入**消失段** —— 用户 2026-10-16 新增规则："拖动人物时，气泡立刻播放消失动画并消失"。
     *
     * 做法是把时间轴直接拨到"停留结束、消失开始"的那一刻（`APPEAR + HOLD`），
     * 于是三块仍然按既定的**由大到小**顺序在 [APPEAR_MS] 内缩没 ——
     * 和自然消失是**同一套动画**，不用另写一份。
     *
     * ⚠️ 只往前拨、不往回拨：气泡本来就已经在消失段时就不动（否则会越拖越长）。
     */
    fun vanishNow() {
        if (startAt == 0L) return
        // ⚠️ 这一步是让 startAt **往前拨**（而不是往后）：令"此刻"正好对应到消失段的起点
        //    （t = APPEAR + HOLD），于是三块从满尺寸开始、由大到小在 VANISH_MS 内缩没。
        //
        //    ⚠️⚠️ 2.1.49 / 2.1.50 把这里写成了"只在 target > startAt 时才拨"，方向反了：
        //    气泡还在出现段或停留段时 target **小于** startAt（实机日志：shift = -865ms），
        //    于是什么都没发生 —— 表现就是"拖动时没有消失动画"。
        startAt = SystemClock.uptimeMillis() - (APPEAR_MS + HOLD_MS).toLong()
        android.util.Log.i("BubbleGeom", "vanishNow -> t=APPEAR+HOLD")
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = if (startAt == 0L) 0f else (now - startAt).toFloat()

        // 组自身坐标系：转过 90° 时，窗口的宽高与组宽高互换
        val gw = if (rotated) height.toFloat() else width.toFloat()
        val gh = if (rotated) width.toFloat() else height.toFloat()

        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        if (rotationDeg != 0f) canvas.rotate(rotationDeg)
        if (mirror || flipV) canvas.scale(if (mirror) -1f else 1f, if (flipV) -1f else 1f)
        canvas.translate(-gw / 2f, -gh / 2f)

        for (i in parts.indices) {
            // 优先用**预缩放**好的那份：绘制尺寸基本 1:1，省掉每帧的大图双线性缩小
            val bmp = scaled[i] ?: parts[i] ?: continue
            val pop = popOf(i, t) ?: continue
            val box = PART_BOX[i]
            val l = box[0] * gw
            val tp = box[1] * gh
            dst.set(l, tp, l + box[2] * gw, tp + box[3] * gh)
            canvas.save()
            // 从小到大：绕**自己的中心**缩放，位置不动
            canvas.scale(pop, pop, dst.centerX(), dst.centerY())
            canvas.drawBitmap(bmp, null, dst, paint)
            canvas.restore()
        }
        canvas.restore()

        // 文字：画在**窗口坐标系**里（不跟着气泡图案转）——图案可能转了 ±90°，
        // 文字必须永远是正的（用户 2026-10-16 定的排版原则见 [drawText]）。
        //
        // ⚠️ 出现/消失与**大气泡**严格对齐（用户 2026-10-16）：
        //    · 大气泡完全出现那一刻（= 出现段结束 APPEAR_MS）才显示文字；
        //    · 大气泡开始缩小的那一瞬间（= 停留段结束 APPEAR_MS + HOLD_MS）文字立刻消失。
        //    三块的消失顺序是大->中->小，所以"大气泡开始缩"正好是消失段的起点。
        //    拖动/再点触发的 [vanishNow] 把时间轴拨到同一个点，于是文字也同时消失 —— 天然一致。
        if (text.isNotEmpty() && t >= APPEAR_MS && t < APPEAR_MS + HOLD_MS) drawText(canvas)

        if (!finished(now)) postInvalidateOnAnimation()
    }

    /**
     * 把 [text] 画进大椭圆里。三条原则（用户 2026-10-16 定稿）：
     *
     *   1. **在大椭圆内部水平垂直居中**；
     *   2. **需要换行就换行**（每行宽度上限 = 该行高度处椭圆的弦宽）；
     *   3. **与椭圆周围留出空隙**。
     *
     * 具体做法与预览脚本（`deskband-bubble-feature/render_bubble_text.py`）逐条对应：
     *   · 可用区 = 大椭圆外框**扣掉描边**（实测约短半轴的 4%）；
     *   · 先定行数：取"能排下的最少行数"，但字号不得小于短轴的 16%
     *     （否则 3 个字会变成「这是／一」、10 个字会挤成一行看不清）；
     *   · 再定字号：该行数下能放下的最大字号，并受 [textCapRatio] 封顶
     *     （≈ 3 个字的字号，免得一个字撑满气泡）；
     *   · 折行：有空格就在空格处断（英文不切单词），没有就逐字断（中文）；
     *   · 居中：以椭圆中心为基准，再上移 0.05em 做**视觉居中补偿**
     *     （墨迹中心天生比 em 中心低一点）。
     */
    private fun drawText(canvas: Canvas) {
        val box = bigEllipseScreenBox() ?: return
        val cx = box[0]
        val cy = box[1]
        val rx = box[2]
        val ry = box[3]
        if (rx <= 2f || ry <= 2f) return

        // 抠掉描边后的内缘椭圆（半轴）
        val inset = minOf(rx, ry) * STROKE_INSET_RATIO
        val ew = (rx - inset) * 2f
        val eh = (ry - inset) * 2f
        if (ew <= 4f || eh <= 4f) return

        val minSize = eh * MIN_SIZE_RATIO
        val cap = ew * CHAR_W_RATIO / 3f      // ≈ 3 个字的字号（封顶）
        val paint = textPaint.apply { color = textColor }

        // 行数由少到多试，取"字号不低于最小可读字号"的第一个行数
        var chosen: Pair<Float, List<String>>? = null
        for (k in 1..6) {
            if (k * minSize * LINE_SPACING > eh * V_MARGIN) break
            var size = cap
            var found: Pair<Float, List<String>>? = null
            while (size >= 9f) {
                paint.textSize = size
                val lh = size * LINE_SPACING
                if (k * lh <= eh * V_MARGIN) {
                    val lines = wrapForEllipse(paint, k, lh, ew, eh)
                    if (lines != null) {
                        found = size to lines
                        break
                    }
                }
                size -= 1f
            }
            if (found != null && found.first >= minSize) {
                chosen = found
                break
            }
        }
        val (size, lines) = chosen ?: return
        paint.textSize = size
        val lh = size * LINE_SPACING
        // 居中：块的竖直中心对齐椭圆中心，再上移 0.05em
        val y0 = cy - (lines.size - 1) / 2f * lh - size * OPTICAL_DY_EM
        for ((j, line) in lines.withIndex()) {
            val w = paint.measureText(line)
            canvas.drawText(line, cx - w / 2f, y0 + j * lh + size * BASELINE_RATIO, paint)
        }
    }

    /**
     * 按椭圆的**弦宽**折行；放不下返回 null。
     *
     * 第 j 行（共 k 行、行高 lh）在竖直方向相对块中心偏移 `(j-(k-1)/2)*lh`，
     * 该高度处的弦宽 = `2*rx*sqrt(1-(dy/ry)^2)`。
     *
     * 断行位置：**有空格就优先在空格处断**（英文不切单词），
     * 否则逐字断（中文可以任意字之间断）。
     */
    private fun wrapForEllipse(
        paint: android.text.TextPaint, k: Int, lh: Float, ew: Float, eh: Float
    ): List<String>? {
        val widths = FloatArray(k) { j ->
            val dy = (j - (k - 1) / 2f) * lh
            val t = minOf(0.995f, Math.abs(2f * dy / eh))
            ew * kotlin.math.sqrt((1f - t * t).coerceAtLeast(0f)) * CHORD_MARGIN
        }
        val lines = ArrayList<String>()
        var i = 0
        val s = text
        while (i < s.length && lines.size < k) {
            val limit = widths[lines.size]
            var end = i
            var lastSpace = -1
            while (end < s.length && paint.measureText(s, i, end + 1) <= limit) {
                if (s[end] == ' ') lastSpace = end
                end++
            }
            if (end == i) return null                      // 一个字都放不下
            // 断点：优先退到最后一个空格（且不是行首）；否则就近断
            val cut = if (end < s.length && lastSpace > i) lastSpace else end
            lines.add(s.substring(i, cut).trim())
            i = cut
            while (i < s.length && s[i] == ' ') i++          // 行首空格吃掉
        }
        if (i < s.length) return null                        // 还有剩下的 -> 这组放不下
        return lines.filter { it.isNotEmpty() }
    }

    /**
     * 大椭圆（含描边）在**窗口坐标系**里的中心与半轴。
     *
     * 用与绘制完全相同的变换链算：把 [PART_BOX] 里大气泡那一格的四个角
     * 依次过 `mapGroupPoint`（镜像 -> 上下翻转 -> 旋转），取包围盒。
     * 旋转都是 90° 的整数倍，所以包围盒就是轴对齐的，椭圆内切其中。
     */
    private fun bigEllipseScreenBox(): FloatArray? {
        val gw = if (rotated) height.toFloat() else width.toFloat()
        val gh = if (rotated) width.toFloat() else height.toFloat()
        if (gw <= 0f || gh <= 0f) return null
        val box = PART_BOX[PART_COUNT - 1]      // 大气泡那一格
        val l = box[0] * gw
        val t = box[1] * gh
        val r = l + box[2] * gw
        val b = t + box[3] * gh
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (px in floatArrayOf(l, r)) {
            for (py in floatArrayOf(t, b)) {
                val p = mapGroupPoint(
                    px, py, gw, gh, width.toFloat(), height.toFloat(),
                    rotationDeg, mirror, flipV
                )
                minX = minOf(minX, p[0]); maxX = maxOf(maxX, p[0])
                minY = minOf(minY, p[1]); maxY = maxOf(maxY, p[1])
            }
        }
        return floatArrayOf(
            (minX + maxX) / 2f, (minY + maxY) / 2f, (maxX - minX) / 2f, (maxY - minY) / 2f
        )
    }

    /**
     * 取三块素材之一（走 [SRC_CACHE]）。
     *
     * ⚠️ 气泡每次点击都会新建一个 BubbleView；若每次都重新解码 assets 里的 PNG
     *    （大气泡 654×896 / 145KB，解码是 10ms 量级），出现动画的头几帧就会顿一下。
     */
    private fun decode(ctx: Context, name: String): Bitmap? {
        val key = if (grayFill) "gray/$name" else name
        SRC_CACHE[key]?.let { if (!it.isRecycled) return it }
        val raw = try {
            ctx.assets.open("$DIR/$name.png").use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        } ?: return null
        val bmp = if (grayFill) grayWhiteFill(raw) else raw
        SRC_CACHE[key] = bmp
        return bmp
    }

    /**
     * 把素材里的**白底**整体换成灰底（描边与抗锯齿边缘按"有多白"线性映射，
     * 于是白↔深蓝的过渡自然变成灰↔深蓝，不会留下白边）。
     *
     * 逐通道做同一份减法：`out = src - (255 - GRAY) * t`，
     * `t` = 该像素"有多白"（0 = 纯描边、1 = 纯白底）。
     * 对一个 50% 混合的抗锯齿像素，这正好等价于"把白色那半换成灰色"。
     */
    private fun grayWhiteFill(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val span = (255 - NAVY_FLOOR).toFloat()
        val dropMax = 255 - GRAY_FILL
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a == 0) continue
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val mn = if (r < g) (if (r < b) r else b) else (if (g < b) g else b)
            val t = ((mn - NAVY_FLOOR) / span).coerceIn(0f, 1f)
            if (t <= 0f) continue
            val drop = (dropMax * t).toInt()
            px[i] = (a shl 24) or
                ((r - drop).coerceAtLeast(0) shl 16) or
                ((g - drop).coerceAtLeast(0) shl 8) or
                (b - drop).coerceAtLeast(0)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    companion object {
        private const val DIR = "bubble"

        /**
         * 深色模式下气泡**内部填充**用的灰（用户 2026-10-16 指定）。
         *
         * 取自他给的那张深色气泡图的底色 —— 实测 RGB(168.8,168.8,168.8) ≈ #A9A9A9。
         */
        private const val GRAY_FILL = 0xA9

        /**
         * 判定"这是描边而不是白底"的通道下限。
         *
         * 素材描边是深蓝（最亮通道 ≈ 15），白底是 255；取 60 作分界，
         * 介于两者之间的抗锯齿像素按比例过渡（见 [grayWhiteFill]）。
         */
        private const val NAVY_FLOOR = 60

        /**
         * 三块素材的**解码缓存**（进程内共享，键 = 素材名）。
         *
         * ⚠️ 这些位图**永不 recycle**：它们在多个 BubbleView 之间共享，
         *    谁都不该回收（[BubbleView.rebuildScaled] 只回收自己那份缩放副本）。
         *    常驻约 2.4MB —— 换来的是"每次点击气泡都不必重新解码 PNG"（用户反馈动画帧率低）。
         */
        private val SRC_CACHE = HashMap<String, Bitmap>(4)

        /** 部件数：小圈 / 中圈 / 大气泡 */
        const val PART_COUNT = 3

        // ---- 气泡内文字的排版参数（与预览脚本同值，用户已验收） ----
        /** 可用区相对描边的内缩比例（实测描边 13px / 短半轴 171px ≈ 4%） */
        private const val STROKE_INSET_RATIO = 0.04f
        /** 每行最多占该处椭圆弦宽的比例（留出与椭圆之间的空隙） */
        private const val CHORD_MARGIN = 0.78f
        /** 文字块总高最多占椭圆高的比例 */
        private const val V_MARGIN = 0.86f
        /** 最小可读字号 = 椭圆高 × 这个比例，用来决定行数 */
        private const val MIN_SIZE_RATIO = 0.16f
        /** 行高倍数 */
        private const val LINE_SPACING = 1.18f
        /** 视觉居中补偿：墨迹中心比 em 中心低约 5%，整体上移这么多 */
        private const val OPTICAL_DY_EM = 0.05f
        /** 居中对齐时"行顶 -> 基线"的比例（近似 ascent 在 em 里的位置） */
        private const val BASELINE_RATIO = 0.80f
        /** 一个汉字约占字号的这个比例（用于算"3 个字的字号"封顶） */
        private const val CHAR_W_RATIO = 1.0f

        /**
         * 每个部件在**整组外接框**里的相对矩形 `[左, 上, 宽, 高]`（0..1）。
         *
         * ⚠️ 由 `tools/extract_bubble_parts.py` 生成，改动请回去重跑脚本。
         *    框各自紧贴该部件的墨水，四角是透明的（见 [visibleEllipses] 的说明）。
         *
         * ⚠️ 深色模式只换填充色、**不换几何** —— 所以两套模式共用这一份部件框。
         */
        val PART_BOX = arrayOf(
            floatArrayOf(0.00000f, 0.44527f, 0.09411f, 0.10299f),   // b0_small 小圈
            floatArrayOf(0.10748f, 0.30697f, 0.11689f, 0.13035f),   // b1_mid   中圈
            floatArrayOf(0.27291f, 0.00000f, 0.72709f, 1.00000f)    // b2_big   大气泡
        )

        /** 整组外接框纵横比（宽 / 高），与素材裁切框一致（1.2 倍后同理） */
        const val GROUP_ASPECT = 1.00495f

        /**
         * 整组尺寸 / 人物**正立素材**的屏幕宽度。
         *
         * 记录的相对尺寸：人物正立素材（1024×875）即"包围矩形 A"（宽 2a、高 b），
         * 气泡整组 1.984375a × 1.974609a = 0.99219W × 0.98730W；
         * **再乘用户 2026-10-16 要求的 1.2 倍** —— 于是：
         *   宽 0.99219 × 1.2 = 1.190628，高 0.98730 × 1.2 = 1.184760
         *
         * ⚠️ 放大是**以小气泡最左点为基准**整体放大：那个点在放大前后不动，
         *    所以整组中心相对人物盒中心的偏移也跟着变大（见 [ART_RIGHT_RATIO]）。
         */
        const val GROUP_W_RATIO = 1.190628f
        const val GROUP_H_RATIO = 1.184760f

        /**
         * 整组中心相对**人物素材盒中心**的偏移（换算基准 = 人物正立素材宽 W），
         * 用**人物素材自己的坐标系**描述：向素材右侧 [ART_RIGHT_RATIO]、向素材上方 [ART_UP_RATIO]。
         *
         * 1.0 倍基线（合成图量得）：右 1.0000W、上 0.4268W（= 齐头顶）；
         * 以"小气泡最左点"(右 0.5039W、上 0.4300W) 为基准放大 1.2 倍后：
         *   右 0.5039 + (1.0000 − 0.5039)×1.2 = 1.0992W
         *   上 0.4300 + (0.4268 − 0.4300)×1.2 = 0.4262W
         *
         * ⚠️ 这两个偏移必须与人物素材走**同一条变换链**（见 [screenOffset]）。
         */
        const val ART_RIGHT_RATIO = 1.0992f
        const val ART_UP_RATIO = 0.4262f

        /** 小圈开始出现到大气泡完全出现（用户指定 0.15s） */
        private const val APPEAR_MS = 150f

        /** 大气泡的停留时长（用户指定 1.5s） */
        private const val HOLD_MS = 1500f

        /** 一个部件的出现 / 消失时长：出现段三等分，正好 0.15s 走完三个 */
        private const val STEP_MS = APPEAR_MS / PART_COUNT

        /** 整段总时长 = 出现 + 停留 + 消失 */
        val TOTAL_MS: Long = (APPEAR_MS + HOLD_MS + STEP_MS * PART_COUNT).toLong()

        /**
         * **消失段**的时长（从"开始消失"到"三块全没"）。
         *
         * 出现段与消失段都是三块各占 [STEP_MS]，所以两者相等 = 用户指定的 0.15s。
         * [vanishNow] 之后服务用它来安排收窗口 —— 必须收得及时：这个悬浮窗
         * **可触摸**（不设 FLAG_NOT_TOUCHABLE，否则系统会把透明度压到 0.8），
         * 留着不放会继续吃掉手指落在它范围内的触摸。
         */
        val VANISH_MS: Long = (STEP_MS * PART_COUNT).toLong()

        /** 小于它就当作已经消失（Float 时间轴在边界上会留下 1e-8 级残值） */
        private const val POP_MIN = 0.01f

        /**
         * 某部件在 [t] 毫秒时的缩放系数；这一刻不该出现时返回 null。
         *
         *   出现：`appear_i = i × STEP` —— 小圈先冒出来
         *   消失：`vanish_i = 0.15s + 1.5s + (PART_COUNT-1-i) × STEP`
         *         —— 大气泡最早开始缩，于是**由大到小**依次消失
         *
         * 抽成纯函数是为了能上单测：出现/消失顺序靠连拍截图去验太不可靠
         * （整段只有 1.8 秒，出现段只有 150ms）。
         */
        internal fun popOf(index: Int, t: Float): Float? {
            val appear = index * STEP_MS
            val vanish = APPEAR_MS + HOLD_MS + (PART_COUNT - 1 - index) * STEP_MS
            val inA = ((t - appear) / STEP_MS).coerceIn(0f, 1f)
            val outA = 1f - ((t - vanish) / STEP_MS).coerceIn(0f, 1f)
            if (inA <= 0f || outA <= 0f) return null
            // 缓动：**出现 ease-out、消失 ease-in**。
            // 0.15s 这么短的动画用线性插值看起来就是"顿"，加缓动后同样的帧率下观感明显更顺
            // （总时长不变，仍是用户指定的 0.15s —— 只是把位移量在时间上重新分配）。
            val inEase = 1f - (1f - inA) * (1f - inA) * (1f - inA)   // 快出慢定
            val outEase = outA * outA                                  // 慢起快收
            // 从 25% 大小"弹"出来：从 0 开始的话第一帧只是个看不见的点，白跑一帧
            val pop = (0.25f + 0.75f * inEase) * outEase
            return if (pop < POP_MIN) null else pop
        }

        /**
         * 气泡**有效区域**（真正画出来的可见部分）在**窗口坐标系**里的内切椭圆：
         * 每项 `[cx, cy, rx, ry]`（大气泡一个椭圆、两个小圈各一个圆）。
         *
         * 为什么要这个：三块素材各自的外框见 [PART_BOX]，框的四角是**透明**的
         * （大气泡是框内切椭圆）。判"气泡有没有压到人物矩形"必须按可见部分算 ——
         * 拿外框判会把"只是透明角擦到"误判成相交（用户 2026-10-16 明确要求）。
         */
        fun visibleEllipses(
            winW: Int, winH: Int, rotDeg: Float, mirror: Boolean, flipV: Boolean
        ): Array<FloatArray> {
            val q = ((rotDeg % 360f) + 360f) % 360f
            val swapped = q == 90f || q == 270f
            val gw = if (swapped) winH.toFloat() else winW.toFloat()
            val gh = if (swapped) winW.toFloat() else winH.toFloat()
            val out = ArrayList<FloatArray>(PART_BOX.size)
            for (box in PART_BOX) {
                val l = box[0] * gw
                val t = box[1] * gh
                val r = l + box[2] * gw
                val b = t + box[3] * gh
                var minX = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE
                var minY = Float.MAX_VALUE
                var maxY = -Float.MAX_VALUE
                for (px in floatArrayOf(l, r)) {
                    for (py in floatArrayOf(t, b)) {
                        val p = mapGroupPoint(
                            px, py, gw, gh, winW.toFloat(), winH.toFloat(), rotDeg, mirror, flipV
                        )
                        minX = minOf(minX, p[0]); maxX = maxOf(maxX, p[0])
                        minY = minOf(minY, p[1]); maxY = maxOf(maxY, p[1])
                    }
                }
                out.add(
                    floatArrayOf(
                        (minX + maxX) / 2f, (minY + maxY) / 2f,
                        (maxX - minX) / 2f, (maxY - minY) / 2f
                    )
                )
            }
            return out.toTypedArray()
        }

        /** 组自身坐标 -> 窗口坐标（变换链与 [BubbleView.onDraw] 严格一致） */
        private fun mapGroupPoint(
            x: Float, y: Float, gw: Float, gh: Float, w: Float, h: Float,
            rotDeg: Float, mirror: Boolean, flipV: Boolean
        ): FloatArray {
            var px = x - gw / 2f
            var py = y - gh / 2f
            if (mirror) px = -px
            if (flipV) py = -py
            val r = Math.toRadians(rotDeg.toDouble())
            val c = Math.cos(r).toFloat()
            val s = Math.sin(r).toFloat()
            return floatArrayOf(px * c - py * s + w / 2f, px * s + py * c + h / 2f)
        }

        /** 按人物正立素材的屏幕宽度，算气泡整组**自身**的尺寸（未旋转） */
        fun groupSize(petUprightW: Float): IntArray {
            val w = (petUprightW * GROUP_W_RATIO).toInt().coerceIn(24, 8192)
            val h = (petUprightW * GROUP_H_RATIO).toInt().coerceAtLeast(1)
            return intArrayOf(w, h)
        }

        /**
         * 窗口尺寸 = 组自身尺寸按人物旋转角一起转（±90° 时宽高互换）。
         *
         * @param rotDeg 人物的吸附旋转角（[PetState.rotationForSnap]）
         */
        fun windowSize(petUprightW: Float, rotDeg: Float): IntArray {
            val g = groupSize(petUprightW)
            val q = ((rotDeg % 360f) + 360f) % 360f
            return if (q == 90f || q == 270f) intArrayOf(g[1], g[0]) else g
        }

        /**
         * 整组中心的偏移：**素材坐标系** -> 屏幕偏移（屏幕坐标 y 向下）。
         *
         * 变换链与人物素材严格一致（顺序不能换）：
         *   1. 先在素材系里按 [side] 取"素材右侧 / 素材左侧"（见 [PetState.bubbleSide]）；
         *   2. 再套人物素材的**镜像(x)**；
         *   3. 再套人物素材的**上下翻转(y)**；
         *   4. 最后按吸附旋转角旋转（与 `canvas.rotate` 同向：正数 = 屏幕顺时针）。
         *
         * @return `[dx, dy]`：屏幕偏移，加在人物盒中心上就是气泡窗口中心
         */
        fun screenOffset(
            petUprightW: Float, side: Int,
            mirror: Boolean, flipV: Boolean, rotDeg: Float
        ): FloatArray {
            var x = side * ART_RIGHT_RATIO * petUprightW
            var y = -ART_UP_RATIO * petUprightW          // 素材坐标 y 向下，"上"取负
            if (mirror) x = -x
            if (flipV) y = -y
            val r = Math.toRadians(rotDeg.toDouble())
            val c = Math.cos(r)
            val s = Math.sin(r)
            return floatArrayOf((x * c - y * s).toFloat(), (x * s + y * c).toFloat())
        }
    }
}
