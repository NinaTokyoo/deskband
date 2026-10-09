package com.dsh.deskpet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 桌宠本体。
 *
 * ## 窗口策略
 *
 * 窗口**只有宠物的外接矩形那么大**（外加一点给"轻跳"留的余量），不是整屏。
 * 这样"不挡屏幕"由 Android 的窗口机制天然保证 —— 窗口之外的触摸根本不会派发到这里。
 *
 * （踩过的坑：曾经用整屏窗口 + FLAG_NOT_TOUCH_MODAL，而那个 flag 的语义是
 *  "本窗口之外的触摸由我自己处理"，结果整个屏幕都点不动。）
 *
 * ## 坐标
 *
 * [anchorX]/[anchorY] 是**素材格子左上角**在屏幕上的位置（拖动时是目标位置）。
 * 窗口位置由格子位置 + 外接矩形偏移推出来，见 [placeWindow]。
 *
 * ## 旋转
 *
 * 旋转只作用在 Canvas 上（绕格子中心），**从不修改格子宽高 —— 所以比例永远不变**。
 * 左半屏用镜像 + 上下翻转得到，不额外存图。
 *
 * 稳定朝向只有两种：正立 0° 与旋转 ∓90°，**中间没有过渡角度**
 * （用户要求"稳定存在的只有正立与旋转90°"）。
 */
@SuppressLint("ViewConstructor")
class PetView(context: Context) : View(context) {

    interface Host {
        fun bounds(): Rect

        /** 把窗口移动到 [x],[y] 并按 [w],[h] 调整大小 */
        fun placeWindow(x: Int, y: Int, w: Int, h: Int)

        /** 拖动结束后回报状态与位置，供持久化 */
        fun onSettled(state: Int, left: Int, top: Int)

        fun onTapped()
    }

    var host: Host? = null

    // ---------------------------------------------------------------- 状态

    var sprite: PetSprite? = null
        set(value) {
            field = value
            cacheSizes()
            cancelTween()
            invalidate()
        }

    /** 当前状态（决定素材与旋转） */
    var state: Int = PetState.CORNER_BR
        private set

    private var sizeFrac = 0.15f

    /** 素材格子左上角（屏幕坐标） */
    private var anchorX = 0f
    private var anchorY = 0f

    /** 拖动起点，避免累计取整误差 */
    private var dragStartX = 0f
    private var dragStartY = 0f

    // ---------------------------------------------------------------- 缓存的尺寸
    //
    // 这些以前每次都现算，拖动时每帧算好几遍，是卡顿的一个来源。
    // 只在素材/状态/大小比例变化时重算。

    private var cellW = 1
    private var cellH = 1
    private var trackW = 1
    private var trackH = 1
    private var bboxW = 1
    private var bboxH = 1
    private var boxOffX = 0
    private var boxOffY = 0

    private fun cacheSizes() {
        val s = sprite ?: return
        val screenH = (host?.bounds()?.height() ?: 1080).toFloat()
        val cell = PetState.scaledSize(
            s.naturalWidth(state), s.naturalHeight(state), screenH * sizeFrac
        )
        cellW = cell[0]
        cellH = cell[1]

        val rot = PetState.rotationForSnap(state)
        val q = ((rot % 360f) + 360f) % 360f
        val rotated = q == 90f || q == 270f
        trackW = if (rotated) cellH else cellW
        trackH = if (rotated) cellW else cellH

        // 外接矩形：旋转后可能互换，另外给"轻跳"留出上方余量
        bboxW = trackW + HOP_PAD_X * 2
        bboxH = trackH + HOP_PAD_TOP + HOP_PAD_BOTTOM
        boxOffX = HOP_PAD_X
        boxOffY = HOP_PAD_TOP
    }

    fun setSizeFraction(f: Float) {
        sizeFrac = f.coerceIn(0.05f, 0.9f)
        cacheSizes()
        // 尺寸变了，窗口必须跟着变，否则宠物会超出窗口导致点不到（踩过这个坑）
        invalidate()
    }

