package com.dsh.deskpet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
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
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 桌宠本体。
 *
 * ## 窗口策略（关键）
 *
 * 窗口**只有宠物那么大**，不是整屏。
 * 这样"不挡屏幕"由 Android 的窗口机制天然保证 —— 窗口之外的触摸根本不会派发到这里，
 * 会直接落到下面的桌面/应用上，不需要任何 flag 技巧。
 *
 * （踩过的坑：曾经用整屏窗口 + FLAG_NOT_TOUCH_MODAL，而那个 flag 的语义是
 *  "本窗口之外的触摸由我自己处理"，结果整个屏幕都点不动。）
 *
 * ## 坐标系统
 *
 * 窗口按 [bbox] 调整大小，[bbox] 是"人物旋转后的外接矩形"。
 * 绘制时把画布平移到窗口中心，再绕中心 [shownRotation] 度旋转，
 * 最后把素材格子居中画出来。
 *
 * **旋转只改变画布变换，绝不修改素材格子的宽高 —— 所以任何角度下比例都不变。**
 *
 * ## 交互
 *
 * 悬浮时只有单击和拖动；命中判定是**逐像素 alpha**，透明处一律放过。
 */
@SuppressLint("ViewConstructor")
class PetView(context: Context) : View(context) {

    interface Host {
        fun bounds(): Rect

        /** 把窗口移动到 [x],[y] 并按 [w],[h] 调整大小 */
        fun placeWindow(x: Int, y: Int, w: Int, h: Int)

        fun onSettled(state: Int, left: Int, top: Int)
        fun onTapped()
    }

    var host: Host? = null

    // ---------------------------------------------------------------- 状态

    var sprite: PetSprite? = null
        set(value) {
            field = value
            relayout()
            invalidate()
        }

    /** 当前吸附状态（PetState.CORNER_* / BORDER_*），也在拖动中实时变化 */
    var state: Int = PetState.CORNER_BR
        private set

    /** 用户设定的大小比例（占屏幕高度的比例，作用在"人物实际高度"上） */
    private var sizeFrac = 0.15f

    /** 格子左上角（屏幕坐标，未含拖动偏移） */
    private var anchorX = 0f
    private var anchorY = 0f

    // ---------------------------------------------------------------- 绘制状态

    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val blurPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val src = Rect()
    private val dst = RectF()

    private var blurFilter: BlurMaskFilter? = null
    private var blurRadius = -1f

    // 翻转动画（跨屏幕中线）
    private var flip = 0f
    private var flipFrom = 0f
    private var flipTo = 0f
    private var flipStart = 0L
    private var flipRunning = false
    private val flipDuration = 220L

    // 旋转动画
    private var shownRotation = 0f
    private var targetRotation = 0f
    private var rotAnimating = false

    // 点击反馈
    private var hopStart = 0L
    private var hopping = false

    // ---------------------------------------------------------------- 拖动

    private var dragging = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    /** 上一次提交给窗口的布局，避免反复 updateViewLayout */
    private var lastWinX = Int.MIN_VALUE
    private var lastWinY = Int.MIN_VALUE
    private var lastWinW = -1
    private var lastWinH = -1

    private val ticker = object : Runnable {
        override fun run() {
            var need = false
            val now = SystemClock.uptimeMillis()

            if (flipRunning) {
                val t = ((now - flipStart).toFloat() / flipDuration).coerceIn(0f, 1f)
                flip = flipFrom + (flipTo - flipFrom) * easeOutCubic(t)
                if (t >= 1f) {
                    flip = flipTo
                    flipRunning = false
                }
                need = true
            }
            if (hopping) {
                val t = ((now - hopStart).toFloat() / 420f).coerceIn(0f, 1f)
                if (t >= 1f) hopping = false
                need = true
            }
            if (rotAnimating) {
                val diff = targetRotation - shownRotation
                if (abs(diff) < 0.15f) {
                    shownRotation = targetRotation
                    rotAnimating = false
                } else {
                    shownRotation += diff * 0.22f
                    need = true
                }
            }
            // 旋转动画会改变外接矩形，窗口要跟着调整
            relayout()
            invalidate()
            if (need) handler.postDelayed(this, FRAME_MS)
        }
    }

