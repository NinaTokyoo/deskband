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
    private val parts = arrayOf(
        decode(context, "b0_small"),
        decode(context, "b1_mid"),
        decode(context, "b2_big")
    )

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()

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

    /** 是否已经转过 90°（窗口宽高与组自身宽高互换） */
    private val rotated: Boolean get() = (rotationDeg % 180f) != 0f

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
        val target = SystemClock.uptimeMillis() - (APPEAR_MS + HOLD_MS).toLong()
        if (target > startAt) startAt = target
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
            val bmp = parts[i] ?: continue          // 素材缺失就跳过这一块
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

        if (!finished(now)) postInvalidateOnAnimation()
    }

    private fun decode(ctx: Context, name: String): Bitmap? = try {
        ctx.assets.open("$DIR/$name.png").use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val DIR = "bubble"

        /** 部件数：小圈 / 中圈 / 大气泡 */
        const val PART_COUNT = 3

        /**
         * 每个部件在**整组外接框**里的相对矩形 `[左, 上, 宽, 高]`（0..1）。
         *
         * ⚠️ 由 `tools/extract_bubble_parts.py` 生成，改动请回去重跑脚本。
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
            // 从 25% 大小"弹"出来：从 0 开始的话第一帧只是个看不见的点，白跑一帧
            val pop = (0.25f + 0.75f * inA) * outA
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