    fun setState(newState: Int) {
        if (state == newState) return
        state = newState
        cacheSizes()
        invalidate()
    }

    // ---------------------------------------------------------------- 动画

    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val src = Rect()
    private val dst = RectF()

    private var hopping = false
    private var hopStart = 0L

    /** 吸附时的位移补间：避免"瞬移" */
    private var tweening = false
    private var tweenFromX = 0f
    private var tweenFromY = 0f
    private var tweenToX = 0f
    private var tweenToY = 0f
    private var tweenStart = 0L
    private var tweenMs = SNAP_MS

    // ---- 跨半屏的镜像翻转动画 ----
    /** 0 = 未翻转，1 = 已翻转；中间值即翻转过程 */
    private var flip = 0f
    private var flipFrom = 0f
    private var flipTo = 0f
    private var flipStart = 0L
    private var flipRunning = false

    private var dragging = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    /** 这次拖动是不是"从吸附状态脱开"，决定要不要加阻尼 */
    private var dragFromSnap = false
    private var downRawX = 0f
    private var downRawY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var lastWinX = Int.MIN_VALUE
    private var lastWinY = Int.MIN_VALUE
    private var lastWinW = -1
    private var lastWinH = -1

    private val ticker = object : Runnable {
        override fun run() {
            var need = false
            val now = SystemClock.uptimeMillis()

            if (tweening) {
                val t = ((now - tweenStart).toFloat() / tweenMs).coerceIn(0f, 1f)
                val e = easeOutCubic(t)
                anchorX = tweenFromX + (tweenToX - tweenFromX) * e
                anchorY = tweenFromY + (tweenToY - tweenFromY) * e
                if (t >= 1f) {
                    anchorX = tweenToX
                    anchorY = tweenToY
                    tweening = false
                }
                need = true
            }
            if (flipRunning) {
                val t = ((now - flipStart).toFloat() / FLIP_MS).coerceIn(0f, 1f)
                flip = flipFrom + (flipTo - flipFrom) * easeOutCubic(t)
                if (t >= 1f) {
                    flip = flipTo
                    flipRunning = false
                }
                need = true
            }
            if (hopping) {
                val t = ((now - hopStart).toFloat() / HOP_MS).coerceIn(0f, 1f)
                if (t >= 1f) hopping = false
                need = true
            }
            if (guideVisible()) {
                need = true
            } else if (lastWinW == (host?.bounds()?.width() ?: -2)) {
                // 指示线刚消失：把窗口收回宠物大小
                restoreWindowSize()
                need = true
            }
            invalidate()
            if (need) handler.postDelayed(this, FRAME_MS)
        }
    }

    private fun cancelTween() {
        tweening = false
    }

