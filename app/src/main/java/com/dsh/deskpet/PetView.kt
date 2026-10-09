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
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 桌宠本体：四种吸附状态 + 拖动过渡动画。
 *
 * 交互规则（用户定义，已用 PetStateTest 钉死）：
 *   - 悬浮在桌面/别的 App 上时，**只有单击和拖动**，没有长按菜单
 *   - 单击 = 开心跳一下（本版先用位移表现，素材到位后再挂 happy 动画）
 *   - 松开 = 吸附回最近的合法状态
 *
 * 朝向规则：
 *   - 非吸附（角落）：头朝上
 *   - 吸附（边框）：头朝屏幕内部
 *
 * 过渡：
 *   - 角落拖出：底边离屏幕底部 >120px 起模糊，200px 时变成边框素材，
 *               中间是"角落素材 + 未旋转边框素材"的叠加，避免生硬
 *   - 跨过屏幕中轴线：水平翻转换边
 */
@SuppressLint("ViewConstructor")
class PetView(context: Context) : View(context) {

    interface Host {
        fun bounds(): Rect
        fun moveTo(x: Int, y: Int)
        fun onSettled(state: Int, leftInset: Int, topInset: Int)
        fun onTapped()
    }

    var host: Host? = null

    // ---------------------------------------------------------------- 状态

    var sprite: PetSprite? = null
        set(value) {
            field = value
            invalidate()
        }

    /** 当前吸附状态（PetState.CORNER_* / BORDER_*） */
    var state: Int = PetState.CORNER_BR
        private set

    /** 拖动时用来判断翻转方向的锚点 x（屏幕坐标） */
    private var anchorX = 0f
    private var anchorY = 0f

    /** 用户设定的大小比例（占屏幕高度），作用在"人物实际高度"上 */
    private var sizeFrac = 0.15f

    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val blurPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val src = Rect()
    private val dst = RectF()

    private var blurFilter: BlurMaskFilter? = null
    private var blurRadius = -1f

    // ---------------------------------------------------------------- 拖动

    private var dragging = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // ---------------------------------------------------------------- 翻转动画

    /** 0 = 未翻转, 1 = 水平镜像；中间值即翻转过程 */
    private var flip = 0f
    private var flipFrom = 0f
    private var flipTo = 0f
    private var flipStart = 0L
    private var flipRunning = false

    /** 翻转动画时长 */
    private val flipDuration = 220L

    // ---------------------------------------------------------------- 点击反馈

    private var hopStart = 0L
    private var hopping = false

    private val ticker = object : Runnable {
        override fun run() {
            var need = false
            val now = android.os.SystemClock.uptimeMillis()

            if (flipRunning) {
                val t = ((now - flipStart).toFloat() / flipDuration).coerceIn(0f, 1f)
                val e = easeOutCubic(t)
                flip = flipFrom + (flipTo - flipFrom) * e
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
            invalidate()
            if (need) handler.postDelayed(this, FRAME_MS)
        }
    }

    // ---------------------------------------------------------------- 尺寸

    /**
     * 当前素材在屏幕上的绘制格子。
     *
     * **故意不按状态做宽高互换**：格子永远是素材的正立比例（等比缩放，不变形），
     * 边框状态贴边时由 [android.graphics.Canvas.rotate] 绕格子中心旋转。
     * 旋转只改变朝向，不改变比例 —— 这是用户明确要求的。
     */
    private fun currentSize(): IntArray {
        val s = sprite ?: return intArrayOf(1, 1)
        val screenH = (host?.bounds()?.height() ?: 1080).toFloat()
        // 滑块作用在「人物实际高度」上；比例由 PetState.scaledSize 保证不变形
        return PetState.scaledSize(
            s.naturalWidth(state),
            s.naturalHeight(state),
            screenH * sizeFrac
        )
    }

    fun setSizeFraction(f: Float) {
        sizeFrac = f.coerceIn(0.05f, 0.6f)
    }

    /** 供服务查询：当前应该占多大 */
    fun measurePet(): IntArray = currentSize()

    // ---------------------------------------------------------------- 吸附定位

    /** 把宠物贴到指定状态的吸附位置 */
    fun applySnap(newState: Int) {
        state = newState
        val b = host?.bounds() ?: return
        val sz = currentSize()
        val w = sz[0]
        val h = sz[1]

        anchorX = if (PetState.isLeft(newState)) 0f else (b.width() - w).toFloat()
        anchorY = if (PetState.isCorner(newState)) {
            (b.height() - h).toFloat()
        } else {
            ((b.height() - h) / 2f)
        }
        // 切状态时确保翻转值与朝向一致，避免残影
        flip = if (PetState.isLeft(newState)) 1f else 0f
        flipFrom = flip
        flipTo = flip
        flipRunning = false

        host?.moveTo(anchorX.roundToInt(), anchorY.roundToInt())
        invalidate()
    }

    /** 从保存的位置恢复（服务启动时用） */
    fun restore(state0: Int, leftInset: Int, topInset: Int) {
        val b = host?.bounds() ?: return
        val sz = currentSize()
        anchorX = leftInset.toFloat().coerceIn(0f, max(0f, (b.width() - sz[0]).toFloat()))
        anchorY = topInset.toFloat().coerceIn(0f, max(0f, (b.height() - sz[1]).toFloat()))
        state = state0
        flip = if (PetState.isLeft(state0)) 1f else 0f
        flipFrom = flip
        flipTo = flip
        host?.moveTo(anchorX.roundToInt(), anchorY.roundToInt())
        invalidate()
    }

    /** 当前左上角坐标（给服务保存用） */
    fun leftInset(): Int = (anchorX + dragOffsetX).roundToInt()
    fun topInset(): Int = (anchorY + dragOffsetY).roundToInt()

    // ---------------------------------------------------------------- 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 窗口是整屏的，只有点在宠物身上才算数，否则让触摸透下去
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
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                }
                if (dragging) {
                    dragOffsetX = dx
                    dragOffsetY = dy
                    followScreenCross()
                    host?.moveTo(
                        (anchorX + dragOffsetX).roundToInt(),
                        (anchorY + dragOffsetY).roundToInt()
                    )
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    settle()
                } else {
                    onTapped()
                }
                dragging = false
                dragOffsetX = 0f
                dragOffsetY = 0f
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 息屏时停表省电 */
    private var paused = false