    // ---------------------------------------------------------------- 几何

    /** 当前"人物"在屏幕上的格子尺寸（按**当前该用的素材**算） */
    private fun cellSize(): IntArray {
        val s = sprite ?: return intArrayOf(1, 1)
        val screenH = (host?.bounds()?.height() ?: 1080).toFloat()
        val cat = state
        return PetState.scaledSize(
            s.naturalWidth(cat),
            s.naturalHeight(cat),
            screenH * sizeFrac
        )
    }

    /** 该状态最终会转多少度（用状态定，不用动画值，避免窗口抖动） */
    private fun layoutRotation(st: Int): Float = PetState.rotationForSnap(st)

    /**
     * 人物旋转后的外接矩形尺寸。
     * 只有旋转到非 0/90 的中间角度时才会比格子大，所以窗口要留出余量。
     */
    private fun bboxFor(w: Int, h: Int, rotDeg: Float): IntArray {
        if (rotDeg % 90f == 0f) {
            // 0/180 用 (w,h)，90/270 用 (h,w)
            val q = ((rotDeg % 360f) + 360f) % 360f
            return if (q == 90f || q == 270f) intArrayOf(h, w) else intArrayOf(w, h)
        }
        val r = Math.toRadians(rotDeg.toDouble())
        val c = abs(cos(r)).toFloat()
        val s = abs(sin(r)).toFloat()
        return intArrayOf(
            ceil(w * c + h * s).toInt().coerceAtLeast(1),
            ceil(w * s + h * c).toInt().coerceAtLeast(1)
        )
    }

    private fun bbox(): IntArray {
        val cell = cellSize()
        return bboxFor(cell[0], cell[1], shownRotation)
    }

    /**
     * 判定"离边多远"时该用的尺寸 —— **旋转后的屏幕足迹**，不是未旋转的格子。
     *
     * 踩过的坑：贴侧边时用未旋转的格子宽度算 x，而人物已经转了 90°，
     * 屏幕上的实际宽度是格子的高，结果右边留出 78px 的缝。
     */
    private fun trackSize(): IntArray {
        val cell = cellSize()
        val r = PetState.rotationForSnap(state)
        val q = ((r % 360f) + 360f) % 360f
        return if (q == 90f || q == 270f) intArrayOf(cell[1], cell[0]) else intArrayOf(cell[0], cell[1])
    }

    /** 把窗口挪到当前位置并按外接矩形调整大小 */
    private fun relayout() {
        val b = bbox()
        val x = (anchorX + dragOffsetX).roundToInt()
        val y = (anchorY + dragOffsetY).roundToInt()
        if (x == lastWinX && y == lastWinY && b[0] == lastWinW && b[1] == lastWinH) return
        lastWinX = x
        lastWinY = y
        lastWinW = b[0]
        lastWinH = b[1]
        host?.placeWindow(x, y, b[0], b[1])
    }

    /** 格子左上角在窗口内的本地坐标 */
    private fun localCell(): FloatArray {
        val cell = cellSize()
        val b = bbox()
        return floatArrayOf(
            (b[0] - cell[0]) / 2f,
            (b[1] - cell[1]) / 2f
        )
    }

    fun setSizeFraction(f: Float) {
        sizeFrac = f.coerceIn(0.05f, 0.6f)
        relayout()
        invalidate()
    }

    // ---------------------------------------------------------------- 吸附定位

