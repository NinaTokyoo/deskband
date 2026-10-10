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

    /** 回弹幅度/时长等参数从这里现读（用户可调） */
    private val prefs by lazy { Prefs(context) }

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

    private var sizeFrac = Prefs.SIZE_BASE

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

    /**
     * 轻跳的**底部**余量：与 [topPad] 取同一个值。
     *
     * 弹起方向永远指向**屏幕内侧**（贴底时向上弹、贴上沿时向下弹），
     * 两头都需要同样多的余量 —— 否则贴上沿时一点击，人物会被窗口下沿切掉。
     *
     * ⚠️ 这里**必须与状态无关**（不能"贴上沿才留"）：上下两组形态若窗口高度不同，
     * 拖过屏幕中线时窗口就会 resize（WM 重新布局 + Surface 重分配），
     * 表现是拖拽经过中线时跳一下 —— 与"窗口取两套素材并集"是同一个道理。
     */
    private fun bottomPad(): Int = topPad()

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

    private fun cacheSizes() {
        val s = sprite ?: return
        val screenH = (host?.bounds()?.height() ?: 1080).toFloat()
        val cell = PetState.scaledSize(
            s.naturalWidth(state), s.naturalHeight(state), screenH * sizeFrac
        )
        cellW = cell[0]
        cellH = cell[1]

        // ⚠️ 未冻结时必须**先**把 curSizeRot 归位到当前状态，再算 trackW/trackH。
        // 否则 sizeRotation() 读到的是上一个状态的朝向，足迹盒宽高算反，
        // 贴边就会留缝（首次呼出吸附右边框、素材与边框之间有缝隙）。
        if (!frozen) curSizeRot = PetState.rotationForSnap(state)
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
        var envW = maxOf(diag, cellW * (1f + SQ_MAX))
        var envH = maxOf(diag, cellH * (1f + SQ_MAX))

        // 窗口尺寸取**两个素材形态的并集**（角落素材 + 正立素材）。
        //
        // 于是拖拽中进/出 A 区时窗口尺寸**恒定不变** —— 不再触发
        // updateViewLayout（WM 重新布局 + Surface 可能重分配），这是
        // "拖到 A 区切换素材卡顿"的一大来源。
        //
        // 而且格子仍在包络里**居中**（见下面的 cellOffX/cellOffY），
        // 包络中心与状态无关，所以**素材中心点在窗口内的位置也不变**：
        // 换素材时人物原地不动，不会再出现"停住然后瞬移"。
        //
        // ⚠️ 只有**窗口**取并集。足迹盒 trackW/trackH 与吸附位置、
        //    屏幕夹取仍必须用**当前状态**的尺寸，混用会重演贴边留缝的老 bug。
        run {
            val other = if (PetState.isZoneless(state)) PetState.CORNER_BR else PetState.BORDER_R
            if (other != state) {
                val co = PetState.scaledSize(
                    s.naturalWidth(other), s.naturalHeight(other), screenH * sizeFrac
                )
                val d2 = kotlin.math.hypot(co[0].toDouble(), co[1].toDouble()).toFloat()
                envW = maxOf(envW, d2, co[0] * (1f + SQ_MAX))
                envH = maxOf(envH, d2, co[1] * (1f + SQ_MAX))
            }
        }

        bboxW = envW.toInt() + HOP_PAD_X * 2
        bboxH = envH.toInt() + topPad() + bottomPad()
        boxOffX = HOP_PAD_X
        boxOffY = topPad()

        // 格子在窗口里居中（旋转包络的中心）；绕偏心支点转时靠包络的
        // 富余量容纳，所以这里居中即可。
        //
        // 注：cellOffY - footOffY 与两个余量之差恒为 0（未旋转时 cellH == trackH），
        // 所以余量取值不影响 centerFromTrackY，只影响窗口本身的高度。
        cellOffX = (bboxW - HOP_PAD_X * 2 - cellW) / 2
        cellOffY = (bboxH - topPad() - bottomPad() - cellH) / 2

        // 足迹盒在包络窗口里的偏移 —— 贴边/夹取算的是足迹盒的边，
        // 不是包络窗口的边，所以换算时必须把这个偏移减掉。
        footOffX = (bboxW - HOP_PAD_X * 2 - trackW) / 2
        footOffY = (bboxH - topPad() - bottomPad() - trackH) / 2

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
    // 减去 winShift*：窗口被夹进屏幕挪了多少，格子在窗口内就反着挪多少，
    // 于是宠物在屏幕上的位置恒定不变（见 placeWindow 的说明）。
    private fun cellLocalX(): Float = (boxOffX + cellOffX).toFloat()
    private fun cellLocalY(): Float = (boxOffY + cellOffY).toFloat()

    /** 素材中心点在窗口内的偏移（相对窗口左上角） */
    private fun centerInWindowX(): Float = cellLocalX() + artU * cellW
    private fun centerInWindowY(): Float = cellLocalY() + artV * cellH

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
     * 重新计算尺寸，**保持素材中心点不动**。
     *
     * ⚠️ 这里**不需要任何补偿计算**。
     *
     * 因为 [anchorX]/[anchorY] 本身就是素材中心点的屏幕坐标，
     * 而 [cacheSizes] 不会改 anchor —— 中心点天然保持不变。
     *
     * 曾经这里写过一段"补偿"，但它用了 anchorX + centerFromTrackX
     * （那是旧足迹的**左边缘**，不是中心点），于是变成"钉住足迹左边缘"。
     * 足迹宽度随朝向互换（541 <-> 463），钉住左边缘就等于让中心点横移
     * (463-541)/2 = -39px —— 表现就是朝向一切换素材就**突然平移一小段**，
     * 切回来又移回去。已删除该补偿。
     */
    private fun cacheSizesKeepingCenter() {
        cacheSizes()
    }

    fun setSizeFraction(f: Float) {
        // 与 Prefs 用同一套边界，别在这里写另一组数（否则两处不一致）
        sizeFrac = f.coerceIn(Prefs.MIN_SIZE, Prefs.MAX_SIZE)
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
            // 纵向：以**吸附边**为基准缩放 ——
            //   贴上沿 / 上角 -> 顶边不动（否则缩小后会离屏幕顶一条缝）
            //   躺侧边        -> 垂直居中，中心不动
            //   贴底 / 下角   -> 底边不动（否则缩小后会浮空）
            anchorY = when {
                PetState.isTop(state) -> oldTop + centerFromTrackY()
                state == PetState.BORDER_R || state == PetState.BORDER_L -> oldCenterY
                else -> oldBottom - trackH + centerFromTrackY()
            }

            // 放大后可能把宠物顶到屏幕外（侧边形态是垂直居中的），
            // 这里夹一次，保证至少一半可见、不会"丢"在屏幕外。
            val cl = PetState.clampToScreen(
                anchorX - centerFromTrackX(), anchorY - centerFromTrackY(),
                trackW.toFloat(), trackH.toFloat(),
                b.width().toFloat(), b.height().toFloat()
            )
            anchorX = cl[0] + centerFromTrackX()
            anchorY = cl[1] + centerFromTrackY()
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
            // 冻结时若没有动画在跑，画出角度要与冻结朝向一致，避免画面与尺寸错配
            if (!rotAnimRunning) shownRotation = frozenRot
        } else {
            frozen = false
            rotAnimRunning = false
        }
        state = newState
        cacheSizes()
        startFlipV(if (PetState.isTop(newState)) 1f else 0f)
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

    // ---- 松手吸附的小回弹 ----
    /**
     * 过冲目标。回弹规则（用户确认）：
     *
     *   松手时素材**没超出**屏幕 -> 吸附会把它**往外**拉 -> 先向屏幕外弹一下
     *   松手时素材**已超出**屏幕 -> 吸附会把它**往里**拉 -> 先向屏幕内弹一下
     *
     * 注意这两条其实是同一件事：**过冲方向 = 吸附的移动方向**。
     * 因为"已超出"恰好等价于"吸附会往回拉"，所以直接用
     * sign(目标 - 当前位置) 当方向即可，不用单独判"是否超出"。
     */
    private var bouncePending = false
    private var bounceToX = 0f
    private var bounceToY = 0f

    // ---- 跨半屏的镜像翻转动画 ----
    /** 0 = 未翻转，1 = 已翻转；中间值即翻转过程 */
    private var flip = 0f
    private var flipFrom = 0f
    private var flipTo = 0f
    private var flipStart = 0L
    private var flipRunning = false

    // ---- 跨屏幕中线的**上下翻转**动画（下边组 <-> 上边组）----
    //
    // 上半屏就是下半屏沿水平轴镜像过来的形态（素材上下翻转，见 PetState.flipVFor）。
    // 换组时用一个纵向压扁的动画把 180° 的观感做出来 —— 与上面镜像翻转同一套手法：
    // 素材本身在状态切换那一帧就换好了，动画只是画布上的压扁，不参与任何尺寸/位置记账。
    /** 0 = 下边组（正立），1 = 上边组（倒挂）；中间值即翻转过程 */
    private var flipV = 0f
    private var flipVFrom = 0f
    private var flipVTo = 0f
    private var flipVStart = 0L
    private var flipVRunning = false

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

    /**
     * 上一次的垂直基准（[PetState.VERT_TOP] / [PetState.VERT_BOTTOM]），
     * 用于**屏幕中线**的滞回判定 —— 见 [PetState.vertOf]。
     */
    private var lastVert = PetState.VERT_NONE

    @Suppress("unused")
    private var pendingLeft = false

    private var dragging = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var lastWinX = Int.MIN_VALUE

    /**
     * 窗口被"夹"回屏幕内造成的位移量（见 [placeWindow]）。
     *
     * 贴边/贴角时窗口会有一部分越出屏幕，而系统按 **窗口 frame ∩ 屏幕**
     * 派发触摸，越出那一侧收不到事件 —— 表现就是"靠近屏幕边缘点在宠物上，
     * 却同时操作到下面的桌面/App"。把窗口夹进屏幕，越界量记在这里，
     * 由 [cellLocalX]/[cellLocalY] 反向补偿，宠物屏幕位置保持不变。
     */
    private var winShiftX = 0
    private var winShiftY = 0
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
                    if (bouncePending) {
                        // 第一段（冲到过冲点）结束 -> 平滑回到贴边位置
                        bouncePending = false
                        animateTo(bounceToX, bounceToY, prefs.bounceBackMs.toLong())
                    } else {
                        // 补间走完才换状态，避免中途改尺寸导致的跳变
                        applyPendingState()
                    }
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
            if (flipVRunning) {
                val t = ((now - flipVStart).toFloat() / FLIP_MS).coerceIn(0f, 1f)
                flipV = flipVFrom + (flipVTo - flipVFrom) * easeOutCubic(t)
                if (t >= 1f) {
                    flipV = flipVTo
                    flipVRunning = false
                }
                need = true
            }
            // 呼出/收起滑动：smoothstep，两头慢，观感是"慢慢平移"
            if (sliding) {
                val t = ((now - slideStart).toFloat() / slideMs).coerceIn(0f, 1f)
                // 第一段（进出屏幕）用 smoothstep，两头慢 —— "慢慢平移"；
                // 第二段（回弹收回）用 easeOutCubic，起步快、末段缓 —— "啪"一下。
                val e = if (slidePhase2Running) easeOutCubic(t) else t * t * (3f - 2f * t)
                anchorX = slideFromX + (slideToX - slideFromX) * e
                anchorY = slideFromY + (slideToY - slideFromY) * e
                if (t >= 1f) {
                    if (slideHasPhase2) {
                        // 第一段（冲到过冲点）结束 -> 收回最终落点。
                        //
                        // 第二段用**专用的短时长**，不复用 bounceBackMs：
                        // 那是边框回弹的"慢慢缩回"用时（默认 360ms），
                        // 拿来收呼出回弹会变成慢吞吞地挪回去，观感不自然。
                        // 回弹要"啪"一下，所以短 + easeOutCubic（起步快、末段缓）。
                        slideHasPhase2 = false
                        slidePhase2Running = true
                        slideFromX = slideToX
                        slideFromY = slideToY
                        slideToX = slideEndX
                        slideToY = slideEndY
                        slideStart = now
                        slideMs = SLIDE_SETTLE_MS
                        need = true
                    } else {
                        anchorX = slideToX
                        anchorY = slideToY
                        sliding = false
                        val d = slideDone
                        slideDone = null
                        // 先置 sliding=false 再回调：回调里会 removeView，
                        // 若那时 sliding 仍为 true，后续清理路径会被误挡。
                        d?.invoke()
                    }
                }
                need = true
            }
            // 注：flipRunning 只在真正翻转时被置 true（见 startFlip）
            if (hopping) {
                val t = ((now - hopStart).toFloat() / HOP_MS).coerceIn(0f, 1f)
                if (t >= 1f) hopping = false
                need = true
            }
            // 只有真的有动画时才重绘。
            // 原来无条件 invalidate()，于是每段动画结束都会多画一帧、
            // 而且和 App 主界面共用主线程的 Choreographer —— 用户反馈
            // "呼出桌宠时上下滑动不如收起时跟手"，少画一帧是一帧。
            if (need) {
                invalidate()
                scheduleFrame()
            }
        }
    }

    // ------------------------------------------------ 呼出 / 收起 滑动动画

    private var sliding = false
    private var slideFromX = 0f
    private var slideFromY = 0f
    private var slideToX = 0f
    private var slideToY = 0f
    private var slideStart = 0L
    private var slideMs = SLIDE_MS
    private var slideDone: (() -> Unit)? = null

    /** 呼出回弹的第二段：过冲点 -> 最终落点（有值时才走第二段） */
    private var slideEndX = 0f
    private var slideEndY = 0f
    private var slideHasPhase2 = false

    /** 是否正处于"回弹收回"那一段（决定用哪条缓动曲线） */
    private var slidePhase2Running = false

    /** 本次滑出开始时的静止位置（素材中心屏幕坐标），供反向滑回使用 */
    private var slideRestX = 0f
    private var slideRestY = 0f

    /** 当前锚点（素材中心屏幕坐标）—— 呼出动画要拿它当滑入目标 */
    fun anchorXNow(): Float = anchorX
    fun anchorYNow(): Float = anchorY

    /** 静止位置（素材中心屏幕坐标）—— 收起被打断时要滑回这里 */
    fun restX(): Float = slideRestX
    fun restY(): Float = slideRestY

    /**
     * 足迹盒左上角（**由锚点反算**）。
     *
     * 与 [restore] 里的 `anchor = left + centerFromTrack*()` 严格互为逆运算，
     * 所以"存 → 取"能精确还原，不会每次循环漂一点。
     *
     * ⚠️ 不能用 `currentLeft()/currentTop()`：那是**当前窗口**推出来的位置，
     * 保存动作若发生在动画过程中/之后，拿到的是中间态甚至屏幕外坐标，
     * 存下去就成了脏数据（用户反馈："首次呼出不在右边框中点，
     * 多呼入收起几次位置也变了"）。
     */
    fun footLeft(): Int = (anchorX - centerFromTrackX()).roundToInt()
    fun footTop(): Int = (anchorY - centerFromTrackY()).roundToInt()

    /**
     * **静止**位置的足迹左上角 —— 动画中返回记住的静止点，否则返回当前锚点。
     *
     * ⚠️ 保存位置一律走这个，不要用 [footLeft]/[footTop]：
     * `onSettled` 会延迟 400ms 落盘，如果这 400ms 内用户点了收起，
     * 延迟保存就在**滑动过程中**触发 —— 那时 anchorX 已在屏幕外，
     * 存下去就是脏坐标，多次循环后位置越漂越远甚至跑出屏幕
     * （实测 left: 1157 → 1212 → 1514）。
     */
    fun restFootLeft(): Int =
        ((if (sliding) slideRestX else anchorX) - centerFromTrackX()).roundToInt()

    fun restFootTop(): Int =
        ((if (sliding) slideRestY else anchorY) - centerFromTrackY()).roundToInt()

    /**
     * 看门狗：强制把宠物放回 (x, y)。
     *
     * 滑入动画依赖 Choreographer 帧回调；一旦帧回调因为任何原因没跑起来
     * （被 setPaused 摘掉、窗口在屏幕外时系统不给帧等），宠物就会
     * **永远停在屏幕外** —— 表现是"窗口在、按钮显示收起，但看不见人物，
     * 而复位又能救回来"（用户反馈）。
     * 所以动画启动后由一个延时任务兜底：到时若还没归位，直接落位。
     */
    fun ensureAt(x: Float, y: Float) {
        if (sliding) return
        if (abs(anchorX - x) < 1f && abs(anchorY - y) < 1f) return
        anchorX = x
        anchorY = y
        syncWindow()
        invalidate()
    }

    /** 强制中止动画并落到指定位置（看门狗用，即使正在 sliding 也生效） */
    fun forceSettleAt(x: Float, y: Float) {
        sliding = false
        slideHasPhase2 = false
        slidePhase2Running = false
        slideDone = null
        anchorX = x
        anchorY = y
        syncWindow()
        invalidate()
    }

    /**
     * 回到**记住的静止位置**（收起被打断、且动画已停时用）。
     *
     * ⚠️ 不能用 applySnap() 代替：那个是"按**当前位置**重新吸附最近的边角"。
     * 快点点按时宠物正滑到一半（例如正往下滑、已经在屏幕底部附近），
     * applySnap 就会把它吸到**底部**而不是原来的右边框中点
     * （用户反馈："前几次确实在右边框中点，连续快速点击几次之后就跳到右边框底部"）。
     *
     * @return false 表示还没记录过静止位置，调用方应改用 applySnap 兜底
     */
    fun restoreRest(): Boolean {
        if (slideRestX == 0f && slideRestY == 0f) return false
        forceSettleAt(slideRestX, slideRestY)
        return true
    }

    /** 动画期间不接受触摸，避免"正飞出去又被拖回来" */
    fun isSliding(): Boolean = sliding

    /**
     * 退出方向：**按实际贴边情况**判断，不再只看状态。
     *
     * 规则：**贴底优先** -> 向下收；然后**贴上沿** -> 向上收；
     * 否则贴左 -> 左；贴右 -> 右；都没贴（悬空）也向下收。
     *
     * ⚠️ 原来只按 state 判断（BORDER_* 走水平），于是"吸附在底边"
     * 但状态仍是右边框时就会往左右跑（用户反馈：
     * "吸附在底边的时候应该是收入到屏幕下面，现在是往左右跑的"）。
     * 上边组同理：贴在屏幕顶上就必须**向上**收出去。
     */
    private fun exitVector(): FloatArray {
        val b = host?.bounds() ?: return floatArrayOf(0f, 1f)
        val left = anchorX - centerFromTrackX()
        val top = anchorY - centerFromTrackY()
        val right = left + trackW
        val bottom = top + trackH
        val eps = 2f
        if (bottom >= b.height() - eps) return floatArrayOf(0f, 1f)
        if (top <= eps) return floatArrayOf(0f, -1f)
        if (left <= eps) return floatArrayOf(-1f, 0f)
        if (right >= b.width() - eps) return floatArrayOf(1f, 0f)
        return floatArrayOf(0f, 1f)
    }

    /** 让**整个窗口**都移出屏幕时，锚点（素材中心）该在哪 */
    private fun offScreenAnchor(dirX: Float, dirY: Float): FloatArray {
        val b = host?.bounds() ?: return floatArrayOf(anchorX, anchorY)
        val x = when {
            dirX > 0f -> b.width() + bboxW / 2f
            dirX < 0f -> -bboxW / 2f
            else -> anchorX
        }
        val y = when {
            dirY > 0f -> b.height() + bboxH / 2f
            dirY < 0f -> -bboxH / 2f
            else -> anchorY
        }
        return floatArrayOf(x, y)
    }

    /**
     * 收起：平滑滑出屏幕，滑完回调 [onDone]。
     *
     * 用 smoothstep（t²(3-2t)）而不是 easeOutCubic —— 进出两头都慢，
     * 观感上才是"慢慢平移出去"。
     */
    fun slideOut(onDone: () -> Unit) {
        // ⚠️ 这里**不能** if (sliding) return。
        // 宠物正在滑入时用户点收起，如果直接返回，onDone 就永远不会注册 ——
        // 服务不停、视图不删，宠物卡在半路，表现就是"连点后乱飞消失"。
        // 新指令一律**接管**当前动画（startSlide 会覆盖在途状态）。
        val v = exitVector()
        val to = offScreenAnchor(v[0], v[1])
        // 记住静止位置：连点时要把宠物原路滑回来
        if (!sliding) {
            slideRestX = anchorX
            slideRestY = anchorY
        }
        startSlide(anchorX, anchorY, to[0], to[1], onDone)
    }

    /**
     * 反向滑回静止位置（连点呼出时用）。
     *
     * **丢弃**待执行的 slideDone —— 否则那次收起的完成回调
     * 仍会 removePet()+stopSelf()，把刚呼出的人物删掉。
     *
     * ⚠️ 必须走 [startSlidingTo]：它会把过冲点一起算上，
     * 从而保留**呼出回弹**那一段。原来直接 startSlide(..., null)
     * 用的是默认 endX=toX，slideHasPhase2 恒为 false，
     * 回弹根本没排上（用户反馈："未完全收起时点呼入，呼入的回弹动画消失"）。
     */
    fun reverseSlide() {
        if (!sliding) return
        slideDone = null
        slideHasPhase2 = false
        slidePhase2Running = false
        startSlidingTo(slideRestX, slideRestY)
    }

    /**
     * 从**当前位置**滑到 (targetX, targetY)，并带上呼出回弹。
     *
     * 过冲量用固定像素（复用 prefs.bouncePx），不按行程比例 ——
     * 行程可能是"整个屏幕外"，按比例会冲出天文数字。
     */
    private fun startSlidingTo(targetX: Float, targetY: Float) {
        val fromX = anchorX
        val fromY = anchorY
        val dx = targetX - fromX
        val dy = targetY - fromY
        val len = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val amp = if (len > 1f && prefs.bounceEnabled) prefs.bouncePx else 0f
        val overX: Float
        val overY: Float
        if (amp > 0f) {
            overX = targetX + dx / len * amp
            overY = targetY + dy / len * amp
        } else {
            overX = targetX
            overY = targetY
        }
        startSlide(fromX, fromY, overX, overY, null, targetX, targetY)
    }

    /**
     * 呼出：先瞬移到屏幕外，再平滑滑回 [targetX]/[targetY]。
     *
     * 从**哪一侧**屏幕外进来由 [exitVector] 决定 —— 与收起方向严格互逆，
     * 所以贴上沿的宠物是"从屏幕上沿滑下来"，贴底的是"从屏幕下方升上来"。
     *
     * 注意瞬移那一步必须 syncWindow()，否则窗口还停在屏幕内、
     * 会看到宠物"从原地开始飞"。
     */
    fun slideIn(targetX: Float, targetY: Float) {
        // 同样允许接管在途动画（例如呼出动画没播完就又被呼出一次）
        val v = exitVector()
        // ⚠️ 先把**目标点**记为静止点，再开始滑。
        // 这样如果呼出动画中途被收起打断，之后再次呼出时
        // reverseSlide() 才知道该滑回哪里 —— 否则会用上一次的过期位置。
        slideRestX = targetX
        slideRestY = targetY
        val from = offScreenAnchor(v[0], v[1])
        // ⚠️ 起点必须**严格**满足两点，否则会出现"斜着从角上飞进来"：
        //   1) 进场轴上一定在屏幕外（用 max/min 强制，不只是"加半个窗口"）
        //   2) 另一轴**钉死在落点**上 —— 原实现对该轴保留 anchorX/anchorY，
        //      只要那一刻锚点有偏差，就会变成斜向进场
        //      （用户反馈："第一次呼入是从右下角飞到右边框中点"）。
        val b = host?.bounds()
        if (b != null) {
            if (v[0] > 0f) from[0] = maxOf(from[0], b.width() + bboxW / 2f)
            if (v[0] < 0f) from[0] = minOf(from[0], -(bboxW / 2f))
            if (v[0] == 0f) from[0] = targetX
            if (v[1] > 0f) from[1] = maxOf(from[1], b.height() + bboxH / 2f)
            if (v[1] < 0f) from[1] = minOf(from[1], -(bboxH / 2f))
            if (v[1] == 0f) from[1] = targetY
        }
        anchorX = from[0]
        anchorY = from[1]
        dragOffsetX = 0f
        dragOffsetY = 0f
        syncWindow()

        // 复用统一实现：从屏幕外滑回落点，并带上呼出回弹（过冲 + 收回）。
        startSlidingTo(targetX, targetY)
    }

    private fun startSlide(
        fromX: Float, fromY: Float, toX: Float, toY: Float, onDone: (() -> Unit)?,
        endX: Float = toX, endY: Float = toY
    ) {
        slideFromX = fromX
        slideFromY = fromY
        slideToX = toX
        slideToY = toY
        slideEndX = endX
        slideEndY = endY
        slideHasPhase2 = (endX != toX || endY != toY)
        slidePhase2Running = false
        slideStart = SystemClock.uptimeMillis()
        slideMs = SLIDE_MS
        slideDone = onDone
        sliding = true
        scheduleFrame()
    }

    private fun cancelTween() {
        tweening = false
        // 回弹的第二段也必须一起取消：否则残留的 bouncePending 会在
        // 下一次补间结束时突然把宠物拉回一个过期的目标位置。
        bouncePending = false
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
     * 松手吸附（带回弹）：先冲到"过冲点"，再回落到贴边位置。
     *
     * 过冲方向 = 吸附的移动方向（见 [bouncePending] 的说明），幅度取用户设定。
     * 若某个轴本来就在贴边位置（移动量≈0），该轴不弹。
     *
     * 上下两边对称：贴上沿时目标在上方，过冲就朝上；贴下沿则朝下。
     * 过冲点的夹取（[PetState.clampToScreen]）对两侧一视同仁，
     * 所以"往上冲出屏幕"与"往下冲出屏幕"允许的幅度也完全一样。
     *
     * ⚠️ **过冲点必须夹取**：回弹幅度可被用户调到很大（36px × 最多 5 倍 = 180px），
     * 而宠物本身可能只有 200 多像素宽 —— 不夹的话会被推出屏幕大半，
     * 加上回洛时长也能调到很长，看起来就是"卡进边框里几乎消失"。
     * 这里保证过冲期间至少 [BOUNCE_MIN_VISIBLE] 可见。
     */
    private fun snapWithBounce(
        targetCenterX: Float, targetCenterY: Float,
        snapSide: Boolean, snapVert: Boolean,
        tW: Int, tH: Int
    ) {
        // 回弹参数由用户在 App 里调（滑块 + 数字输入），每次吸附时现读。
        // 总开关关掉时 amp 直接按 0 处理 —— 于是走下面"没什么可弹的"分支，
        // 只做位置补间，不再往外弹。
        val amp = if (prefs.bounceEnabled) prefs.bouncePx else 0f
        val backMs = prefs.bounceBackMs
        val dx = targetCenterX - curCenterX()
        val dy = targetCenterY - curCenterY()
        var ox = 0f
        var oy = 0f
        if (snapSide && abs(dx) > 1f && amp > 0.5f) ox = if (dx > 0f) amp else -amp
        if (snapVert && abs(dy) > 1f && amp > 0.5f) oy = if (dy > 0f) amp else -amp

        if (ox == 0f && oy == 0f) {
            // 已经在贴边位置，没什么可弹的
            bouncePending = false
            if (abs(dx) < 1f && abs(dy) < 1f) {
                anchorX = targetCenterX
                anchorY = targetCenterY
                applyPendingState()
            } else {
                animateTo(targetCenterX, targetCenterY)
            }
            return
        }

        // 过冲点：换回足迹坐标后夹一次，绝不让它整个跑出屏幕。
        //
        // ⚠️ 这里的"至少可见多少"必须**跟着幅度放宽** ——
        // 原来固定 0.75，于是任何幅度都被夹在"最多 25% 出屏"，
        // 幅度超过约 3.5 倍后过冲点全被夹成同一个位置，
        // 用户实测"回弹幅度调节了没有效果"。
        //
        // 现在改成：允许的出屏量正好等于本次幅度 amp，
        // 只有当它会让可见部分少于 [BOUNCE_MIN_VISIBLE] 时才夹。
        // 这样幅度在"最多 88% 出屏"以内都是**精确生效**的。
        val b = host?.bounds()
        var overCenterX = targetCenterX + ox
        var overCenterY = targetCenterY + oy
        if (b != null) {
            val dimX = tW.toFloat()
            val dimY = tH.toFloat()
            val floorX = (1f - abs(ox) / dimX).coerceAtLeast(BOUNCE_MIN_VISIBLE)
            val floorY = (1f - abs(oy) / dimY).coerceAtLeast(BOUNCE_MIN_VISIBLE)
            val cl = PetState.clampToScreen(
                overCenterX - centerFromTrackXSized(tW),
                overCenterY - centerFromTrackYSized(tH),
                dimX, dimY,
                b.width().toFloat(), b.height().toFloat(),
                minOf(floorX, floorY)
            )
            overCenterX = cl[0] + centerFromTrackXSized(tW)
            overCenterY = cl[1] + centerFromTrackYSized(tH)
        }

        bounceToX = targetCenterX
        bounceToY = targetCenterY
        bouncePending = true
        animateTo(overCenterX, overCenterY)
    }

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
        // 换组的瞬间就把"上下翻转"排上动画：上半屏的形态是下半屏沿水平轴镜像过来的
        startFlipV(if (PetState.isTop(newState)) 1f else 0f)
        val prevRot = curSizeRot
        val prevShown = shownRotation
        val newRot = PetState.rotationForSnap(newState)
        val willRotate = abs(prevRot - newRot) > 0.01f

        if (!willRotate) {
            // 又拖回不需要旋转的形态。
            //
            // ⚠️ 这里**不能硬切**：正向过界是 180ms 动画，反向却瞬间落值的话，
            // 手指在分界线附近来回时就是"动画->硬切->动画->硬切"，明显抽搐。
            // 所以正在转的过程中转回来，就反向播动画回去。
            state = newState
            if (rotAnimRunning) {
                startRotAnim(shownRotation, newRot)
            } else {
                frozen = false
                shownRotation = newRot
            }
            cacheSizesKeepingCenter()
            placeWindow()
            return
        }

        // 需要旋转：冻结在旧朝向，整段动画期间尺寸不变
        if (!frozen) {
            frozenRot = prevRot
            frozen = true
            // 冻结时若没有动画在跑，画出角度要与冻结朝向一致，避免画面与尺寸错配
            if (!rotAnimRunning) shownRotation = frozenRot
        }
        // ⚠️ 已经在朝**同一个角度**转，就不要重启动画。
        // 拖动时状态可能反复切换，每次都重置计时起点会让 180ms 的动作走不完
        // （与镜像翻转那个"太慢"的 bug 同源）。
        val sameTarget = rotAnimRunning && abs(rotAnimTo - newRot) < 0.01f
        state = newState
        cacheSizes()
        if (!sameTarget) startRotAnim(prevShown, newRot)
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
        if (rotAnimRunning || frozen) shownRotation else PetState.rotationForSnap(state)

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
        // ⚠️ 已经在朝**同一个目标**翻转时，绝不重启动画。
        //
        // 拖动中每个 MOVE 事件都会调到这里；若每次都重置起点，
        // 动画计时会一直被推后，200ms 的动作被拖成"爬行" ——
        // 表现就是"镜像动画太慢"。
        if (flipRunning && abs(flipTo - target) < 0.01f) return
        if (abs(flip - target) < 0.01f && !flipRunning) {
            flip = target
            return
        }
        flipFrom = flip
        flipTo = target
        flipStart = SystemClock.uptimeMillis()
        flipRunning = true
        choreographer.removeFrameCallback(frameCallback)
        scheduleFrame()
    }

    /**
     * 跨**屏幕中线**的上下翻转：与 [startFlip] 完全对称，只是压扁轴换成纵向。
     *
     * 目标由**状态**决定（[PetState.isTop]），不是由"当前离上沿近不近"决定 ——
     * 画出来的形态必须与最终吸附的形态一致，否则松手那一下会突然翻过去。
     */
    private fun startFlipV(target: Float) {
        if (flipVRunning && abs(flipVTo - target) < 0.01f) return
        if (abs(flipV - target) < 0.01f && !flipVRunning) {
            flipV = target
            return
        }
        flipVFrom = flipV
        flipVTo = target
        flipVStart = SystemClock.uptimeMillis()
        flipVRunning = true
        choreographer.removeFrameCallback(frameCallback)
        scheduleFrame()
    }

    /** 不做动画，直接把上下翻转对齐到某个状态（恢复位置 / 复位 / 吸附落位时用） */
    private fun alignFlipV(st: Int) {
        val t = if (PetState.isTop(st)) 1f else 0f
        flipV = t
        flipVFrom = t
        flipVTo = t
        flipVRunning = false
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

    /**
     * 把窗口挪到与素材中心点对齐的位置，并在需要时调整窗口尺寸。
     *
     * 窗口尺寸只在**状态/尺寸真的变了**的时候才调用 updateViewLayout 改，
     * 纯移动不会触发 resize —— 否则拖动时每帧 resize 一次，卡到不能用。
     */
    private fun placeWindow() {
        // 以**素材中心点**反推窗口位置：中心点定点不动，尺寸变了也不会横移
        //
        // ⚠️ 顺序很重要：必须先按"不夹取"算出 x/y，再夹取并记录补偿量。
        //    因为 centerInWindowX() 依赖 cellLocalX()，而 cellLocalX() 又依赖
        //    winShiftX —— 先清零再算，得到的才是"未夹取"的窗口位置。
        winShiftX = 0
        winShiftY = 0
        var x = (curCenterX() - centerInWindowX()).roundToInt()
        var y = (curCenterY() - centerInWindowY()).roundToInt()

        // ⚠️ 这里**不做窗口夹取**（曾经加过，已移除）。
        //
        // 夹取会把窗口拉回屏幕内，再用画布平移反向补偿。问题在于
        // **窗口移动是异步的、比画布平移慢一帧** —— 滑动期间跳过夹取、
        // 滑完立刻恢复夹取的交接处，就会看到一帧跳变/闪烁
        // （用户反馈："呼入收起的时候会有闪烁现象"）。
        // 它还造成过"窗口在、宠物画到屏幕外看不见"。
        //
        // 贴边时窗口有一部分在屏幕外，系统只对 **窗口 frame ∩ 屏幕** 派发触摸，
        // 这是可接受的；换取动画完全平滑、位置绝不漂移。
        winShiftX = 0
        winShiftY = 0

        if (x == lastWinX && y == lastWinY && bboxW == lastWinW && bboxH == lastWinH) return
        lastWinX = x
        lastWinY = y
        lastWinW = bboxW
        lastWinH = bboxH
        host?.placeWindow(x, y, bboxW, bboxH)
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
        // 纵向：由状态自己决定挂在哪条水平边上（贴上沿 -> 顶边贴 0）
        val trackTop = when (PetState.vertForState(newState)) {
            PetState.VERT_TOP -> 0f
            else -> if (PetState.isZoneless(newState)) ((b.height() - trackH) / 2f)
            else (b.height() - trackH).toFloat()
        }
        anchorX = trackLeft + centerFromTrackX()
        anchorY = trackTop + centerFromTrackY()
        dragOffsetX = 0f
        dragOffsetY = 0f
        lastVert = PetState.vertForState(newState)
        alignFlipV(newState)
        cancelTween()
        syncWindow()
    }

    /** 从保存的位置恢复（left/top 是**足迹盒**左上角，与持久化格式一致） */
    fun restore(state0: Int, left: Int, top: Int) {
        state = state0
        cacheSizes()
        // 记住这个状态的纵向归属：紧接着的第一次拖动就不会因为中线滞回而先跳到另一组
        lastVert = PetState.vertForState(state0)
        alignFlipV(state0)
        anchorX = left + centerFromTrackX()
        anchorY = top + centerFromTrackY()
        dragOffsetX = 0f
        dragOffsetY = 0f
        cancelTween()
        // ⚠️ 兜底：把恢复出来的锚点夹进屏幕内。
        //
        // 保存位置可能本身就是屏幕外的坐标（收起时锚点在滑出路径上被存了下来，
        // 实测 pos_left=1514 > 屏宽 1440）——直接恢复到那里，人物就看不见了，
        // 而复位不受影响（它按右边框中点重算），于是表现为
        // "完全收起后再呼出看不见人，但复位能救回来"。
        // 夹取后 placeWindow 也不再有越界补偿，人物必定落在屏幕内。
        host?.bounds()?.let { b ->
            // 夹的是**整个足迹盒**，不是素材中心 ——
            // 只夹中心的话中心正好落在屏幕边线上，宠物会有一半在屏幕外。
            val hw = trackW / 2f
            val hh = trackH / 2f
            anchorX = anchorX.coerceIn(hw, (b.width() - hw).coerceAtLeast(hw))
            anchorY = anchorY.coerceIn(hh, (b.height() - hh).coerceAtLeast(hh))
        }
        syncWindow()
    }

    /**
     * 复位：回到**右边框中点**（旋转形态，垂直居中）。
     *
     * 尺寸与回弹参数由调用方先重置到默认倍率，这里只负责把姿态与位置归位。
     */
    fun resetToRightMid() {
        val b = host?.bounds() ?: return
        state = PetState.BORDER_R
        frozen = false
        rotAnimRunning = false
        shownRotation = PetState.rotationForSnap(PetState.BORDER_R)
        cacheSizes()
        val left = (b.width() - trackW).toFloat()
        val top = (b.height() - trackH) / 2f
        anchorX = left + centerFromTrackX()
        anchorY = top + centerFromTrackY()
        dragOffsetX = 0f
        dragOffsetY = 0f
        cancelTween()
        flip = 0f
        flipFrom = 0f
        flipTo = 0f
        lastZone = PetState.ZONE_B
        lastVert = PetState.vertForState(PetState.BORDER_R)
        alignFlipV(PetState.BORDER_R)
        syncWindow()
        host?.onSettled(state, left.roundToInt(), top.roundToInt())
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
                // 与 onDraw 里的 canvas.translate 严格互逆：
                // 下边组画的时候向上移了 -push，这里就加回 push；上边组相反。
                dy += if (PetState.isTop(state)) -push else push
            } else {
                dx += push * (if (rot > 0f) 1f else -1f)
            }
        }

        // 反掉翻转动画的水平压缩
        if (flip > 0.001f && flip < 0.999f) {
            val squash = abs(1f - 2f * flip).coerceAtLeast(0.03f)
            dx /= squash
        }

        // 反掉上下翻转动画的垂直压缩（与 onDraw 里的 canvas.scale 互逆）
        if (flipV > 0.001f && flipV < 0.999f) {
            val squash = abs(1f - 2f * flipV).coerceAtLeast(0.03f)
            dy /= squash
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
        val opaque = runCatching { ((bmp.getPixel(bx, by) ushr 24) and 0xFF) >= HIT_ALPHA_MIN }
            .getOrDefault(true)
        if (opaque) return true

        // 单像素透明时，**落在素材的 alpha 外接框里也算命中**。
        //
        // 原来只认"这一个像素不透明"，于是点在以下位置会被判为未命中，
        // 事件直接穿透到下层窗口 —— 用户看到的现象就是
        // "吸附到右下角后，点/拖素材却操作到下面的桌面或 App"：
        //   · 发丝之间、头与身体之间的透明缝隙（角落素材尤其多）
        //   · 抗锯齿造成的半透明边缘（alpha 低于阈值）
        // 外接框远小于窗口（窗口还有对角线包络的富余量），
        // 所以不会让整屏变得不可点。
        val b = alphaBounds(bmp) ?: return false
        return bx >= b[0] && bx < b[2] && by >= b[1] && by < b[3]
    }

    /**
     * 取该状态要画的位图（**镜像 / 上下翻转在这里定，不含旋转**）。
     *
     * ⚠️ mirror 与 flipV 都必须是 **state 的纯函数**：
     *   · [PetState.isLeft]   —— 左半屏水平镜像
     *   · [PetState.flipVFor] —— 上边组（上角 / 贴上沿）上下翻转
     *
     * 绝不能用"当前离上沿近不近"这类**运行时标志**来选翻转：
     * artCenterCache / scaledCache 都以 state 为键，翻转一旦不由状态唯一决定，
     * 缓存就会串味 —— 素材中心点算错（表现是位置自己变了）、
     * 命中判定与实际画面不一致。
     *
     * 左半屏叠加上下翻转不会把人物倒过来：上边组本来就是"下边组沿水平轴镜像"
     * 的形态，左右两侧用同一套规则才对称；而旋转躺倒的侧边形态由
     * [PetState.flipVFor] 返回 false，不受影响（否则头会朝错方向）。
     */
    private fun bitmapFor(st: Int): Bitmap? {
        val s = sprite ?: return null
        return s.bitmap(st, mirror = PetState.isLeft(st), flipV = PetState.flipVFor(st))
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
                if (!hitsPet(event.x + winShiftX, event.y + winShiftY)) return false
                downRawX = event.rawX
                downRawY = event.rawY
                dragOffsetX = 0f
                dragOffsetY = 0f
                dragging = false
                cancelTween()
                // 记下手指作用点在窗口内的位置 —— 旋转就绕它转
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

    /** 拖动过程中刷新状态（素材与旋转随之变化，没有中间角度） */
    private fun updateDragState() {
        val b = host?.bounds() ?: return
        val x = curTrackLeft()
        val y = curTrackTop()
        // 中线判定用**足迹中心**（0115 版行为）
        val leftHalf = (x + trackW / 2f) < b.width() / 2f

        // 跨半屏 -> 触发镜像翻转动画
        startFlip(if (leftHalf) 1f else 0f)

        val screenH = b.height().toFloat()
        val trackHf = trackH.toFloat()

        // ---- 垂直基准：这里只决定"以哪条水平边为准"，距离语义本身不动 ----
        // 离上沿近就是上边组（带中线滞回，见 PetState.vertOf）。
        // 选完基准才去量距离，于是上/下两组天然共用同一套 A/B/E 判定。
        val v = PetState.vertOf(y, trackHf, screenH, lastVert)
        lastVert = v
        val dv = PetState.vertDistFor(v, y, trackHf, screenH)

        val dr = PetState.sideDist(x, trackW.toFloat(), b.width().toFloat(), leftHalf)
        // 旋转判定看**素材中心点**有没有越过 B/E 分界线
        val cd = PetState.centerSideDist(
            curCenterX(), b.width().toFloat(), leftHalf
        )

        // 带滞回的分区判定。门槛 = aW + 半宽，但半宽必须取**与朝向无关**的值。
        //
        // ⚠️ 不能用 trackW/2：它随朝向互换（正立 541 / 旋转 463），
        // 于是"门槛决定朝向、朝向又决定门槛"形成自反馈：
        //   正立 -> 门槛470 -> 中心点450<=470 -> 判B -> 旋转
        //   旋转 -> 门槛431 -> 450>431 -> 判E -> 转回正立 -> 又判B ...
        // 无限循环，表现就是分界线附近小幅高频抖动。
        // 取 max(trackW, trackH)（互换后不变）即可打破这个反馈。
        val halfRef = maxOf(trackW, trackH) / 2f
        val zone = PetState.zoneWithHysteresis(
            v, dv, dr, leftHalf, lastZone, cd,
            PetState.currentAW + halfRef
        )
        lastZone = zone

        // B 区即"中心点已越界" -> 旋转形态；
        // A/E 区按**垂直基准**决定用上角/下角、贴上沿/贴下沿（倒挂/正立）
        val st = PetState.stateForZone(v, zone, leftHalf)
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
        // 中线判定用**足迹中心**（0115 版行为）
        val leftHalf = (x + trackW / 2f) < b.width() / 2f

        val screenH = b.height().toFloat()
        val trackHf = trackH.toFloat()

        // 与拖动时**完全同一套**基准选择与距离度量（含中线滞回，参数一并续上），
        // 所以"松手瞬间看到的形态"与"最终吸附到的形态"必然一致。
        val v = PetState.vertOf(y, trackHf, screenH, lastVert)
        lastVert = v
        val dv = PetState.vertDistFor(v, y, trackHf, screenH)
        val dr = PetState.sideDist(x, trackW.toFloat(), b.width().toFloat(), leftHalf)

        // 与拖动时保持一致：中心点判据，门槛含半宽，且半宽取与朝向无关的值
        // （否则门槛与朝向互相决定，会形成自反馈振荡）
        val cd = PetState.centerSideDist(curCenterX(), b.width().toFloat(), leftHalf)
        val zone = PetState.zoneWithHysteresis(
            v, dv, dr, leftHalf, lastZone, cd,
            PetState.currentAW + maxOf(trackW, trackH) / 2f
        )
        lastZone = zone

        // ---- 贴哪条边：整个判定（含 E 区"贴更近的边"）都在逻辑层做完 ----
        // 用户要求：即使拖的时候素材没转，只要松手后贴到侧边，就要自动旋转 + 贴边。
        val edges = PetState.snapEdgesForZone(v, zone, dv, dr)
        val snapVert = edges[0] == 1
        val snapSide = edges[1] == 1
        // 贴水平边时用哪个状态由**垂直基准**决定：下沿正立、上沿倒挂。
        // 贴两条边就是角：下角用 CORNER_B[LR]，上角用 CORNER_T[LR]。
        val want = when {
            snapVert && snapSide -> PetState.stateForZone(v, PetState.ZONE_A, leftHalf)
            // 贴侧边 -> 一定旋转躺倒（左右对称，与上下无关）
            snapSide -> if (leftHalf) PetState.BORDER_L else PetState.BORDER_R
            // 只贴水平边 -> 正立素材（可能上下翻转）
            else -> PetState.stateForZone(v, PetState.ZONE_E, leftHalf)
        }

        // 目标位置必须用 **want 的足迹** 算，否则换成旋转形态后会不贴边
        val wantRot = PetState.rotationForSnap(want)
        val wantRotated = abs(abs(wantRot) - 90f) < 1f
        val tW = if (wantRotated) cellH else cellW
        val tH = if (wantRotated) cellW else cellH

        var nx = x
        var ny = y
        // 贴水平边：贴上沿贴 0，贴下沿贴底 —— 由垂直基准决定，两边完全对称
        if (snapVert) ny = if (v == PetState.VERT_TOP) 0f else (b.height() - tH).toFloat()
        if (snapSide) nx = if (leftHalf) 0f else (b.width() - tW).toFloat()
        val c = PetState.clampToScreen(
            nx, ny, tW.toFloat(), tH.toFloat(),
            b.width().toFloat(), b.height().toFloat()
        )

        // 吸附完成时翻转值对齐所在半屏（水平镜像）与所在上下半屏（上下翻转）。
        // flipV 只驱动压扁动画：0 与 1 都不压扁，对齐它只是"不再补播动画"，
        // 不会让画面突然翻过去（素材随状态走，见 bitmapFor）。
        flip = if (leftHalf) 1f else 0f
        flipFrom = flip
        flipTo = flip
        flipRunning = false
        alignFlipV(want)

        // 状态切换推迟到补间结束
        pendingState = want
        pendingLeft = leftHalf

        // 目标足迹位置 -> 目标中心点（用 want 的足迹换算）
        val targetCenterX = c[0] + centerFromTrackXSized(tW)
        val targetCenterY = c[1] + centerFromTrackYSized(tH)

        // ---- 顺序：需要旋转时，**一边转一边往边上靠，且旋转先完成** ----
        // 用户要求：素材未旋转时松手，要一边旋转一边靠近边框，
        // 并在吸附到边框之前完成旋转。
        // 做法：旋转与位移**同时**开始，旋转(ROT_MS 180)短于位移(SNAP_MS 220)，
        // 所以到边之前就已经转正。
        //
        // ⚠️ 判定必须基于**实际显示的角度**(shownRotation)，不能用 curSizeRot。
        // curSizeRot 是尺寸记账值，只在 cacheSizes() 里且 !frozen 时更新。
        // 曾经的坑：拖动中旋转已开始(frozen=true，state 已改)但 curSizeRot 还是旧值，
        // 若此时松手且目标是正立，needRotate 会算成 false -> 走不旋转的分支；
        // 而那个还在跑的旋转动画随后跑完、onRotationDone 把 curSizeRot 更新成旋转形态，
        // 于是位移补间结束时的 applyPendingState 才发现要转 -> **先吸附、后旋转**。
        // 加上 frozen / rotAnimRunning 的判断，保证"正在转"时一定走本分支统一处理。
        val needRotate =
            frozen || rotAnimRunning || abs(shownRotation - wantRot) > 0.01f
        if (needRotate) {
            frozenRot = curSizeRot
            frozen = true
            // 冻结时若没有动画在跑，画出角度要与冻结朝向一致，避免画面与尺寸错配
            if (!rotAnimRunning) shownRotation = frozenRot
            state = want
            cacheSizes()
            startRotAnim(shownRotation, wantRot)
            // 状态与尺寸已在此处理，别再让补间结束时的 applyPendingState 重做一遍
            pendingState = null
            // 边转边靠；末尾带回弹
            snapWithBounce(targetCenterX, targetCenterY, snapSide, snapVert, tW, tH)
            placeWindow()
            invalidate()
            host?.onSettled(want, c[0].roundToInt(), c[1].roundToInt())
            return
        }

        if (abs(curCenterX() - targetCenterX) < 1f && abs(curCenterY() - targetCenterY) < 1f) {
            anchorX = targetCenterX
            anchorY = targetCenterY
            bouncePending = false
            applyPendingState()
        } else {
            snapWithBounce(targetCenterX, targetCenterY, snapSide, snapVert, tW, tH)
        }

        placeWindow()
        host?.onSettled(want, c[0].roundToInt(), c[1].roundToInt())
    }

    /**
     * 位移补间结束后切换状态（换素材）。
     *
     * ⚠️ 这里**只在确实需要转、且还没转到位时**才启动旋转，而且判断依据是
     * **实际显示的角度** [shownRotation]，不是记账值 [curSizeRot]。
     *
     * 因为本函数在位移**走完之后**才被调用：如果此时才发现要转，
     * 用户看到的就是"先吸附就位、然后再旋转"。
     * 正确的顺序应该由 settleInternal 的 needRotate 分支统一处理
     * （旋转与位移同时开始、旋转先完成），这里只是兜底。
     */
    private fun applyPendingState() {
        val st = pendingState ?: return
        pendingState = null
        val newRot = PetState.rotationForSnap(st)
        alignFlipV(st)
        if (!rotAnimRunning && abs(shownRotation - newRot) > 0.01f) {
            // 兜底：还差一个旋转，冻结尺寸后接着转（正常路径不会走到这里）
            frozenRot = curSizeRot
            frozen = true
            // 冻结时若没有动画在跑，画出角度要与冻结朝向一致，避免画面与尺寸错配
            if (!rotAnimRunning) shownRotation = frozenRot
            state = st
            cacheSizes()
            startRotAnim(shownRotation, newRot)
        } else {
            state = st
            // 旋转动画仍在跑时尺寸保持冻结，由 onRotationDone 统一解冻
            if (rotAnimRunning || frozen) cacheSizes() else cacheSizesKeepingCenter()
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
        // 窗口被夹进屏幕造成的位移，在这里用**画布平移**补偿。
        //
        // 只影响绘制，**不污染** cellLocalX / centerInWindowX / centerFromTrackX
        // 等坐标换算。（曾经把补偿放进 cellLocalX：centerFromTrackX 跟着变，
        // 收起时保存的足迹位置与呼出时反算的 anchor 用了不同的 winShift，
        // 于是每次收起再呼出就漂一个 winShift —— 位置不断往角落跑。）
        if (winShiftX != 0 || winShiftY != 0) {
            canvas.translate(-winShiftX.toFloat(), -winShiftY.toFloat())
        }
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
            // 压扁之后向头的方向弹起：头朝上就往上，头朝左就往左。
            // 上边组是倒挂的（头朝下），所以它**向下**弹 —— 与"始终朝屏幕内侧弹"
            // 是同一件事：弹起方向永远背离吸附的那条边。
            val push = jumpAt(t)
            val dx = if (headAlongY) 0f else -push * (if (rot > 0f) 1f else -1f)
            val dy = if (!headAlongY) 0f else if (PetState.isTop(state)) push else -push
            canvas.translate(dx, dy)
        }

        // 跨半屏的镜像翻转：中途压成一条线再展开，做出"翻面"观感
        if (flip > 0.001f && flip < 0.999f) {
            val squash = abs(1f - 2f * flip).coerceAtLeast(0.03f)
            canvas.scale(squash, 1f, cx, cy)
        }

        // 跨屏幕中线的**上下翻转**：同上，只是压扁轴换成纵向
        // （下边组 <-> 上边组，素材本身就是沿水平轴镜像的两套）
        if (flipV > 0.001f && flipV < 0.999f) {
            val squash = abs(1f - 2f * flipV).coerceAtLeast(0.03f)
            canvas.scale(1f, squash, cx, cy)
        }

        // 用**原图 + src/dst 矩形**绘制，让 GPU 去做缩放。
        //
        // 原来走 scaledBitmap(state)：Bitmap.createScaledBitmap 在 CPU 上重采样
        // 并**分配一块新位图**，缓存 key 含 cellW/cellH，所以进 A 区
        // （素材纵横比变化 -> cellW 变）就会 cache miss，于是在拖拽的绘制里
        // 同步做一次大图重采样 —— 用户反馈的"拖到 A 区切换素材明显卡顿"就是它。
        // 反复 miss 还会反复分配大块内存，带来 GC 压力。
        //
        // drawBitmap(bmp, src, dst, paint) 由 GPU 采样，paint 已带
        // FILTER_BITMAP_FLAG，质量一致，但**不再创建任何位图**。
        // ⚠️ 必须判 isRecycled。
        //
        // 这里用的是 PetSprite 持有的**原图**；收起桌宠时服务销毁、
        // 那批位图会被回收，而窗口往往还会再走一帧 traversal ——
        // 直接画就会抛 "Canvas: trying to use a recycled bitmap"，
        // 整个进程崩溃（用户看到的"点收起把 APP 关了"）。
        // 改用原图之前用的 scaledBitmap 自带这个检查，换过来时漏掉了。
        val bmp = bitmapFor(state)?.takeIf { !it.isRecycled } ?: run {
            canvas.restoreToCount(save)
            return
        }
        src.set(0, 0, bmp.width, bmp.height)
        dst.set(
            cellLocalX(), cellLocalY(),
            cellLocalX() + cellW, cellLocalY() + cellH
        )
        canvas.drawBitmap(bmp, src, dst, paint)
        canvas.restoreToCount(save)
    }

    /** 息屏时停表省电 */
    private var paused = false

    fun setPaused(p: Boolean) {
        if (paused == p) return
        paused = p
        if (p) choreographer.removeFrameCallback(frameCallback) else invalidate()
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

        /** 呼出/收起滑出滑入的时长（"慢慢平移"，比吸附慢得多） */
        private const val SLIDE_MS = 420f

        /** 呼出回弹第二段（收回落点）的时长：要"啪"一下，不能慢 */
        private const val SLIDE_SETTLE_MS = 150f
        private const val FLIP_MS = 260f
        private const val ROT_MS = 180f

        /** 回弹过冲幅度与回落时长的默认值在 Prefs 里（用户可调），这里不再重复定义 */

        /**
         * 回弹过冲期间**至少**保留多少可见比例。
         *
         * 防止幅度调大后被推出屏幕大半，看着像"卡进边框里消失了"。
         */
        const val BOUNCE_MIN_VISIBLE = 0.12f
        private const val HOP_MS = 380f
        private const val HOP_FRACTION = 0.08f

        /** 点击时最大压缩比例（0.22 = 压掉 22% 的厚度） */
        private const val SQ_MAX = 0.22f

        /** 窗口给"轻跳"和四角旋转留的余量，避免边缘被切 */
        const val HOP_PAD_X = 8

        /**
         * 轻跳余量的最小保底值。
         *
         * 上下两头**用同一个值**（见 [bottomPad]）：弹起方向永远朝屏幕内侧，
         * 贴上沿的宠物是向下弹的，底部同样需要 cellH×8% 的余量。
         */
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
            val pad = (cell[1] * HOP_FRACTION).toInt() + HOP_PAD_MIN

            // 足迹盒在包络里的位置（居中）
            val footOffX = (envW - tw) / 2
            val footOffY = (envH - th) / 2

            val x = if (PetState.isLeft(state)) 0 else screen.width() - tw
            // 纵向：上边组贴顶，躺侧边垂直居中，其余贴底
            val y = when (PetState.vertForState(state)) {
                PetState.VERT_TOP -> 0
                else -> if (PetState.isZoneless(state)) (screen.height() - th) / 2
                else screen.height() - th
            }

            return intArrayOf(
                x - HOP_PAD_X - footOffX, y - pad - footOffY,
                envW + HOP_PAD_X * 2, envH + pad * 2
            )
        }
    }
}