    /** 平滑移动到目标位置；任何"宠物自己动"的地方都走这里 */
    fun animateTo(toX: Float, toY: Float, durationMs: Long = SNAP_MS.toLong()) {
        tweenFromX = anchorX
        tweenFromY = anchorY
        tweenToX = toX
        tweenToY = toY
        if (abs(anchorX - toX) < 0.5f && abs(anchorY - toY) < 0.5f) {
            anchorX = toX
            anchorY = toY
            tweening = false
            invalidate()
            return
        }
        tweenMs = durationMs.toFloat()
        tweenStart = SystemClock.uptimeMillis()
        tweening = true
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun startTween(toX: Float, toY: Float) = animateTo(toX, toY)

    /** 跨半屏时的镜像翻转动画 */
    private fun startFlip(target: Float) {
        if (abs(flip - target) < 0.01f && !flipRunning) {
            flip = target
            return
        }
        flipFrom = flip
        flipTo = target
        flipStart = SystemClock.uptimeMillis()
        flipRunning = true
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    // ---------------------------------------------------------------- 位置

    private fun curX(): Float = anchorX + dragOffsetX
    private fun curY(): Float = anchorY + dragOffsetY

    /** 当前显示位置（屏幕坐标，格子左上角） */
    fun currentLeft(): Int = curX().roundToInt()
    fun currentTop(): Int = curY().roundToInt()

    private fun guideVisible(): Boolean = SystemClock.uptimeMillis() < guideUntil

    /**
     * 把窗口挪到与格子对齐的位置，并在需要时调整窗口尺寸。
     *
     * 窗口尺寸只在**状态/尺寸真的变了**的时候才调用 updateViewLayout 改，
     * 纯移动不会触发 resize —— 否则拖动时每帧 resize 一次，卡到不能用。
     *
     * 例外：显示 A 区指示线时窗口临时撑满整屏（线要横跨全屏）。
     * 那几秒用户在 App 里调参数，短暂的整屏窗口可以接受。
     */
    private fun placeWindow() {
        val guides = guideVisible()
        val b = host?.bounds()
        if (guides && b != null) {
            val x = 0
            val y = 0
            val w = b.width()
            val h = b.height()
            if (x == lastWinX && y == lastWinY && w == lastWinW && h == lastWinH) return
            lastWinX = x; lastWinY = y; lastWinW = w; lastWinH = h
            host?.placeWindow(x, y, w, h)
            return
        }
        val x = (curX() - boxOffX).roundToInt()
        val y = (curY() - boxOffY).roundToInt()
        if (x == lastWinX && y == lastWinY && bboxW == lastWinW && bboxH == lastWinH) return
        lastWinX = x
        lastWinY = y
        lastWinW = bboxW
        lastWinH = bboxH
        host?.placeWindow(x, y, bboxW, bboxH)
    }

    /** 指示线消失后要把窗口收回宠物大小 */
    private fun restoreWindowSize() {
        lastWinW = -1
        placeWindow()
    }

    /** 把窗口尺寸同步过来（大小变了之后必须调用，否则宠物会超出窗口点不到） */
    fun syncWindow() {
        lastWinW = -1
        cacheSizes()
        placeWindow()
        invalidate()
    }

    // ---------------------------------------------------------------- 吸附

    /** 贴到某个状态的标准位置（贴边） */
    fun applySnap(newState: Int) {
        val b = host?.bounds() ?: return
        state = newState
        cacheSizes()
        anchorX = if (PetState.isLeft(newState)) 0f else (b.width() - trackW).toFloat()
        anchorY = if (PetState.isZoneless(newState)) ((b.height() - trackH) / 2f)
        else (b.height() - trackH).toFloat()
        dragOffsetX = 0f
        dragOffsetY = 0f
        cancelTween()
        syncWindow()
    }

    /** 从保存的位置恢复 */
    fun restore(state0: Int, left: Int, top: Int) {
        state = state0
        cacheSizes()
        anchorX = left.toFloat()
        anchorY = top.toFloat()
        dragOffsetX = 0f
        dragOffsetY = 0f
        cancelTween()
        syncWindow()
    }

    // ---------------------------------------------------------------- 触摸

    /**
     * 命中判定：**逐像素 alpha**。
     *
     * 窗口已经只有宠物那么大，但格子是矩形、人物只占其中一部分，
     * 所以还要看该点在图上的 alpha —— 透明处返回 false，触摸继续往下传。
     */
    private fun hitsPet(localX: Float, localY: Float): Boolean {
        val s = sprite ?: return false
        val cx = boxOffX + cellW / 2f
        val cy = boxOffY + cellH / 2f
        var dx = localX - cx
        var dy = localY - cy

        val hop = hopOffset()
        dy -= hop

        // 反掉翻转动画的水平压缩
        if (flip > 0.001f && flip < 0.999f) {
            val squash = abs(1f - 2f * flip).coerceAtLeast(0.03f)
            dx /= squash
        }

        val rot = PetState.rotationForSnap(state)
        if (abs(rot) > 0.01f) {
            val rad = Math.toRadians(-rot.toDouble())
            val c = cos(rad).toFloat()
            val sn = sin(rad).toFloat()
            val rx = dx * c - dy * sn
            val ry = dx * sn + dy * c
            dx = rx
            dy = ry
        }

        val u = dx / cellW + 0.5f
        val v = dy / cellH + 0.5f
        if (u < 0f || u > 1f || v < 0f || v > 1f) return false

        val bmp = runCatching { bitmapFor(state) }.getOrNull() ?: return true
        val bx = (u * (bmp.width - 1)).toInt().coerceIn(0, bmp.width - 1)
        val by = (v * (bmp.height - 1)).toInt().coerceIn(0, bmp.height - 1)
        return runCatching { ((bmp.getPixel(bx, by) ushr 24) and 0xFF) >= HIT_ALPHA_MIN }
            .getOrDefault(true)
    }

    private fun bitmapFor(st: Int): Bitmap? {
        val s = sprite ?: return null
        // 左半屏**只做水平镜像**（不做上下翻转）。
        // 翻转旋转角符号（见 PetState.rotationForSnap）已经能让两侧都"头朝内"，
        // 再加纵向翻转反而会把人物倒过来。
        return s.bitmap(st, mirror = PetState.isLeft(st), flipV = false)
    }

    private fun hopOffset(): Float {
        if (!hopping) return 0f
        val t = ((SystemClock.uptimeMillis() - hopStart).toFloat() / HOP_MS).coerceIn(0f, 1f)
        return -cellH * HOP_FRACTION * sin(t * Math.PI).toFloat()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!hitsPet(event.x, event.y)) return false
                downRawX = event.rawX
                downRawY = event.rawY
                dragStartX = anchorX
                dragStartY = anchorY
                dragOffsetX = 0f
                dragOffsetY = 0f
                dragging = false
                cancelTween()
                // 吸附状态（贴边）起步才加阻尼；空中起拖直接 1:1 跟手
                dragFromSnap = PetState.isAttachedState(state)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                if (dragging) {
                    // 严格跟手；只有"刚从吸附状态脱开"那一下带一点阻尼，
                    // 免得贴边的宠物被轻轻一碰就弹走。
                    val f = dampFactor(dx, dy)
                    dragOffsetX = dx * f
                    dragOffsetY = dy * f
                    updateDragState()
                    placeWindow()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    // 把拖动结果固化进 anchor，避免累计误差
                    anchorX = dragStartX + dragOffsetX
                    anchorY = dragStartY + dragOffsetY
                    dragOffsetX = 0f
                    dragOffsetY = 0f
                    settle()
                } else {
                    onTapped()
                }
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * 拖动阻尼系数：严格跟手时返回 1。
     *
     * 只有当**这次拖动是从吸附状态开始的**才加阻尼 —— 已经拖在空中的宠物
     * 再拖就是 1:1 跟手，不该有粘滞感。
     *
     * 阻尼期内的总让位是固定的 [DAMP_TAIL_PX]：
     *   手指走 DAMP_HEAD_PX 时宠物才开始让位，
     *   让位到 DAMP_TAIL_PX（总位移约 DAMP_FULL_PX）时阻尼结束，之后 1:1。
     * 这样"脱开那一下"的手感是确定的，不随手指速度变化。
     */
    private fun dampFactor(dx: Float, dy: Float): Float {
        if (!dragFromSnap) return 1f
        val d = kotlin.math.hypot(dx, dy)
        if (d >= DAMP_FULL_PX) return 1f
        if (d <= DAMP_HEAD_PX) return DAMP_TAIL_PX / DAMP_HEAD_PX
        val t = (d - DAMP_HEAD_PX) / (DAMP_FULL_PX - DAMP_HEAD_PX)
        val eased = t * t * (3f - 2f * t)
        val m = DAMP_TAIL_PX / DAMP_HEAD_PX
        return m + (1f - m) * eased
    }

    /** 拖动过程中刷新状态（素材与旋转随之变化，没有中间角度） */    private fun updateDragState() {
        val b = host?.bounds() ?: return
        val x = curX()
        val y = curY()
        val leftHalf = (x + trackW / 2f) < b.width() / 2f

        // 跨半屏 -> 触发镜像翻转动画
        startFlip(if (leftHalf) 1f else 0f)

        val db = PetState.bottomDist(y, trackH.toFloat(), b.height().toFloat())
        val dr = PetState.sideDist(x, trackW.toFloat(), b.width().toFloat(), leftHalf)

        val zone = PetState.zoneOf(db, dr, leftHalf)
        var st = PetState.stateForZone(zone, leftHalf)
        // E 区：正立摆放时右边缘超出屏幕 -> 转 90°
        if (zone == PetState.ZONE_E) {
            val rot = PetState.eRotation(leftHalf, x, cellW.toFloat(), b.width().toFloat())
            if (rot != 0f) st = if (leftHalf) PetState.BORDER_L else PetState.BORDER_R
        }
        setState(st)
    }

    /** 松手吸附：贴边 + 平滑补间过去（不瞬移） */
    private fun settle() {
        val b = host?.bounds() ?: return
        val x = anchorX
        val y = anchorY
        val leftHalf = (x + trackW / 2f) < b.width() / 2f
        val db = PetState.bottomDist(y, trackH.toFloat(), b.height().toFloat())
        val dr = PetState.sideDist(x, trackW.toFloat(), b.width().toFloat(), leftHalf)

        val zone = PetState.zoneOf(db, dr, leftHalf)
        var st = PetState.stateForZone(zone, leftHalf)
        if (zone == PetState.ZONE_E) {
            val rot = PetState.eRotation(leftHalf, x, cellW.toFloat(), b.width().toFloat())
            if (rot != 0f) st = if (leftHalf) PetState.BORDER_L else PetState.BORDER_R
        }
        state = st
        cacheSizes()
        // 吸附完成时翻转值要对齐到所在半屏，避免残影
        flip = if (leftHalf) 1f else 0f
        flipFrom = flip
        flipTo = flip
        flipRunning = false

        val target = PetState.snapPosition(
            x, y, trackW.toFloat(), trackH.toFloat(), db, dr,
            b.width().toFloat(), b.height().toFloat(), leftHalf
        )
        val c = PetState.clampToScreen(
            target[0], target[1], trackW.toFloat(), trackH.toFloat(),
            b.width().toFloat(), b.height().toFloat()
        )

        startTween(c[0], c[1])
        placeWindow()
        host?.onSettled(state, tweenToX.roundToInt(), tweenToY.roundToInt())
    }

    private fun onTapped() {
        hopping = true
        hopStart = SystemClock.uptimeMillis()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        host?.onTapped()
    }

    // ---------------------------------------------------------------- 绘制

    override fun onDraw(canvas: Canvas) {
        val s = sprite ?: return
        placeWindow()

        val hop = hopOffset()
        val rot = PetState.rotationForSnap(state)
        val cx = boxOffX + cellW / 2f
        val cy = boxOffY + cellH / 2f

        val save = canvas.save()
        canvas.translate(0f, hop)
        if (abs(rot) > 0.01f) canvas.rotate(rot, cx, cy)
        // 跨半屏的镜像翻转：中途压成一条线再展开，做出"翻面"观感
        if (flip > 0.001f && flip < 0.999f) {
            val squash = abs(1f - 2f * flip).coerceAtLeast(0.03f)
            canvas.scale(squash, 1f, cx, cy)
        }

        val bmp = bitmapFor(state) ?: run {
            canvas.restoreToCount(save)
            return
        }
        src.set(0, 0, bmp.width, bmp.height)
        dst.set(boxOffX.toFloat(), boxOffY.toFloat(),
            (boxOffX + cellW).toFloat(), (boxOffY + cellH).toFloat())
        canvas.drawBitmap(bmp, src, dst, paint)
        canvas.restoreToCount(save)

        // A 区指示线画在最上层
        if (guideVisible()) drawGuides(canvas)
    }

    /** 息屏时停表省电 */
    private var paused = false

    fun setPaused(p: Boolean) {
        if (paused == p) return
        paused = p
        if (p) handler.removeCallbacks(ticker) else invalidate()
    }

    // ---------------------------------------------------------------- A 区指示线

    private var guideUntil = 0L
    private val guidePaint = Paint().apply {
        color = 0xFFE53935.toInt()   // 细红线
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    /**
     * 显示 A 区指示线：左侧那条是"离侧边 aW"，上侧那条是"离底边 aH"。
     *
     * 只在用户调 A 区大小时短暂显示，方便对照。
     */
    fun flashGuides(durationMs: Long = 2500L) {
        guideUntil = SystemClock.uptimeMillis() + durationMs
        invalidate()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun drawGuides(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (now >= guideUntil) return
        val b = host?.bounds() ?: return
        val aw = PetState.currentAW
        val ah = PetState.currentAH

        // 竖线：右侧 A 区左边界 = 屏宽 - aw；左侧镜像 = aw
        val rightHalf = !PetState.isLeft(state)
        val xLine = if (rightHalf) b.width() - aw else aw
        canvas.drawLine(xLine, 0f, xLine, b.height().toFloat(), guidePaint)

        // 横线：A 区上边界 = 屏高 - ah
        val yLine = b.height() - ah
        canvas.drawLine(0f, yLine, b.width().toFloat(), yLine, guidePaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        cacheSizes()
        setMeasuredDimension(bboxW, bboxH)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        cacheSizes()
        placeWindow()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private fun easeOutCubic(t: Float): Float {
        val u = 1f - t
        return 1f - u * u * u
    }

    companion object Geometry {
        private const val FRAME_MS = 16L
        private const val HIT_ALPHA_MIN = 16
        private const val SNAP_MS = 220f
        private const val FLIP_MS = 200f
        private const val HOP_MS = 380f
        private const val HOP_FRACTION = 0.08f

        /**
         * 拖动阻尼：刚脱离吸附时先走一段"带阻力"的位移。
         *
         * 阻尼期总位移固定为 [DAMP_TAIL_PX]（手指走多远，宠物最终都只让出这么多），
         * 这样"脱开手感"是确定的，不受手指速度影响。
         */
        const val DAMP_HEAD_PX = 26f
        const val DAMP_TAIL_PX = 14f
        const val DAMP_FULL_PX = 64f

        /** 窗口给"轻跳"和四角旋转留的余量，避免边缘被切 */
        const val HOP_PAD_X = 8
        const val HOP_PAD_TOP = 48
        const val HOP_PAD_BOTTOM = 8

        /** 服务在 addView 前算初始窗口用 */
        fun windowRectFor(
            s: PetSprite,
            state: Int,
            sizeFrac: Float,
            screen: Rect
        ): IntArray {
            val screenH = screen.height().toFloat()
            val cell = PetState.scaledSize(
                s.naturalWidth(state), s.naturalHeight(state), screenH * sizeFrac
            )
            val rot = PetState.rotationForSnap(state)
            val q = ((rot % 360f) + 360f) % 360f
            val rotated = q == 90f || q == 270f
            val tw = if (rotated) cell[1] else cell[0]
            val th = if (rotated) cell[0] else cell[1]

            val x = if (PetState.isLeft(state)) 0 else screen.width() - tw
            val y = if (PetState.isZoneless(state)) (screen.height() - th) / 2
            else screen.height() - th

            return intArrayOf(
                x - HOP_PAD_X, y - HOP_PAD_TOP,
                tw + HOP_PAD_X * 2, th + HOP_PAD_TOP + HOP_PAD_BOTTOM
            )
        }
    }
}