    /** 贴到某个状态的吸附位置（贴边） */
    fun applySnap(newState: Int) {
        state = newState
        val b = host?.bounds() ?: return
        val cell = cellSize()
        val w = cell[0]
        val h = cell[1]

        anchorX = if (PetState.isLeft(newState)) 0f else (b.width() - w).toFloat()
        anchorY = if (PetState.isCorner(newState)) {
            (b.height() - h).toFloat()
        } else {
            ((b.height() - h) / 2f)
        }
        flip = if (PetState.isLeft(newState)) 1f else 0f
        flipFrom = flip
        flipTo = flip
        flipRunning = false
        shownRotation = PetState.rotationForSnap(newState)
        targetRotation = shownRotation
        rotAnimating = false
        relayout()
        invalidate()
    }

    /** 从保存的位置恢复（服务启动时用） */
    fun restore(state0: Int, left: Int, top: Int) {
        state = state0
        flip = if (PetState.isLeft(state0)) 1f else 0f
        flipFrom = flip
        flipTo = flip
        shownRotation = PetState.rotationForSnap(state0)
        targetRotation = shownRotation
        anchorX = left.toFloat()
        anchorY = top.toFloat()
        relayout()
        invalidate()
    }

    /** 当前位置（给服务保存用） */
    fun currentLeft(): Int = (anchorX + dragOffsetX).roundToInt()
    fun currentTop(): Int = (anchorY + dragOffsetY).roundToInt()

    // ---------------------------------------------------------------- 触摸

    /**
     * 窗口只有宠物那么大，所以能进来的触摸已经离宠物很近了。
     * 这里再做一次**逐像素 alpha** 判定：素材透明的地方照样放过。
     */
    private fun hitsPet(localX: Float, localY: Float): Boolean {
        val s = sprite ?: return false
        val cell = cellSize()
        val w = cell[0].toFloat()
        val h = cell[1].toFloat()
        val lc = localCell()
        val cx = lc[0] + w / 2f
        val cy = lc[1] + h / 2f

        var dx = localX - cx
        var dy = localY - cy

        // 反掉翻转动画的水平压缩
        if (flip > 0.001f && flip < 0.999f) {
            val squash = abs(1f - 2f * flip).coerceAtLeast(0.03f)
            dx /= squash
        }
        // 反掉旋转
        if (abs(shownRotation) > 0.01f) {
            val rad = Math.toRadians(-shownRotation.toDouble())
            val c = cos(rad).toFloat()
            val sn = sin(rad).toFloat()
            val rx = dx * c - dy * sn
            val ry = dx * sn + dy * c
            dx = rx
            dy = ry
        }

        val u = dx / w + 0.5f
        val v = dy / h + 0.5f
        if (u < 0f || u > 1f || v < 0f || v > 1f) return false

        val bmp = runCatching { s.bitmap(state, PetState.needsMirror(state)) }.getOrNull()
            ?: return true
        val bx = (u * (bmp.width - 1)).toInt().coerceIn(0, bmp.width - 1)
        val by = (v * (bmp.height - 1)).toInt().coerceIn(0, bmp.height - 1)
        return runCatching { ((bmp.getPixel(bx, by) ushr 24) and 0xFF) >= HIT_ALPHA_MIN }
            .getOrDefault(true)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!hitsPet(event.x, event.y)) return false
                downRawX = event.rawX
                downRawY = event.rawY
                dragging = false
                dragOffsetX = 0f
                dragOffsetY = 0f
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                if (dragging) {
                    dragOffsetX = dx
                    dragOffsetY = dy
                    updateDragVisual()
                    relayout()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) settle() else onTapped()
                dragging = false
                dragOffsetX = 0f
                dragOffsetY = 0f
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 拖动过程中刷新状态：贴哪条边、用哪套素材、转多少度 */
    private fun updateDragVisual() {
        val b = host?.bounds() ?: return
        val t = trackSize()
        val x = anchorX + dragOffsetX
        val y = anchorY + dragOffsetY
        val leftHalf = (x + t[0] / 2f) < b.width() / 2f
        val db = PetState.bottomDist(y, t[1].toFloat(), b.height().toFloat())
        val dr = PetState.sideDist(x, t[0].toFloat(), b.width().toFloat(), leftHalf)

        // 状态跟着拖动实时更新，素材/朝向都由它决定
        state = PetState.stateForDrag(db, dr, leftHalf)

        // 跨中线翻转
        startFlip(if (leftHalf) 1f else 0f)

        // 旋转直接跟随连续场（手指走多少就转多少）
        shownRotation = PetState.rotationForDrag(db, dr, leftHalf)
        targetRotation = shownRotation
        rotAnimating = false
    }