    fun setPaused(p: Boolean) {
        if (paused == p) return
        paused = p
        if (p) {
            handler.removeCallbacks(ticker)
        } else {
            invalidate()
        }
    }

    /** 点击是否落在宠物身上（窗口是整屏的，必须自己判定） */
    private fun hitsPet(px: Float, py: Float): Boolean {
        val sz = currentSize()
        val x = anchorX + dragOffsetX
        val y = anchorY + dragOffsetY
        return px >= x && px <= x + sz[0] && py >= y && py <= y + sz[1]
    }

    /** 拖动跨过屏幕中轴线 -> 触发水平翻转（素材在 onDraw 里按半屏自动切换） */
    private fun followScreenCross() {
        val b = host?.bounds() ?: return
        val sz = currentSize()
        val x = anchorX + dragOffsetX
        val centerX = x + sz[0] / 2f
        startFlip(if (centerX < b.width() / 2f) 1f else 0f)
    }

    private fun startFlip(target: Float) {
        if (abs(flip - target) < 0.01f && !flipRunning) return
        flipFrom = flip
        flipTo = target
        flipStart = android.os.SystemClock.uptimeMillis()
        flipRunning = true
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    /**
     * 松手：按所在区决定**用哪套素材、转不转**。
     *
     * 用户说明："松手吸附是吸附到松手位置对应的像素位置" ——
     * 所以这里**不移动宠物**，只记下状态。位置就停在松手的地方。
     */
    private fun settle() {
        val b = host?.bounds() ?: return
        val sz = currentSize()
        val x = anchorX + dragOffsetX
        val y = anchorY + dragOffsetY

        val db = (b.height() - (y + sz[1])).coerceAtLeast(0f)
        val dr = (b.width() - (x + sz[0])).coerceAtLeast(0f)
        val leftHalf = (x + sz[0] / 2f) < b.width() / 2f

        state = PetState.snapTarget(db, dr, leftHalf)

        // 保留松手位置：把拖动偏移固化成锚点，不做任何吸附对齐
        anchorX = x
        anchorY = y
        dragOffsetX = 0f
        dragOffsetY = 0f

        host?.onSettled(state, leftInset(), topInset())
        invalidate()
    }

    private fun onTapped() {
        hopping = true
        hopStart = android.os.SystemClock.uptimeMillis()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        host?.onTapped()
    }

    // ---------------------------------------------------------------- 绘制

    override fun onDraw(canvas: Canvas) {
        val s = sprite ?: return
        val b = host?.bounds() ?: return
        val sz = currentSize()
        val w = sz[0]
        val h = sz[1]

        val x = anchorX + dragOffsetX
        val y = anchorY + dragOffsetY

        // 判定基准：桌宠的**底边**离屏幕底部、**右边**离屏幕右侧（左半屏镜像）
        val db = (b.height() - (y + h)).coerceAtLeast(0f)
        val dr = (b.width() - (x + w)).coerceAtLeast(0f)
        val leftHalf = (x + w / 2f) < b.width() / 2f

        // 拖动中用哪套素材 / 转多少度，直接由所在区决定
        val artState = if (dragging) {
            PetState.artStateFor(db, dr, leftHalf)
        } else {
            state
        }
        val rotation = if (dragging) {
            PetState.dragRotationFor(db, dr, leftHalf)
        } else {
            PetState.rotationForSnap(state)
        }

        // 模糊：D 区（那条 L 形带）内才有，拖动时生效
        val blurT = if (dragging) PetState.blurFor(db, dr) else 0f

        // 角落素材 <-> 边框素材 的切换进度
        val morph = if (dragging) PetState.morphFor(db, dr) else 0f

        // 点击反馈：轻轻跳一下
        var hopY = 0f
        if (hopping) {
            val t = ((android.os.SystemClock.uptimeMillis() - hopStart).toFloat() / 420f)
                .coerceIn(0f, 1f)
            hopY = -h * 0.06f * kotlin.math.sin(t * Math.PI).toFloat()
        }

        val save = canvas.save()
        // 跨屏幕中线时的水平翻转动画：中途压成一条线再展开，做出"翻面"观感
        if (flip > 0.001f && flip < 0.999f) {
            val cx = x + w / 2f
            val squash = abs(1f - 2f * flip).coerceAtLeast(0.03f)
            canvas.scale(squash, 1f, cx, y + h / 2f)
        }

        val drawY = y + hopY
        // 镜像只由左右半屏决定
        val mirrored = leftHalf

        if (morph <= 0.001f) {
            drawOne(canvas, s, artState, w, h, x, drawY, mirrored, blurT, rotation)
        } else {
            // 过渡：角落素材淡出 + 边框素材淡入（叠加模糊，避免生硬）
            drawBlend(canvas, s, w, h, x, drawY, mirrored, morph, blurT, rotation)
        }
        canvas.restoreToCount(save)
    }

    private fun drawOne(
        canvas: Canvas,
        s: PetSprite,
        st: Int,
        w: Int, h: Int,
        x: Float, y: Float,
        mirrored: Boolean,
        blurT: Float,
        rotation: Float
    ) {
        val bmp: Bitmap = s.bitmap(st, mirrored)
        src.set(0, 0, bmp.width, bmp.height)

        val paintToUse = if (blurT > 0.001f) {
            blurPaint.alpha = 255
            blurPaint.maskFilter = blurFilterFor(blurT * max(1f, w * 0.14f))
            blurPaint
        } else {
            paint
        }
        if (rotation != 0f) {
            // 绕格子中心旋转；贴边时格子正好是 90° 后的形状
            val save = canvas.save()
            canvas.rotate(rotation, x + w / 2f, y + h / 2f)
            dst.set(x, y, x + w, y + h)
            canvas.drawBitmap(bmp, src, dst, paintToUse)
            canvas.restoreToCount(save)
        } else {
            dst.set(x, y, x + w, y + h)
            canvas.drawBitmap(bmp, src, dst, paintToUse)
        }
    }

    private fun drawBlend(
        canvas: Canvas,
        s: PetSprite,
        w: Int, h: Int,
        x: Float, y: Float,
        mirrored: Boolean,
        morph: Float,
        blurT: Float,
        rotation: Float
    ) {
        val m = morph.coerceIn(0f, 1f)
        val radius = blurFilterFor(blurT * max(1f, w * 0.14f))
        val left = mirrored

        // 旋转时的格子：边框素材旋转 90° 后宽高互换
        val qw = if (rotation != 0f) h.toFloat() else w.toFloat()
        val qh = if (rotation != 0f) w.toFloat() else h.toFloat()

        // ---- 下层：角落素材（始终正立），随进度淡出 ----
        val cornerBmp = s.bitmap(if (left) PetState.CORNER_BL else PetState.CORNER_BR, left)
        src.set(0, 0, cornerBmp.width, cornerBmp.height)
        dst.set(x, y, x + w, y + h)
        paint.alpha = ((1f - m) * 255f).roundToInt().coerceIn(0, 255)
        paint.maskFilter = radius
        canvas.drawBitmap(cornerBmp, src, dst, paint)

        // ---- 上层：边框素材，随进度淡入 ----
        val borderBmp = s.bitmap(if (left) PetState.BORDER_L else PetState.BORDER_R, left)
        src.set(0, 0, borderBmp.width, borderBmp.height)
        paint.alpha = (m * 255f).roundToInt().coerceIn(0, 255)
        paint.maskFilter = radius
        if (rotation != 0f) {
            val save = canvas.save()
            canvas.rotate(rotation, x + qw / 2f, y + qh / 2f)
            dst.set(x, y, x + qw, y + qh)
            canvas.drawBitmap(borderBmp, src, dst, paint)
            canvas.restoreToCount(save)
        } else {
            dst.set(x, y, x + w, y + h)
            canvas.drawBitmap(borderBmp, src, dst, paint)
        }

        paint.maskFilter = null
        paint.alpha = 255
    }

    private fun blurFilterFor(radius: Float): BlurMaskFilter? {
        if (radius <= 0.5f) return null
        if (blurRadius >= 0f && abs(blurRadius - radius) < 0.5f) return blurFilter
        blurRadius = radius
        blurFilter = try {
            BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        } catch (e: Exception) {
            null
        }
        return blurFilter
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val b = host?.bounds()
        setMeasuredDimension(
            b?.width() ?: suggestedMinimumWidth,
            b?.height() ?: suggestedMinimumHeight
        )
    }

    private fun easeOutCubic(t: Float): Float {
        val u = 1f - t
        return 1f - u * u * u
    }

    private companion object {
        const val FRAME_MS = 16L
    }
}
