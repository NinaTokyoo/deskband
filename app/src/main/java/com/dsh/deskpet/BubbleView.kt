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

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = if (startAt == 0L) 0f else (now - startAt).toFloat()

        // 组自身坐标系：转过 90° 时，窗口的宽高与组宽高互换
        val gw = if (rotated) height.toFloat() else width.toFloat()
        val gh = if (rotated) width.toFloat() else height.toFloat()

        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        if (rotationDeg != 0f) canvas.rotate(rotationDeg)
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

        /** 整组外接框纵横比（宽 / 高），与素材裁切框一致 */
        const val GROUP_ASPECT = 1.00448f

        /**
         * 整组宽度 / 人物**正立素材**的屏幕宽度。
         *
         * 记录的相对尺寸：人物正立素材（1024×875）即"包围矩形 A"（宽 2a、高 b），
         * 气泡整组 1.984375a × 1.974609a —— 于是 1.984375 / 2 = 0.99219。
         */
        const val GROUP_W_RATIO = 0.99219f

        /**
         * 气泡中心相对**人物包围盒中心**的偏移（单位 = 人物正立素材宽 W），
         * 在"人物正立"的坐标系里：向右 [OFFSET_RIGHT_RATIO]、向上 [OFFSET_UP_RATIO]。
         *
         * 来自记录的合成图：人物盒 1024×875、气泡整组 871×867、组中心在 (1504.5, 474.5)，
         * 人物盒中心在 (553, 911.5) —— 于是偏移 = (951.5, -437) px = (0.9292W, 0.4268W)。
         *
         * ⚠️ 人物躺倒时**整组跟着转同一个角度**，这两个偏移也要一起转
         * （见 PetService.showBubble 里的旋转矩阵）—— 转完气泡就跑到人物上方去了。
         */
        const val OFFSET_RIGHT_RATIO = 0.9292f
        const val OFFSET_UP_RATIO = 0.4268f

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

        /** 按人物正立素材的屏幕宽度，算气泡整组**自身**的尺寸（未旋转） */
        fun groupSize(petUprightW: Float): IntArray {
            val w = (petUprightW * GROUP_W_RATIO).toInt().coerceIn(24, 4096)
            val h = (w / GROUP_ASPECT).toInt().coerceAtLeast(1)
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
    }
}