    private fun startFlip(target: Float) {
        if (abs(flip - target) < 0.01f && !flipRunning) return
        flipFrom = flip
        flipTo = target
        flipStart = SystemClock.uptimeMillis()
        flipRunning = true
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    /**
     * 松手：按所在区吸附。
     *
     * 吸附 = 真的贴到边上，另一轴保留松手位置（左半屏镜像）。
     * **先用吸附后的状态算出格子尺寸，再用它算贴边位置** ——
     * 否则角落素材和边框素材宽度不同，贴边会留缝。
     */
    private fun settle() {
        val b = host?.bounds() ?: return
        val preTrack = trackSize()
        val x = anchorX + dragOffsetX
        val y = anchorY + dragOffsetY
        val leftHalf = (x + preTrack[0] / 2f) < b.width() / 2f
        val db = PetState.bottomDist(y, preTrack[1].toFloat(), b.height().toFloat())
        val dr = PetState.sideDist(x, preTrack[0].toFloat(), b.width().toFloat(), leftHalf)

        state = PetState.stateForDrag(db, dr, leftHalf)

        // 用**吸附后**的屏幕足迹算贴边位置。
        // 人物在侧边是转 90° 的，屏幕上的宽高与格子互换，
        // 必须用足迹，否则会留缝（实测踩过 78px 的缝）。
        val track = trackSize()
        val tw = track[0].toFloat()
        val th = track[1].toFloat()

        val edges = PetState.snapEdges(db, dr, leftHalf)
        var nx = x
        var ny = y
        if (edges[0] == 1) ny = b.height() - th
        if (edges[1] == 1) nx = if (leftHalf) 0f else b.width() - tw

        val c = PetState.clampToScreen(nx, ny, tw, th, b.width().toFloat(), b.height().toFloat())
        anchorX = c[0]
        anchorY = c[1]
        dragOffsetX = 0f
        dragOffsetY = 0f

        // 贴完朝向也归位
        targetRotation = PetState.rotationForSnap(state)
        if (abs(shownRotation - targetRotation) < 0.15f) {
            shownRotation = targetRotation
            rotAnimating = false
        } else {
            rotAnimating = true
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        }

        relayout()
        invalidate()
        host?.onSettled(state, currentLeft(), currentTop())
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
        val b = host?.bounds() ?: return
        val cell = cellSize()
        val w = cell[0].toFloat()
        val h = cell[1].toFloat()
        val lc = localCell()
        val cx = lc[0] + w / 2f
        val cy = lc[1] + h / 2f

        // 离本侧吸附边 / 底边的距离，决定模糊强度（用旋转后的屏幕足迹量）
        val leftHalf = PetState.isLeft(state)
        val screenX = anchorX + dragOffsetX
        val screenY = anchorY + dragOffsetY
        val track = trackSize()
        val db = PetState.bottomDist(screenY, track[1].toFloat(), b.height().toFloat())
        val dr = PetState.sideDist(screenX, track[0].toFloat(), b.width().toFloat(), leftHalf)
        val blurT = if (dragging) PetState.blurFor(db, dr, leftHalf) else 0f

        // 点击反馈
        var hopY = 0f
        if (hopping) {
            val t = ((SystemClock.uptimeMillis() - hopStart).toFloat() / 420f).coerceIn(0f, 1f)
            hopY = -h * 0.06f * sin(t * Math.PI).toFloat()
        }

        val save = canvas.save()
        canvas.translate(0f, hopY)
        // 绕格子中心旋转（只改变换，不动格子尺寸 -> 比例不变）
        if (abs(shownRotation) > 0.01f) canvas.rotate(shownRotation, cx, cy)
        // 跨中线的水平翻转：中途压扁再展开
        if (flip > 0.001f && flip < 0.999f) {
            val squash = abs(1f - 2f * flip).coerceAtLeast(0.03f)
            canvas.scale(squash, 1f, cx, cy)
        }

        val bmp = s.bitmap(state, PetState.needsMirror(state))
        src.set(0, 0, bmp.width, bmp.height)
        dst.set(lc[0], lc[1], lc[0] + w, lc[1] + h)

        val pt = if (blurT > 0.001f) {
            blurPaint.alpha = 255
            blurPaint.maskFilter = blurFilterFor(blurT * max(1f, w * 0.14f))
            blurPaint
        } else {
            paint
        }
        canvas.drawBitmap(bmp, src, dst, pt)
        canvas.restoreToCount(save)
    }

    private fun blurFilterFor(radius: Float): BlurMaskFilter? {
        if (radius <= 0.5f) return null
        if (blurRadius >= 0f && abs(blurRadius - radius) < 0.5f) return blurFilter
        blurRadius = radius
        blurFilter = runCatching { BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) }.getOrNull()
        return blurFilter
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val b = bbox()
        setMeasuredDimension(b[0], b[1])
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        relayout()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private fun easeOutCubic(t: Float): Float {
        val u = 1f - t
        return 1f - u * u * u
    }

    /** 息屏时停表省电 */
    private var paused = false

    fun setPaused(p: Boolean) {
        if (paused == p) return
        paused = p
        if (p) handler.removeCallbacks(ticker) else invalidate()
    }

    /** 本类的静态几何工具（服务在 addView 之前也要用，所以放在 public 伴生对象里） */
    companion object Geometry {
        private const val FRAME_MS = 16L
        private const val HIT_ALPHA_MIN = 16
        /**
         * 静态几何计算：给定素材、状态、大小比例，算出初始窗口应当放在哪、多大。
         * 服务在 addView 之前需要这个尺寸（那时 view 还没 attach）。
         *
         * @return [x, y, w, h]
         */
        fun boundsFor(
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
            val box = bboxOf(cell[0], cell[1], rot)
            // 屏幕足迹：转 90/270 时宽高互换
            val q = ((rot % 360f) + 360f) % 360f
            val trackW = if (q == 90f || q == 270f) cell[1] else cell[0]
            val trackH = if (q == 90f || q == 270f) cell[0] else cell[1]

            val x = if (PetState.isLeft(state)) 0 else screen.width() - trackW
            val y = if (PetState.isCorner(state) || PetState.isUprightVariant(state)) {
                screen.height() - trackH
            } else {
                (screen.height() - trackH) / 2
            }
            // 窗口左上角 = 足迹左上角（格子在外接矩形里居中，偏移对称）
            val offX = (box[0] - cell[0]) / 2
            val offY = (box[1] - cell[1]) / 2
            return intArrayOf(x - offX, y - offY, box[0], box[1])
        }

        fun bboxOf(w: Int, h: Int, rotDeg: Float): IntArray {
            val q = ((rotDeg % 360f) + 360f) % 360f
            if (q == 0f || q == 180f) return intArrayOf(w, h)
            if (q == 90f || q == 270f) return intArrayOf(h, w)
            val r = Math.toRadians(rotDeg.toDouble())
            val c = abs(cos(r)).toFloat()
            val sn = abs(sin(r)).toFloat()
            return intArrayOf(
                ceil(w * c + h * sn).toInt().coerceAtLeast(1),
                ceil(w * sn + h * c).toInt().coerceAtLeast(1)
            )
        }
    }
}
