package com.dsh.deskpet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.Choreographer
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
            clearScaledCache()
            cacheSizes()
            cancelTween()
            invalidate()
        }

    /** 当前状态（决定素材与旋转） */
    var state: Int = PetState.CORNER_BR
        private set

    private var sizeFrac = Prefs.DEFAULT_SIZE

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

    /** 素材格子在足迹盒内的偏移（让格子居中于足迹盒） */
    private var cellOffX = 0
    private var cellOffY = 0

    /**
     * 轻跳的顶部余量：按素材高度**等比**给，不能用固定值 ——
     * 宠物放大后 8% 的跳幅远超固定的 48px，会被窗口切顶。
     */
    private fun topPad(): Int =
        (cellH * HOP_FRACTION).toInt() + HOP_PAD_MIN

    /** 足迹盒在"旋转包络窗口"内的偏移 */
    private var footOffX = 0
    private var footOffY = 0

    /**
     * **素材中心点**在位图里的相对位置（0..1）。
     *
     * 按 alpha 外接框算，而不是简单取一半 —— 格子是矩形，人物不一定居中，
     * 只有按真实像素范围取中心，旋转时才是"绕人物自己转"。
     */
    private var artU = 0.5f
    private var artV = 0.5f

    /** 素材中心点缓存：state -> [artU, artV]，避免每次 cacheSizes 重扫位图 */
    private val artCenterCache = HashMap<Int, FloatArray>()

    /**
     * 预缩放位图缓存：把素材**一次性缩放到目标格子尺寸**，
     * 之后每帧就是 1:1 贴图，不再做缩放采样。
     *
     * 之前每帧都把 1024x875（或 1440x1352）的源图缩放绘制到 ~541x463，
     * 缩放采样是每帧最重的一项开销。
     */
    private val scaledCache = HashMap<String, Bitmap>()

    /**
     * 旋转动画期间是否"冻结"尺寸。
     *
     * 冻结时 [sizeRotation] 返回 [frozenRot]（旧朝向），
     * 于是格子宽高与窗口尺寸在整段动画里不变，只有画布在转。
     * 这样素材中心点的屏幕位置才不会因为尺寸互换而平移一截。
     */
    private var frozen = false
    private var frozenRot = 0f

    /** 当前足迹尺寸所对应的旋转角（未冻结时随状态更新） */
    private var curSizeRot = 0f

    /** 绕手指点旋转：拖动期间用它当支点（窗口内坐标） */
    private var pivotValid = false
    private var pivotLocalX = 0f
    private var pivotLocalY = 0f

    private fun cacheSizes() {
        val s = sprite ?: return
        val screenH = (host?.bounds()?.height() ?: 1080).toFloat()
        val cell = PetState.scaledSize(
            s.naturalWidth(state), s.naturalHeight(state), screenH * sizeFrac
        )
        cellW = cell[0]
        cellH = cell[1]

        val rot = sizeRotation()
        val q = ((rot % 360f) + 360f) % 360f
        val rotated = q == 90f || q == 270f
        trackW = if (rotated) cellH else cellW
        trackH = if (rotated) cellW else cellH

        // 旋转包络：**保证任何角度都不裁切**。
        //
        // 关键：W×H 矩形绕中心转 θ 时外接框是 (W|cosθ|+H|sinθ|)，45° 附近
        // 约等于**对角线**，比 max(W,H) 大得多 —— 用 max 会在中间角度切掉。
        // 另外点击压缩会把横向撑大 (1+SQ_MAX) 倍，也要一起容纳。
        val diag = kotlin.math.hypot(cellW.toDouble(), cellH.toDouble()).toFloat()
        val envW = maxOf(diag, cellW * (1f + SQ_MAX))
        val envH = maxOf(diag, cellH * (1f + SQ_MAX))
        bboxW = envW.toInt() + HOP_PAD_X * 2
        bboxH = envH.toInt() + topPad() + HOP_PAD_BOTTOM
        boxOffX = HOP_PAD_X
        boxOffY = topPad()

        // 格子在窗口里居中（旋转包络的中心）；绕偏心支点转时靠包络的
        // 富余量容纳，所以这里居中即可。
        cellOffX = (bboxW - HOP_PAD_X * 2 - cellW) / 2
        cellOffY = (bboxH - topPad() - HOP_PAD_BOTTOM - cellH) / 2

        // 足迹盒在包络窗口里的偏移 —— 贴边/夹取算的是足迹盒的边，
        // 不是包络窗口的边，所以换算时必须把这个偏移减掉。
        footOffX = (bboxW - HOP_PAD_X * 2 - trackW) / 2
        footOffY = (bboxH - topPad() - HOP_PAD_BOTTOM - trackH) / 2

        // 素材中心点：按 alpha 外接框算，结果**按状态缓存**。
        // alphaBounds 要整张位图逐行扫描，而 cacheSizes 在状态切换时会被反复调用，
        // 每次重扫就是掉帧的一个来源。
        var u = 0.5f
        var v = 0.5f
        artCenterCache[state]?.let {
            u = it[0]
            v = it[1]
        } ?: run {
            val bmp = runCatching { bitmapFor(state) }.getOrNull()
            if (bmp != null) {
                val b = alphaBounds(bmp)
                if (b != null && b[2] > b[0] && b[3] > b[1]) {
                    u = (b[0] + b[2]) / 2f / bmp.width
                    v = (b[1] + b[3]) / 2f / bmp.height
                }
            }
            artCenterCache[state] = floatArrayOf(u, v)
        }
        artU = u
        artV = v

        // 未冻结时记录"当前足迹对应的朝向"，供下一次冻结使用
        if (!frozen) curSizeRot = PetState.rotationForSnap(state)
    }

    /**
     * 逐像素扫出 alpha 外接框 [l, t, r, b]。
     *
     * Bitmap 没有现成的 alpha bounds API，所以自己扫；
     * 带步长抽样（每 3px 取一个点）足够定中心，且只在状态切换时算一次。
     */
    private fun alphaBounds(bmp: Bitmap): IntArray? {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return null
        var l = w
        var t = h
        var r = -1
        var b = -1
        val step = 3
        val row = IntArray(w)
        var y = 0
        while (y < h) {
            runCatching { bmp.getPixels(row, 0, w, 0, y, w, 1) }.getOrNull() ?: break
            var x = 0
            while (x < w) {
                if (((row[x] ushr 24) and 0xFF) >= HIT_ALPHA_MIN) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > b) b = y
                }
                x += step
            }
            y += step
        }
        return if (r < 0) null else intArrayOf(l, t, r, b)
    }

    /** 素材格子在窗口内的左上角（含轻跳余量） */
    private fun cellLocalX(): Float = (boxOffX + cellOffX).toFloat()
    private fun cellLocalY(): Float = (boxOffY + cellOffY).toFloat()

    /** 素材中心点在窗口内的偏移（相对窗口左上角） */
    private fun centerInWindowX(): Float = cellLocalX() + artU * cellW
    private fun centerInWindowY(): Float = cellLocalY() + artV * cellH

    /**
     * 旋转与压缩的**支点**（窗口内坐标）。
     *
     * 用户在拖动时希望**绕手指实际作用点**旋转，所以拖动期间用
     * [pivotLocalX]/[pivotLocalY]（按下点固定在窗口里的位置）；
     * 松手后没有手指了，回退到素材中心点。
     *
     * ⚠️ 支点在整段旋转动画期间必须保持不变，否则一动就抽搐。
     */
    /**
     * 旋转与压缩的**支点**（窗口内坐标）= 素材中心点。
     *
     * ⚠️ 为什么不用"手指实际作用点"当支点：
     * 用户要求**无论如何不准裁切素材**。绕中心转时，素材离中心最远就是
     * 半对角线，所以"边长=对角线"的方形窗口恰好容得下任意角度；
     * 而绕偏心点（尤其按在角上）转时，从窗口中心算最远可达 1.5×对角线，
     * 窗口要开到约 3×对角线（本机 ≈2100px，几乎满屏宽），
     * 那会重新引入"整屏窗口挡住触摸"的老问题。
     * 两者不可兼得，这里服从"不裁切"。
     */
    private fun pivotX(): Float = centerInWindowX()
    private fun pivotY(): Float = centerInWindowY()

    /** 素材中心点相对"足迹盒左上角"的偏移 —— 换算贴边坐标用 */
    private fun centerFromTrackX(): Float =
        centerInWindowX() - boxOffX - footOffX

    private fun centerFromTrackY(): Float =
        centerInWindowY() - boxOffY - footOffY

    /**
     * 指定**足迹宽度**时，素材中心点相对足迹左上角的偏移。
     *
     * 松手吸附要按"目标状态的足迹"算位置（例如从正立切到旋转，
     * 足迹宽高会互换），这时不能再用当前缓存的 [centerFromTrackX]。
     */
    private fun centerFromTrackXSized(trackWidth: Int): Float =
        (trackWidth - cellW) / 2f + artU * cellW

    private fun centerFromTrackYSized(trackHeight: Int): Float =
        (trackHeight - cellH) / 2f + artV * cellH

    /**
     * 尺寸变化后，**保持素材中心点的屏幕位置不变**。
     *
     * 拖动过程中状态切换会让足迹尺寸互换（如 541x463 -> 463x541），
     * 若不补偿，中心点会横向平移约 39px —— 那就是"抽一下"。
     */
    private fun cacheSizesKeepingCenter() {
        val oldFromTrackX = centerFromTrackX()
        val oldFromTrackY = centerFromTrackY()
        val cx = anchorX + oldFromTrackX
        val cy = anchorY + oldFromTrackY
        cacheSizes()
        anchorX = cx - centerFromTrackX()
        anchorY = cy - centerFromTrackY()
    }

    fun setSizeFraction(f: Float) {
        sizeFrac = f.coerceIn(0.05f, 0.9f)
        // 缩放前先记下**吸附边的屏幕位置**
        val oldLeft = curTrackLeft()
        val oldTop = curTrackTop()
        val oldRight = oldLeft + trackW
        val oldBottom = oldTop + trackH
        val oldCenterY = curCenterY()

        cacheSizes()

        // 以**吸附边**为基准缩放：
        //   贴底 / 角落 -> 底边不动（否则缩小后会浮空）
        //   贴侧边      -> 吸附的那一侧不动（否则放大后会超出屏幕）
        val b = host?.bounds()
        if (b != null) {
            // 横向：贴左则左边不动，贴右则右边不动
            anchorX = if (PetState.isLeft(state)) {
                oldLeft + centerFromTrackX()
            } else {
                oldRight - trackW + centerFromTrackX()
            }
            // 纵向：贴底 / 角落 -> 底边不动；侧边形态垂直居中 -> 中心不动
            anchorY = if (state == PetState.BORDER_R || state == PetState.BORDER_L) {
                oldCenterY
            } else {
                oldBottom - trackH + centerFromTrackY()
            }
        }
        dragOffsetX = 0f
        dragOffsetY = 0f
        cancelTween()
        syncWindow()
    }

    fun setState(newState: Int) {
        if (state == newState) return
        val prevRot = curSizeRot
        val newRot = PetState.rotationForSnap(newState)
        val willRotate = abs(prevRot - newRot) > 0.01f
        if (willRotate) {
            frozenRot = prevRot
            frozen = true
        } else {
            frozen = false
            rotAnimRunning = false
        }
        state = newState
        cacheSizes()
        if (willRotate) startRotAnim(shownRotation, newRot)
        placeWindow()
        invalidate()
    }

    // ---------------------------------------------------------------- 动画

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 动画帧用 **Choreographer（vsync 对齐）**，不用 postDelayed(16)。
     *
     * postDelayed 有两个毛病：不跟 vsync 同步，容易产生"一帧画两次 / 漏一帧"；
     * 而且延迟按固定值走，和屏幕刷新率对不齐就会周期性卡顿。
     */
    private val choreographer = Choreographer.getInstance()
    private val frameCallback = Choreographer.FrameCallback { ticker.run() }

    private fun scheduleFrame() {
        choreographer.removeFrameCallback(frameCallback)
        choreographer.postFrameCallback(frameCallback)
    }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val src = Rect()
    private val dst = RectF()

    private var hopping = false
    private var hopStart = 0L

    /**
     * 点击反馈：**先压扁再弹起**，而且压缩方向始终与人物头朝向一致。
     *
     *   贴底边 / 角落（头朝上）-> 先上下压扁，再向上弹起
     *   贴右边框（头朝左）     -> 先左右压扁，再向左弹起
     *   贴左边框（头朝右）     -> 先左右压扁，再向右弹起
     *
     * 实现：在"素材自己的坐标系"里做 —— 先转到人物朝向，再沿头轴压缩，
     * 这样不管转到多少度，压的方向都跟着头走。
     */
    private fun hopPhase(): Float {
        if (!hopping) return -1f
        return ((SystemClock.uptimeMillis() - hopStart).toFloat() / HOP_MS).coerceIn(0f, 1f)
    }

    /** 压缩量：前 30% 压到最扁，之后回弹 */
    private fun squashAt(t: Float): Float =
        if (t < 0.3f) SQ_MAX * (t / 0.3f) else SQ_MAX * (1f - (t - 0.3f) / 0.7f)

    /** 弹起位移：压扁结束后才起跳，正弦半波 */
    private fun jumpAt(t: Float): Float {
        if (t < 0.3f) return 0f
        val u = (t - 0.3f) / 0.7f
        return cellH * HOP_FRACTION * sin(u * Math.PI).toFloat()
    }

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

    // ---- 旋转动画（0 <-> ∓90 之间平滑过渡）----
    /** 当前显示用的角度（动画过程中是中间值） */
    private var shownRotation = 0f
    private var rotAnimFrom = 0f
    private var rotAnimTo = 0f
    private var rotAnimStart = 0L

    /** 本次旋转动画时长（拖动中用 ROT_MS，松手贴边前用更快的 ROT_SNAP_MS） */
    private var rotAnimMs = ROT_MS
    private var rotAnimRunning = false

    /** 松手后待生效的状态（补间结束才应用） */
    private var pendingState: Int? = null

    /** 上一次的区号，用于 B/E 分界的滞回判定 */
    private var lastZone = PetState.ZONE_E

    @Suppress("unused")
    private var pendingLeft = false

    private var dragging = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
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
                    // 补间走完才换状态，避免中途改尺寸导致的跳变
                    applyPendingState()
                }
                need = true
            }
            if (rotAnimRunning) {
                val t = ((now - rotAnimStart).toFloat() / rotAnimMs).coerceIn(0f, 1f)
                shownRotation = rotAnimFrom + (rotAnimTo - rotAnimFrom) * easeOutCubic(t)
                if (t >= 1f) {
                    shownRotation = rotAnimTo
                    rotAnimRunning = false
                    // 转完了才解锁尺寸，并保持中心点不动
                    onRotationDone()
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
            // 注：镜像翻转现在是"越过中线立即完成"，flipRunning 不再被置 true；
            // 这段保留是为了以后想恢复过渡动画时可以直接启用。
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
            if (need) scheduleFrame()
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
        choreographer.removeFrameCallback(frameCallback)
        scheduleFrame()
    }

    private fun startTween(toX: Float, toY: Float) = animateTo(toX, toY)

    /**
     * 旋转动画：在 0 与 ∓90 之间平滑过渡。
     *
     * @param durationMs 时长。拖动中切换用默认 180ms（避免分界线附近闪）；
     *                   松手贴边前的那次旋转用 [ROT_SNAP_MS]（更快，因为要在贴边前完成）
     */
    private fun startRotAnim(from: Float, to: Float, durationMs: Float = ROT_MS) {
        if (abs(from - to) < 0.01f) {
            shownRotation = to
            rotAnimRunning = false
            // 没真正开始转，必须立刻解冻 —— 否则尺寸会被永久锁在旧朝向
            onRotationDone()
            return
        }
        rotAnimFrom = from
        rotAnimTo = to
        rotAnimMs = durationMs
        rotAnimStart = SystemClock.uptimeMillis()
        rotAnimRunning = true
        choreographer.removeFrameCallback(frameCallback)
        scheduleFrame()
    }

    /**
     * 拖动中切换状态：**旋转走动画**，避免在 B/E 分界线附近来回闪。
     *
     * ⚠️ 顺序很重要：**先冻结尺寸（记住旧朝向），再换状态**。
     *
     * 否则换状态那一下足迹尺寸立刻互换，素材中心点会平移一截，
     * 看起来就是"旋转时抽一下"。冻结之后整段动画里只有画布在转，
     * 尺寸与窗口都保持不变，中心点也就不动。
     */
    private fun setDragState(newState: Int) {
        if (state == newState) return
        val prevRot = curSizeRot
        val prevShown = shownRotation
        val newRot = PetState.rotationForSnap(newState)
        val willRotate = abs(prevRot - newRot) > 0.01f

        if (!willRotate) {
            // 又拖回不需要旋转的形态：取消进行中的旋转动画，直接归位
            if (frozen || rotAnimRunning) {
                rotAnimRunning = false
                frozen = false
                shownRotation = newRot
            }
            state = newState
            cacheSizesKeepingCenter()
            placeWindow()
            return
        }

        // 需要旋转：冻结在旧朝向，整段动画期间尺寸不变
        if (!frozen) {
            frozenRot = prevRot
            frozen = true
        }
        state = newState
        cacheSizes()
        startRotAnim(prevShown, newRot)
        placeWindow()
    }

    /**
     * 旋转动画结束：解除冻结并把尺寸切到新朝向。
     *
     * 切的时候**保持素材中心点不动**，所以即使尺寸互换，
     * 画面也不会横移 —— 这正是用户要的"抓着中心点"手感。
     *
     * 若此时还在拖动，就只补偿中心；若是松手后的吸附，
     * 则用新尺寸重算贴边位置并补间过去，最后精确贴边。
     */
    private fun onRotationDone() {
        if (!frozen) return
        frozen = false
        // 无论是拖动中还是松手后，解冻时都**保持素材中心点不动**，
        // 尺寸切换就不会让画面横移。位置补间（若在跑）继续朝目标走。
        cacheSizesKeepingCenter()
        placeWindow()
    }
    /** 旋转动画期间，用于绘制的实际角度 */
    private fun displayRotation(): Float =
        if (rotAnimRunning) shownRotation else PetState.rotationForSnap(state)

    /**
     * 用于**计算格子与窗口尺寸**的旋转角。
     *
     * 旋转动画期间要"定格"在起点角度 —— 这样格子宽高与窗口尺寸
     * 在整段动画里保持不变，只有画布在转。
     *
     * 否则一旦开始转，尺寸立刻互换，**素材中心点会跟着平移一截**，
     * 看起来就是抽一下（用户反馈的"旋转时抽搐"就是这个）。
     */
    private fun sizeRotation(): Float = if (frozen) frozenRot else PetState.rotationForSnap(state)

    /**
     * 跨半屏的镜像翻转：**快速小动画**。
     *
     * 用户要求：素材中心点一越过屏幕中线就翻，用一个简单快速的小动画完成。
     * 所以这里用很短的时长（[FLIP_MS]），做得干脆，不要拖沓。
     */
    private fun startFlip(target: Float) {
        if (abs(flip - target) < 0.01f && !flipRunning) return
        flipFrom = flip
        flipTo = target
        flipStart = SystemClock.uptimeMillis()
        flipRunning = true
        choreographer.removeFrameCallback(frameCallback)
        scheduleFrame()
    }

    // ---------------------------------------------------------------- 位置
    //
    // anchorX/anchorY 是**素材中心点**的屏幕坐标（不是格子左上角）。
    // 以中心点为基准的好处：状态切换导致足迹尺寸互换时，
    // 只要中心点不动，画面就不会横移（用户要的"抓着中心点拖"）。

    /** 素材中心点的当前屏幕坐标（含拖动位移） */
    private fun curCenterX(): Float = anchorX + dragOffsetX
    private fun curCenterY(): Float = anchorY + dragOffsetY

    /** 足迹盒左上角的当前屏幕坐标（贴边/夹取计算用） */
    private fun curTrackLeft(): Float = curCenterX() - centerFromTrackX()
    private fun curTrackTop(): Float = curCenterY() - centerFromTrackY()

    /** 当前显示位置（屏幕坐标，足迹盒左上角） */
    fun currentLeft(): Int = curTrackLeft().roundToInt()
    fun currentTop(): Int = curTrackTop().roundToInt()

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
        // 以**素材中心点**反推窗口位置：中心点定点不动，尺寸变了也不会横移
        val x = (curCenterX() - centerInWindowX()).roundToInt()
        val y = (curCenterY() - centerInWindowY()).roundToInt()
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
        // 先算足迹盒左上角，再换算成中心点（anchor 存的是中心点）
        val trackLeft = if (PetState.isLeft(newState)) 0f else (b.width() - trackW).toFloat()
        val trackTop = if (PetState.isZoneless(newState)) ((b.height() - trackH) / 2f)
        else (b.height() - trackH).toFloat()
        anchorX = trackLeft + centerFromTrackX()
        anchorY = trackTop + centerFromTrackY()
        dragOffsetX = 0f
        dragOffsetY = 0f
        cancelTween()
        syncWindow()
    }

    /** 从保存的位置恢复（left/top 是**足迹盒**左上角，与持久化格式一致） */
    fun restore(state0: Int, left: Int, top: Int) {
        state = state0
        cacheSizes()
        anchorX = left + centerFromTrackX()
        anchorY = top + centerFromTrackY()
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
        // 与 onDraw 一致：绕**同一个支点**旋转
        val cx = pivotX()
        val cy = pivotY()
        var dx = localX - cx
        var dy = localY - cy

        // 反掉点击动画的压扁与弹起（与 onDraw 里的变换严格对应）
        val t = hopPhase()
        if (t >= 0f) {
            val sq = squashAt(t)
            val headAlongY = abs(displayRotation()) < 45f
            val sx = if (headAlongY) 1f + sq else 1f - sq
            val sy = if (headAlongY) 1f - sq else 1f + sq
            dy /= sy
            dx /= sx
            val push = jumpAt(t)
            val rot = displayRotation()
            if (headAlongY) {
                dy += push
            } else {
                dx += push * (if (rot > 0f) 1f else -1f)
            }
        }

        // 反掉翻转动画的水平压缩
        if (flip > 0.001f && flip < 0.999f) {
            val squash = abs(1f - 2f * flip).coerceAtLeast(0.03f)
            dx /= squash
        }

        // 命中判定用**显示角度**，和实际画出来的朝向保持一致
        val rot = displayRotation()
        if (abs(rot) > 0.01f) {
            val rad = Math.toRadians(-rot.toDouble())
            val c = cos(rad).toFloat()
            val sn = sin(rad).toFloat()
            val rx = dx * c - dy * sn
            val ry = dx * sn + dy * c
            dx = rx
            dy = ry
        }

        // dx/dy 是相对**支点**的，换算回格子内坐标
        val cu = dx + (cx - cellLocalX())
        val cv = dy + (cy - cellLocalY())
        val u = cu / cellW
        val v = cv / cellH
        if (u < 0f || u > 1f || v < 0f || v > 1f) return false

        // 用预缩放位图做 alpha 取样：比全分辨率图小得多，getPixel 更快
        val bmp = runCatching { scaledBitmap(state) }.getOrNull() ?: return true
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

    /**
     * 取**已缩放到格子尺寸**的位图（每帧 1:1 贴图用）。
     *
     * 注意这里用**未旋转**的格子尺寸 —— 旋转是画布变换，不改变格子宽高，
     * 所以比例永远不变。
     */
    private fun scaledBitmap(st: Int): Bitmap? {
        val s = sprite ?: return null
        val key = st.toString() + "x" + cellW + "x" + cellH
        scaledCache[key]?.let { if (!it.isRecycled) return it }
        val src = bitmapFor(st) ?: return null
        val out = runCatching {
            Bitmap.createScaledBitmap(src, cellW.coerceAtLeast(1), cellH.coerceAtLeast(1), true)
        }.getOrNull() ?: return src
        // 尺寸切换不频繁，缓存不会无限增长；超了就先清掉重来
        if (scaledCache.size > 12) {
            for (b in scaledCache.values) if (!b.isRecycled && b !== out) b.recycle()
            scaledCache.clear()
        }
        scaledCache[key] = out
        return out
    }

    private fun clearScaledCache() {
        for (b in scaledCache.values) if (!b.isRecycled) b.recycle()
        scaledCache.clear()
        artCenterCache.clear()
    }

    private fun hopOffset(): Float {
        val t = hopPhase()
        return if (t < 0f) 0f else -jumpAt(t)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!hitsPet(event.x, event.y)) return false
                downRawX = event.rawX
                downRawY = event.rawY
                dragOffsetX = 0f
                dragOffsetY = 0f
                dragging = false
                cancelTween()
                // 记下手指作用点在窗口内的位置 —— 旋转就绕它转
                pivotLocalX = event.x
                pivotLocalY = event.y
                pivotValid = true
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                    // 用户规则：**手指按在哪里，素材中心点就在哪里**。
                    // 不保留"手指到中心点的偏移"，直接在起拖那一刻把中心点对齐到按下位置。
                    anchorX = downRawX
                    anchorY = downRawY
                    dragOffsetX = 0f
                    dragOffsetY = 0f
                }
                if (dragging) {
                    // 用户规则：**手指按在哪里，素材中心点就在哪里**
                    anchorX = event.rawX
                    anchorY = event.rawY
                    dragOffsetX = 0f
                    dragOffsetY = 0f
                    updateDragState()
                    placeWindow()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    // 固化当前位置，并清零位移 —— 否则 settle 里位移会被算两遍（瞬移）
                    anchorX = curCenterX()
                    anchorY = curCenterY()
                    dragOffsetX = 0f
                    dragOffsetY = 0f
                    pivotValid = false
                    settle()
                } else {
                    pivotValid = false
                    onTapped()
                }
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 拖动过程中刷新状态（素材与旋转随之变化，没有中间角度） */
    private fun updateDragState() {
        val b = host?.bounds() ?: return
        val x = curTrackLeft()
        val y = curTrackTop()
        // 中线判定用**素材中心点**：中心一越过屏幕中线就翻（带动画）
        val leftHalf = curCenterX() < b.width() / 2f

        // 跨半屏 -> 触发镜像翻转动画
        startFlip(if (leftHalf) 1f else 0f)

        val db = PetState.bottomDist(y, trackH.toFloat(), b.height().toFloat())
        val dr = PetState.sideDist(x, trackW.toFloat(), b.width().toFloat(), leftHalf)
        // 旋转判定看**素材中心点**有没有越过 B/E 分界线
        val cd = PetState.centerSideDist(
            curCenterX(), b.width().toFloat(), leftHalf
        )

        // 带滞回的分区判定。门槛 = aW + 半宽：宠物完全贴边时中心点离边正好是
        // 半宽，必须把半宽算进门槛，"贴边静止"才稳定落在 B 内
        // （否则会在旋转/正立之间横跳，而过中线判定又依赖 trackW，跟着一起抖）。
        val zone = PetState.zoneWithHysteresis(
            db, dr, leftHalf, lastZone, cd,
            PetState.currentAW + trackW / 2f
        )
        lastZone = zone

        // B 区即"中心点已越界" -> 旋转形态；E 区保持正立
        val st = PetState.stateForZone(zone, leftHalf)
        setDragState(st)
    }

    /** 松手吸附：贴边 + 平滑补间过去（不瞬移） */
    /**
     * 松手吸附。
     *
     * ⚠️ 关键：**先把位置补间过去，到终点再切换状态**。
     *
     * 之前是"先切状态再补间"，而切状态会立刻改格子尺寸（角落素材与正立素材
     * 宽高不同）并触发窗口 resize —— 于是补间的起点坐标含义突然变了，
     * 看起来就是一卡一卡地跳。现在补间期间保持旧状态与旧尺寸，
     * 到达终点后再换状态，位置不跳。
     */
    private fun settle() {
        settleInternal()
    }

    /**
     * 松手吸附的实际计算。
     *
     * anchorX/anchorY 是**素材中心点**，而贴边/夹取是按**足迹盒**算的，
     * 所以这里统一先换算成足迹盒左上角，算完再换回中心点。
     */
    private fun settleInternal() {
        val b = host?.bounds() ?: return
        // 先把拖动位移**固化进 anchor 并清零**。
        // 否则补间过程中 anchorX 在变、dragOffsetX 还在被加上去，
        // 位移被算两遍 —— 那就是"一松手就瞬移"。
        anchorX = curCenterX()
        anchorY = curCenterY()
        dragOffsetX = 0f
        dragOffsetY = 0f

        val x = curTrackLeft()
        val y = curTrackTop()
        // 中线判定用**素材中心点**：中心一越线就翻
        val leftHalf = curCenterX() < b.width() / 2f
        val db = PetState.bottomDist(y, trackH.toFloat(), b.height().toFloat())
        val dr = PetState.sideDist(x, trackW.toFloat(), b.width().toFloat(), leftHalf)

        // 与拖动时保持一致：中心点判据，门槛含半宽
        val cd = PetState.centerSideDist(curCenterX(), b.width().toFloat(), leftHalf)
        val zone = PetState.zoneWithHysteresis(
            db, dr, leftHalf, lastZone, cd,
            PetState.currentAW + trackW / 2f
        )
        lastZone = zone

        // ---- 朝向由"最终贴到哪条边"决定，而不是由拖动时的区号决定 ----
        // 用户要求：即使拖的时候素材没转，只要松手后贴到侧边，就要自动旋转 + 贴边。
        val edges = if (zone == PetState.ZONE_E) {
            if (PetState.nearerEdgeIsBottom(db, dr)) intArrayOf(1, 0) else intArrayOf(0, 1)
        } else {
            PetState.snapEdgesForZone(zone)
        }
        val snapBottom = edges[0] == 1
        val snapSide = edges[1] == 1
        val want = when {
            // 角落：两条边都贴 -> 用角落素材
            snapBottom && snapSide ->
                if (leftHalf) PetState.CORNER_BL else PetState.CORNER_BR
            // 贴侧边 -> 一定旋转躺倒
            snapSide -> if (leftHalf) PetState.BORDER_L else PetState.BORDER_R
            // 只贴底边 -> 正立
            else -> if (leftHalf) PetState.BORDER_L_UPRIGHT else PetState.BORDER_R_UPRIGHT
        }

        // 目标位置必须用 **want 的足迹** 算，否则换成旋转形态后会不贴边
        val wantRot = PetState.rotationForSnap(want)
        val wantRotated = abs(abs(wantRot) - 90f) < 1f
        val tW = if (wantRotated) cellH else cellW
        val tH = if (wantRotated) cellW else cellH

        var nx = x
        var ny = y
        if (snapBottom) ny = (b.height() - tH).toFloat()
        if (snapSide) nx = if (leftHalf) 0f else (b.width() - tW).toFloat()
        val c = PetState.clampToScreen(
            nx, ny, tW.toFloat(), tH.toFloat(),
            b.width().toFloat(), b.height().toFloat()
        )

        // 吸附完成时翻转值对齐所在半屏
        flip = if (leftHalf) 1f else 0f
        flipFrom = flip
        flipTo = flip
        flipRunning = false

        // 状态切换推迟到补间结束
        pendingState = want
        pendingLeft = leftHalf

        // 目标足迹位置 -> 目标中心点（用 want 的足迹换算）
        val targetCenterX = c[0] + centerFromTrackXSized(tW)
        val targetCenterY = c[1] + centerFromTrackYSized(tH)

        // ---- 顺序：需要旋转时，**一边转一边往边上靠，且旋转先完成** ----
        // 用户要求：素材未旋转时松手，要一边旋转一边靠近边框，
        // 并在吸附到边框之前完成旋转。
        // 做法：旋转与位移**同时**开始，但旋转时长更短
        // （ROT_SNAP_MS < SNAP_MS），于是到边之前就已经转正。
        val needRotate = abs(curSizeRot - wantRot) > 0.01f
        if (needRotate) {
            frozenRot = curSizeRot
            frozen = true
            state = want
            cacheSizes()
            startRotAnim(shownRotation, wantRot, ROT_SNAP_MS)
            // 状态与尺寸已在此处理，别再让补间结束时的 applyPendingState 重做一遍
            pendingState = null
            animateTo(targetCenterX, targetCenterY, SNAP_MS.toLong())
            placeWindow()
            invalidate()
            host?.onSettled(want, c[0].roundToInt(), c[1].roundToInt())
            return
        }

        if (abs(curCenterX() - targetCenterX) < 1f && abs(curCenterY() - targetCenterY) < 1f) {
            anchorX = targetCenterX
            anchorY = targetCenterY
            applyPendingState()
        } else {
            animateTo(targetCenterX, targetCenterY)
        }

        placeWindow()
        host?.onSettled(want, c[0].roundToInt(), c[1].roundToInt())
    }

    /** 补间结束后才真正切状态（换素材 + 换旋转），避免中途改尺寸造成跳变 */
    private fun applyPendingState() {
        val st = pendingState ?: return
        pendingState = null
        val prevRot = curSizeRot
        val newRot = PetState.rotationForSnap(st)
        if (abs(prevRot - newRot) > 0.01f) {
            // 先冻结尺寸再换状态，中心点不动
            frozenRot = prevRot
            frozen = true
            state = st
            cacheSizes()
            startRotAnim(shownRotation, newRot)
        } else {
            state = st
            cacheSizesKeepingCenter()
        }
        placeWindow()
        invalidate()
    }

    private fun onTapped() {
        hopping = true
        hopStart = SystemClock.uptimeMillis()
        choreographer.removeFrameCallback(frameCallback)
        scheduleFrame()
        host?.onTapped()
    }

    // ---------------------------------------------------------------- 绘制

    override fun onDraw(canvas: Canvas) {
        val s = sprite ?: return
        placeWindow()

        val hop = 0f
        // 用带动画的角度，避免在 B/E 分界线附近瞬间翻转
        val rot = displayRotation()
        // 支点：拖动时绕**手指作用点**，松手后绕素材中心点。
        // 支点在整段动画里保持不变，所以不会抽搐。
        val cx = pivotX()
        val cy = pivotY()

        val save = canvas.save()
        // 旋转绕格子中心；只改变换、不动格子尺寸 -> 比例不变
        if (abs(rot) > 0.01f) canvas.rotate(rot, cx, cy)

        // 点击反馈：在人物自己的坐标系里压扁（压的方向跟着头朝向）
        val t = hopPhase()
        if (t >= 0f) {
            val sq = squashAt(t)      // 0 = 不压, SQ_MAX = 压得最扁
            // 沿"头轴"压扁。贴底/角落时头轴是 y，贴侧边（转 90°）时头轴是 x。
            val headAlongY = abs(rot) < 45f
            val sx = if (headAlongY) 1f + sq else 1f - sq
            val sy = if (headAlongY) 1f - sq else 1f + sq
            canvas.scale(sx, sy, cx, cy)
            // 压扁之后向头的方向弹起：头朝上就往上，头朝左就往左
            val push = jumpAt(t)
            val dx = if (headAlongY) 0f else -push * (if (rot > 0f) 1f else -1f)
            val dy = if (headAlongY) -push else 0f
            canvas.translate(dx, dy)
        }

        // 跨半屏的镜像翻转：中途压成一条线再展开，做出"翻面"观感
        if (flip > 0.001f && flip < 0.999f) {
            val squash = abs(1f - 2f * flip).coerceAtLeast(0.03f)
            canvas.scale(squash, 1f, cx, cy)
        }

        // 用**预缩放**好的位图：每帧 1:1 贴图，省掉整幅缩放采样
        val bmp = scaledBitmap(state) ?: run {
            canvas.restoreToCount(save)
            return
        }
        canvas.drawBitmap(bmp, cellLocalX(), cellLocalY(), paint)
        canvas.restoreToCount(save)

        // A 区指示线画在最上层
        if (guideVisible()) drawGuides(canvas)
    }

    /** 息屏时停表省电 */
    private var paused = false

    fun setPaused(p: Boolean) {
        if (paused == p) return
        paused = p
        if (p) choreographer.removeFrameCallback(frameCallback) else invalidate()
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
        choreographer.removeFrameCallback(frameCallback)
        scheduleFrame()
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
        choreographer.removeFrameCallback(frameCallback)
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
        private const val FLIP_MS = 110f
        private const val ROT_MS = 180f

        /** 松手贴边前的那次旋转：要"迅速完成"，所以更短 */
        private const val ROT_SNAP_MS = 110f
        private const val HOP_MS = 380f
        private const val HOP_FRACTION = 0.08f

        /** 点击时最大压缩比例（0.22 = 压掉 22% 的厚度） */
        private const val SQ_MAX = 0.22f

        /** 窗口给"轻跳"和四角旋转留的余量，避免边缘被切 */
        const val HOP_PAD_X = 8
        const val HOP_PAD_BOTTOM = 8

        /** 轻跳余量的最小保底值 */
        const val HOP_PAD_MIN = 8

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

            // 与 cacheSizes 用同一套包络公式，保证不裁切
            val diag = kotlin.math.hypot(cell[0].toDouble(), cell[1].toDouble()).toFloat()
            val envW = maxOf(diag, cell[0] * (1f + SQ_MAX)).toInt()
            val envH = maxOf(diag, cell[1] * (1f + SQ_MAX)).toInt()
            val top = (cell[1] * HOP_FRACTION).toInt() + HOP_PAD_MIN

            // 足迹盒在包络里的位置（居中）
            val footOffX = (envW - tw) / 2
            val footOffY = (envH - th) / 2

            val x = if (PetState.isLeft(state)) 0 else screen.width() - tw
            val y = if (PetState.isZoneless(state)) (screen.height() - th) / 2
            else screen.height() - th

            return intArrayOf(
                x - HOP_PAD_X - footOffX, y - top - footOffY,
                envW + HOP_PAD_X * 2, envH + top + HOP_PAD_BOTTOM
            )
        }
    }
}
