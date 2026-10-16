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

        /**
         * 把窗口移动到 [x],[y] 并按 [w],[h] 调整大小。
         *
         * @return 窗口参数**确实已经下发**为 true。
         *   ⚠️ 失败时务必返回 false：PetView 只在成功后才更新窗口缓存，
         *   否则一次 updateViewLayout 失败会让同坐标被永久跳过（见 [winPlaced]）。
         *   注意"参数本来就已经是这些值"属于成功，要返回 true。
         */
        fun placeWindow(x: Int, y: Int, w: Int, h: Int): Boolean

        /** 拖动结束后回报状态与位置，供持久化 */
        fun onSettled(state: Int, left: Int, top: Int)

        fun onTapped()

        /**
         * 点击动画走到"**压缩结束、开始弹起**"的那一刻（[TAP_PEAK_T] 处）。
         *
         * 服务据此让思考气泡与弹起**同步**出现（用户要求："当人物压缩完开始弹起时，
         * 气泡……同步开始出现"）。只有 [bubbleAllowed] 成立时才会被调用。
         */
        fun onJumpStart()
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
     *
     * ⚠️ 比例用 [HOP_PAD_FRACTION]（轻跳与长按弹起取较大者）：
     * 长按松手的弹起比轻跳高得多，只按 8% 留余量会被切顶。
     */
    private fun topPad(): Int =
        (cellH * HOP_PAD_FRACTION).toInt() + HOP_PAD_MIN

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
    /** [alphaBoundsCached] 的单槽记忆化：最近一次查询的 state 与其外接框 */
    private var hitBoundsState: Int? = null
    private var hitBoundsRect: IntArray? = null

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
        // 另外点击/长按压缩会把横向撑大 (1+SQ_ENV_MAX) 倍，也要一起容纳。
        val diag = kotlin.math.hypot(cellW.toDouble(), cellH.toDouble()).toFloat()
        var envW = maxOf(diag, cellW * (1f + SQ_ENV_MAX))
        var envH = maxOf(diag, cellH * (1f + SQ_ENV_MAX))

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
                envW = maxOf(envW, d2, co[0] * (1f + SQ_ENV_MAX))
                envH = maxOf(envH, d2, co[1] * (1f + SQ_ENV_MAX))
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

        // 素材中心点：按 alpha 外接框算，结果**按状态缓存**（见 [artCenterOf]）。
        val uv = artCenterOf(state)
        artU = uv[0]
        artV = uv[1]

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

    /**
     * 某个状态对应的**格子**尺寸（未旋转，按素材自身比例缩放）。
     *
     * ⚠️ 角落素材与正立素材的格子宽度**不一样**（约 263 vs 289），
     * 所以凡是"按目标状态算落点"的地方都必须用这个，不能用当前状态的
     * [cellW]/[cellH] —— 差半个差值 ≈ 13px，表现就是吸附后贴边留缝（或越界）。
     */
    private fun cellOf(st: Int): IntArray {
        val s = sprite ?: return intArrayOf(cellW, cellH)
        val screenH = (host?.bounds()?.height() ?: 1080).toFloat()
        return PetState.scaledSize(s.naturalWidth(st), s.naturalHeight(st), screenH * sizeFrac)
    }

    /** 某个状态旋转后的**足迹**（宽高可能互换） */
    private fun footprintOf(st: Int): IntArray {
        val cell = cellOf(st)
        val q = ((PetState.rotationForSnap(st) % 360f) + 360f) % 360f
        return if (q == 90f || q == 270f) intArrayOf(cell[1], cell[0]) else cell
    }

    /**
     * 某状态下**素材中心点**在**足迹**里的偏移（相对足迹左上角）。
     *
     * 未旋转：就是 `(u·格宽, v·格高)`。
     * 旋转 90°：足迹是格子转过来的外接框，偏移分别退化成
     * `(格高·(1−v), 格宽·u)`（+90°）与 `(格高·v, 格宽·(1−u))`（−90°）；
     * 素材中心接近格子中心时两者都≈足迹中心。
     */
    private fun centerOffsetInTrack(st: Int): FloatArray {
        val cell = cellOf(st)
        val uv = artCenterOf(st)
        val q = ((PetState.rotationForSnap(st) % 360f) + 360f) % 360f
        return when (q) {
            90f -> floatArrayOf(cell[1] * (1f - uv[1]), cell[0] * uv[0])
            270f -> floatArrayOf(cell[1] * uv[1], cell[0] * (1f - uv[0]))
            else -> floatArrayOf(cell[0] * uv[0], cell[1] * uv[1])
        }
    }

    /**
     * 素材中心点在某状态位图里的相对位置（0..1），**按状态缓存**。
     *
     * 按 alpha 外接框算，而不是简单取一半 —— 格子是矩形，人物不一定居中，
     * 只有按真实像素范围取中心，旋转时才是"绕人物自己转"。
     * alphaBounds 要整张位图逐行扫描，所以结果必须缓存。
     */
    private fun artCenterOf(st: Int): FloatArray {
        artCenterCache[st]?.let { return it }
        var u = 0.5f
        var v = 0.5f
        val bmp = runCatching { bitmapFor(st) }.getOrNull()
        if (bmp != null) {
            val b = alphaBounds(bmp)
            if (b != null && b[2] > b[0] && b[3] > b[1]) {
                u = (b[0] + b[2]) / 2f / bmp.width
                v = (b[1] + b[3]) / 2f / bmp.height
            }
        }
        val out = floatArrayOf(u, v)
        artCenterCache[st] = out
        return out
    }

    /**
     * 应用新的尺寸倍率。
     *
     * ⚠️ 这里**只做"以吸附边为基准缩放"**（贴上沿/上角 -> 顶边不动；
     *    躺侧边 -> 竖直居中不动；贴底/下角 -> 底边不动；横向贴哪边钉哪边）。
     *    **不要再往里加东西** —— 2.1.6~2.1.9 加过"尺寸变化后强制吸回某一侧"，
     *    手机上会抖、还会把人物甩到另一侧；2.1.11 试过"侧边改成钉竖直中心"，
     *    用户实测后也不要。**用户拍板：就用 2.1.10 这一版的行为**
     *    （= 下面这段，等于 2.1.3 / 基线的锚点规则）。
     */
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

        // 以**吸附边**为基准缩放。
        //
        // ⚠️ 这里改成**直接用记下的那条边的坐标反算中心**，不再走
        //    `oldRight - trackW + centerFromTrackX()` 那种绕一层的写法。
        //    旧写法隐含依赖"缩放前后 centerFromTrackX 与新 trackW 的关系"，
        //    而 cacheSizes() 之后 trackW/trackH/artU/artV 全都换成了新值，
        //    读起来很难确认锚到的到底是哪条边。
        //    现在写成"让新足迹的那条边落在缩放前的位置上"，一眼可验：
        //      贴右：新右边 == 旧右边   ->  center = oldRight - (newTrackW - offset)
        //      贴下：新下边 == 旧下边
        //    贴左 / 贴上沿同理。
        val b = host?.bounds()
        if (b != null) {
            val offX = centerFromTrackX()
            val offY = centerFromTrackY()
            // 横向：贴左 -> 左边不动；贴右（含四个角里的右侧两个）-> 右边不动
            anchorX = if (PetState.isLeft(state)) {
                oldLeft + offX
            } else {
                oldRight - (trackW - offX)
            }
            // 纵向：贴上沿 / 上角 -> 顶边不动；躺侧边 -> 垂直居中（中心不动）；
            //       贴底 / 下角 -> 底边不动
            //
            // ⚠️ 2.1.11 曾把这里改成"侧边一律钉竖直中心"（含立姿侧边），
            //    用户实测后决定不用，回到 2.1.10 这一版（= 基线判据）。
            anchorY = when {
                PetState.isTop(state) -> oldTop + offY
                state == PetState.BORDER_R || state == PetState.BORDER_L -> oldCenterY
                else -> oldBottom - (trackH - offY)
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
        clearPendingSnap()
        syncWindow()
        markRest(anchorX, anchorY)
        // ⚠️ 这里**不要**顺手把位置上报给服务存档：showPet() 是先调本函数
        //    （此时视图还没落位、锚点还是 0,0）再 restore()/resetToRightMid() ——
        //    在这里上报等于每次呼出都往存档里写 (0,0)，下次呼出就落在屏幕左上角。
        //    尺寸变化后的存档由服务侧的 reloadPet() 负责（见那里的 scheduleSave）。
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

    /**
     * 叫醒动画时钟：设置变了（呼吸开关、呼吸倍率…）时由服务调一次。
     *
     * ⚠️ 必须有这个入口。ticker 在"呼吸关掉 + 完全静止"时会**彻底不再排帧**
     *    （见 ticker 里那段说明），此时在 App 里把呼吸打开，不会有任何一帧来重新判断 ——
     *    表现就是用户报的"启用呼吸后需要点击一下人物才进行呼吸"（点一下会排帧，于是又活了）。
     *    `BREATH_DELAY_MS` 是 0，所以醒来这一帧就会直接进入呼吸，也就是"启用后立即呼吸"。
     */
    fun wakeAnimation() {
        choreographer.removeFrameCallback(frameCallback)
        scheduleFrame()
        invalidate()
    }

    /**
     * 延后 [delayMs] 毫秒再排一帧。
     *
     * 用在"呼吸晃动"的启动上：静止满 `BREATH_DELAY_MS` 才呼吸，
     * 而那段时间 ticker 已经空转结束了 —— 不预约一次唤醒就永远不会开始。
     */
    private fun scheduleFrameDelayed(delayMs: Long) {
        choreographer.removeFrameCallback(frameCallback)
        choreographer.postFrameCallbackDelayed(frameCallback, delayMs.coerceAtLeast(0L))
    }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val src = Rect()
    private val dst = RectF()

    private var hopping = false
    private var hopStart = 0L

    /**
     * 这一次点击动画里"弹起那一刻"的气泡通知是否已经发过。
     *
     * 动画帧回调每帧都会经过 t = [TAP_PEAK_T]，用它保证**一次点击只冒一次泡**；
     * 每次 [onTapped] 重新置 false。
     */
    private var bubbleFired = false

    /**
     * 长按压缩：**按住不放就一直往下压**，最深 [PRESS_MAX_SQ]（50%），松手回弹。
     *
     * **四条边都启用**，规则统一为"**吸附边不动，另一侧压过来**"：
     * 贴下边就压上边、贴上边就压下边、贴左右边框时素材是躺倒的，
     * 压的就是"朝屏幕内侧"的那一侧（见 [PetState.attachEdgeOf]）——
     * 贴哪条边由状态决定，旋转由 canvas 负责，绘制代码只有一套。
     *
     * 用户要求："现在只针对吸附在底边和左右下角的情况进行修改点击动画……
     * 底边不动仅上边向下压缩"，随后又要求"把底边的单击和长按动画复制到其他三边，
     * 记得旋转对应角度"。
     *
     * 三点约定（用户确认过）：
     *   · 一按下就立刻开始压（没有"长按判定"的等待），短按也会先压一点点；
     *   · 短按松手 -> **接着播原来的点击动画**（继续压到 [SQ_MAX]、弹起、落回），
     *     衔接方式是把点击动画的时钟往前拨（见 [onTapped]），所以不会跳变；
     *   · 长按（已经压得比 [SQ_MAX] 更深）松手 -> 回弹 + 与压缩程度成正比的弹起，
     *     落地还有一次回弹；这一档**不触发点击互动**。
     */
    private var pressing = false
    private var pressStart = 0L

    /** 松手回弹：从 [releaseFrom] 弹回 0 */
    private var releasing = false
    private var releaseStart = 0L
    private var releaseFrom = 0f
    /** 松手回弹的时长（带弹起时用 [PRESS_JUMP_MS]，让"弹起 + 回弹"同时结束） */
    private var releaseMs = PRESS_RELEASE_MS

    /**
     * 松手时**向上弹起**的高度（像素，0 = 不弹）。
     *
     * 长按松手时按压缩程度给：压得越深弹得越高（见 [PetState.pressJumpFraction]）。
     * 它是**画布平移**，不参与位置记账 —— 所以不会把宠物挪走。
     */
    private var releaseJumpPx = 0f

    /** 弹起落地后要不要回弹；[landSq] 是落地压缩深度（0 = 不回弹） */
    private var landing = false
    private var landStart = 0L
    private var landSq = 0f

    /**
     * **上一次"还在动"的时刻**：所有动画标志与拖动都会把它推到当前时间。
     * 静止满 [BREATH_DELAY_MS] 后开始呼吸晃动（见 [currentBreath]）。
     */
    private var idleSince = 0L

    /**
     * 这一帧是否处于"呼吸晃动"状态：完全静止满 [BREATH_DELAY_MS] 后为 true，
     * 一旦有任何动画或被手指按住就立刻回到 false。
     * 由 ticker 每帧维护，绘制与命中判定统一走 [currentBreath]。
     */
    private var breathing = false

    /** 这次回弹结束时要接上的落地回弹深度（长按按按住时长线性给） */
    private var releaseLandSq = 0f

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
        if (t < TAP_PEAK_T) SQ_MAX * (t / TAP_PEAK_T)
        else SQ_MAX * (1f - (t - TAP_PEAK_T) / (1f - TAP_PEAK_T))

    /** 弹起位移：压扁结束后才起跳，正弦半波 */
    private fun jumpAt(t: Float): Float {
        if (t < TAP_PEAK_T) return 0f
        val u = (t - TAP_PEAK_T) / (1f - TAP_PEAK_T)
        return cellH * HOP_FRACTION * sin(u * Math.PI).toFloat()
    }

    /** 长按压缩量：按住越久压得越深，到 [PRESS_FULL_MS] 压满 [PRESS_MAX_SQ] */
    private fun pressSquash(): Float {
        val held = (SystemClock.uptimeMillis() - pressStart).toFloat()
        val u = (held / PRESS_FULL_MS).coerceIn(0f, 1f)
        return PRESS_MAX_SQ * PetState.easeInArrive(u)
    }

    /** 这一帧实际要画的压缩量（0 = 不压，负值 = 回弹时轻微拉伸） */
    private fun currentSquash(): Float = when {
        hopping -> squashAt(hopPhase())
        pressing -> pressSquash()
        releasing -> releaseFrom * PetState.springBack(
            ((SystemClock.uptimeMillis() - releaseStart).toFloat() / releaseMs)
                .coerceIn(0f, 1f),
            // 用更"软"的摆动系数：回弹只轻微过冲一下（最多约 16% 的拉伸），
            // 不会把人物拉得又高又瘦
            1f
        )
        // 落地回弹：单击轻跳 / 长按弹起落下来那一下（见 [startLanding]）
        landing -> landSq * PetState.landSquash(
            ((SystemClock.uptimeMillis() - landStart).toFloat() / PRESS_LAND_MS)
                .coerceIn(0f, 1f)
        )
        else -> 0f
    }

    /**
     * 这一帧的**弹起位移**（像素，正数 = 朝头的方向，画的时候取负号或正号见 onDraw）。
     *
     *   点一下 -> 轻跳（[jumpAt]，正弦半波）
     *   长按松手 -> 与压缩程度成正比的弹起（[releaseJumpPx]），也是正弦半波，
     *   到点正好落回 0，所以不会残留位移。
     */
    private fun currentJump(): Float = when {
        hopping -> jumpAt(hopPhase())
        releasing && releaseJumpPx > 0f ->
            releaseJumpPx * sin(
                (Math.PI * ((SystemClock.uptimeMillis() - releaseStart).toFloat() / releaseMs)
                    .coerceIn(0f, 1f)).toFloat()
            )
        else -> 0f
    }

    /**
     * **呼吸晃动**波形（-1..1，0 = 不呼吸）。
     *
     * 用户要求："当人物处于吸附 0.5s 后，给人物增加微微的呼吸晃动效果，
     * 原则是素材吸附边不得离开手机被吸附边。"
     *
     * 实现要点：
     *   · 只有"完全静止"（没有任何动画、也没被手指按着）满 [BREATH_DELAY_MS] 才开始；
     *   · 起点严格是 0（见 [PetState.breathWave]），所以开始的那一刻不跳变；
     *   · "不离开吸附边"靠**支点**保证：绘制时绕吸附边缩放、沿吸附边平移，
     *     所以贴边那条边无论怎么呼吸都不会离开屏幕边（见 [squashPivotLocalY]）。
     */
    private fun currentBreath(): Float {
        if (!breathing || !prefs.breathEnabled) return 0f
        val elapsed = (SystemClock.uptimeMillis() - idleSince).toFloat()
        // 周期随「呼吸速度倍率」变（prefs.breathPeriodMs = 基准 / 速度）。
        //
        // ⚠️ 速度只作用在**波形**上，不动 [BREATH_DELAY_MS] 那个启动延迟 ——
        //    用户要的是"呼吸动画播得快慢"，不是"多久之后才开始呼吸"。
        //    把 delay 也一起缩放的话，调到 10 倍速会变成"一静止就猛吸一口气"。
        return PetState.breathWave(elapsed, BREATH_DELAY_MS, prefs.breathPeriodMs)
    }

    /**
     * 压缩的**支点**（画布局部 y，与 [onDraw] 里 `canvas.scale` 的支点严格一致）。
     *
     * 贴顶取格子上缘（屏幕上方不动），其余三种边取下缘 ——
     * 贴左右边框时画布 y 轴指向屏幕的左右，支点在下缘就等于"贴边那条竖边不动"。
     */
    private fun squashPivotLocalY(): Float =
        if (PetState.squashPivotAtCellBottom(state)) cellLocalY() + cellH else cellLocalY()

    /**
     * 松手回弹：从当前压缩量弹回 0，并可带一次**向上弹起**。
     *
     * @param from 松手时的压缩量
     * @param jumpPx 弹起高度（像素）；>0 时用 [PRESS_JUMP_MS] 让"弹起 + 回弹"一起结束
     * @param landSq 落地回弹的深度（只在这次带弹起时用；见 [PetState.landSquashDepth]）
     */
    private fun startSquashRelease(from: Float, jumpPx: Float = 0f, landSq: Float = 0f) {
        if (from <= 0.001f && jumpPx <= 0.5f) {
            releasing = false
            releaseJumpPx = 0f
            return
        }
        releaseFrom = from
        releaseJumpPx = jumpPx
        releaseLandSq = landSq
        releaseMs = if (jumpPx > 0f) PRESS_JUMP_MS else PRESS_RELEASE_MS
        releaseStart = SystemClock.uptimeMillis()
        releasing = true
        choreographer.removeFrameCallback(frameCallback)
        scheduleFrame()
    }

    /**
     * 落地回弹：轻跳 / 长按弹起**落地那一下**压一下再弹回来。
     *
     * 用户要求："长按弹起落地之后加一个回弹效果"、"还有单击也是"，
     * 以及"单击时的回弹效果轻一点；长按落地时的回弹效果与按压时间成正比"。
     * 曲线用 [PetState.landSquash]：快速压下去 → 轻微过冲到拉伸 → 落定，
     * 结束后压缩量严格为 0，不会残留变形。
     *
     * @param sq 落地压缩深度：单击传最轻的 [PRESS_LAND_SQ_TAP]；
     *   长按按住得越久越深（[PetState.landSquashDepth]，按满 = [PRESS_LAND_SQ_MAX]）
     */
    private fun startLanding(sq: Float = PRESS_LAND_SQ_TAP) {
        if (sq <= 0.001f) return
        landSq = sq
        landStart = SystemClock.uptimeMillis()
        landing = true
        choreographer.removeFrameCallback(frameCallback)
        scheduleFrame()
    }

    /** 拖动开始时作废长按压缩；[smooth] 为真时让压缩量平滑收回（不闪） */
    private fun cancelPress(smooth: Boolean) {
        if (!pressing) return
        val d = pressSquash()
        pressing = false
        if (smooth) startSquashRelease(d) else releasing = false
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
     * 用于**上边带边界**的滞回判定 —— 见 [PetState.vertOf]。
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
     * [lastWinX]/[lastWinY]/[lastWinW]/[lastWinH] 这四个缓存**是否可信**。
     *
     * ⚠️ 存在的理由：这四个值原来是"先写缓存、再调 [Host.placeWindow]"，
     *    而 `updateViewLayout` 失败会被 [PetService] 静默吞掉。于是一次失败之后，
     *    **相同坐标会被 [placeWindow] 开头的 early-return 永久挡住** ——
     *    窗口再也不同步，表现是宠物画到窗口外、超出部分点不到。
     *    现在改成"调用成功才更新缓存"；失败就把这个标志置 false，
     *    下次同坐标仍然会重试。
     */
    private var winPlaced = false

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

    /**
     * 息屏时停表省电。
     *
     * ⚠️ 这个字段必须在 [ticker] **之前**声明：ticker.run() 开头要读它。
     */
    private var paused = false

    private val ticker = object : Runnable {
        override fun run() {
            // 息屏期间不要推进任何动画：setPaused(true) 会摘掉帧回调，
            // 但触摸等路径仍可能重新排一帧 —— 这里直接退出，
            // 免得"息屏时动画偷偷往前跑"。
            // 亮屏时 setPaused(false) 会重新排帧，动画从暂停处继续。
            if (paused) return

            var need = false
            val now = SystemClock.uptimeMillis()

            if (tweening) {
                val t = ((now - tweenStart).toFloat() / tweenMs).coerceIn(0f, 1f)
                if (tweenSpring) {
                    // 回弹段：从过冲点"弹"回落点。
                    // springBack 是**相对落点的偏移系数**（1 -> 0，中途为负），
                    // 不是 0->1 的插值系数，所以这里要用 to + (from-to)*k 的写法。
                    // 好处：中途会越过落点到另一侧一点点（"弹一下"），
                    // 而 springBack(1) == 0 保证终点精确 —— 不会留缝。
                    val k = PetState.springBack(t)
                    anchorX = tweenToX + (tweenFromX - tweenToX) * k
                    anchorY = tweenToY + (tweenFromY - tweenToY) * k
                } else {
                    val e = easeOutCubic(t)
                    anchorX = tweenFromX + (tweenToX - tweenFromX) * e
                    anchorY = tweenFromY + (tweenToY - tweenFromY) * e
                }
                if (t >= 1f) {
                    anchorX = tweenToX
                    anchorY = tweenToY
                    tweening = false
                    tweenSpring = false
                    if (bouncePending) {
                        // 第一段（冲到过冲点）结束 -> 弹回落点。
                        // 注意：这一段结束时停在**过冲点**上，还不是落点，
                        // 所以不能在这里 markRest（落点已由 settleInternal 记好）
                        bouncePending = false
                        // spring=true：阻尼摆动，"弹一下"而不是减速停住
                        animateTo(
                            bounceToX, bounceToY, prefs.bounceBackMs.toLong(), spring = true
                        )
                    } else {
                        // 补间走完才换状态，避免中途改尺寸导致的跳变
                        // 到这里才真正停稳 —— 落点就是这里
                        markRest(anchorX, anchorY)
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
            // 呼出/收起滑动：两段式 —— 进场段 + 回弹段
            if (sliding) {
                val t = ((now - slideStart).toFloat() / slideMs).coerceIn(0f, 1f)
                if (slidePhase2Running) {
                    // 回弹段：从过冲点"弹"回落点。
                    //
                    // 用 PetState.springBackGained：中途会荡到**落点另一侧**
                    // （深度 = 弹出高度 × 用户在「呼出回弹」卡片里调的回弹幅度），
                    // 最后精确停在落点上（u=1 严格为 0，所以不会留缝）。
                    val k = PetState.springBackGained(t, prefs.summonSpringGain)
                    anchorX = slideToX + (slideFromX - slideToX) * k
                    anchorY = slideToY + (slideFromY - slideToY) * k
                } else {
                    // 第一段（进出屏幕）：
                    //   呼入 -> easeInArrive（起步最快、一路减速，"啪一下到位"）
                    //   收起 -> smoothstep（两头慢、中段快，"慢慢平移出去"）
                    // 都满足 e(0)=0、e(1)=1，所以两端位置都是精确的。
                    val e = if (slideEntry) PetState.easeInArrive(t) else PetState.smoothstep(t)
                    anchorX = slideFromX + (slideToX - slideFromX) * e
                    anchorY = slideFromY + (slideToY - slideFromY) * e
                }
                if (t >= 1f) {
                    if (slideHasPhase2) {
                        // 第一段（冲到过冲点）结束 -> 收回最终落点。
                        //
                        // 第二段用**专用的短时长**，不复用 bounceBackMs：
                        // 那是边框回弹的"慢慢缩回"用时（默认 360ms），
                        // 拿来收呼出回弹会变成慢吞吞地挪回去，观感不自然。
                        // 回弹要"啪"一下，所以短 + 阻尼摆动（见上面的 springBack）。
                        slideHasPhase2 = false
                        slidePhase2Running = true
                        slideFromX = slideToX
                        slideFromY = slideToY
                        slideToX = slideEndX
                        slideToY = slideEndY
                        slideStart = now
                        slideMs = slideSettleMs
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
                // 压缩结束、开始弹起的那一刻：让思考气泡同步出现
                // （只在"吸附右边框 + 下半屏"时，见 [bubbleAllowed]）
                if (!bubbleFired && t >= TAP_PEAK_T) {
                    bubbleFired = true
                    if (bubbleAllowed()) host?.onJumpStart()
                }
                if (t >= 1f) {
                    hopping = false
                    // 轻跳落地 -> 落地回弹（用户要求"还有单击也是"）
                    startLanding()
                }
                need = true
            }
            if (pressing) {
                // 按住时压缩量还在长；压满之后就不用再重绘了（省帧）
                if (now - pressStart < PRESS_FULL_MS.toLong()) need = true
            }
            if (releasing) {
                val t = ((now - releaseStart).toFloat() / releaseMs).coerceIn(0f, 1f)
                if (t >= 1f) {
                    val hadJump = releaseJumpPx > 0f
                    releasing = false
                    releaseJumpPx = 0f
                    // 弹起落地 -> 落地回弹（深度按这次按住多久给的）
                    if (hadJump) startLanding(releaseLandSq)
                }
                need = true
            }
            if (landing) {
                val t = ((now - landStart).toFloat() / PRESS_LAND_MS).coerceIn(0f, 1f)
                if (t >= 1f) landing = false
                need = true
            }
            // ---- 呼吸晃动：完全静止（= 吸附/落位动画结束）后立刻开始 ----
            // "还在动"包括所有动画以及被手指按着（拖动/长按都算被按住）。
            val moving = hopping || pressing || releasing || landing || sliding ||
                tweening || rotAnimRunning || flipRunning || flipVRunning || dragging
            if (moving) {
                idleSince = now
                breathing = false
            } else {
                // 总开关关掉时不进入呼吸态 —— 否则 ticker 会白转（每帧重绘却什么都没变）
                val wantBreath = prefs.breathEnabled && now - idleSince >= BREATH_DELAY_MS.toLong()
                if (breathing && !wantBreath) {
                    // ⚠️ 中途关掉呼吸：屏幕上是**这一帧的呼吸形变**（吸气/呼气），
                    //    而下面那个分支会"彻底安静、不再排帧"，于是人物就永久停在那一帧
                    //    （用户报的 bug："正在呼吸的时候停用呼吸，人物应回到原来的比例，
                    //     现在是直接停到那一帧"）。所以这里补一帧：
                    //    这一帧里 currentBreath() 已经返回 0（它要求 breathing && 开关为真），
                    //    人物就回到了原来的比例。
                    need = true
                }
                breathing = wantBreath
                if (breathing) {
                    // 只有呼吸这一件事在动时**隔帧重绘**：波形周期 2.6s，
                    // 30fps 完全够看，能省掉一半的绘制。
                    if ((now / 16L) % 2L == 0L) need = true
                } else if (!prefs.breathEnabled) {
                    // 关掉呼吸：彻底安静，不再排帧。
                    // ⚠️ 所以**必须**有 [wakeAnimation] 这个入口：在 App 里重新打开呼吸时，
                    //    不再有任何一帧会来重新判断，表现就是用户报的
                    //    "启用呼吸后需要点击一下人物才进行呼吸"（点一下会排帧，于是又活了）。
                } else {
                    // 还差一会儿才开始呼吸：正好在那一刻再来一帧去启动它
                    scheduleFrameDelayed((BREATH_DELAY_MS - (now - idleSince)).toLong())
                }
            }

            // 只有真的有动画时才重绘。
            // 原来无条件 invalidate()，于是每段动画结束都会多画一帧、
            // 而且和 App 主界面共用主线程的 Choreographer —— 用户反馈
            // "呼出桌宠时上下滑动不如收起时跟手"，少画一帧是一帧。
            if (need) {
                invalidate()
                scheduleFrame()
            } else if (breathing) {
                // 呼吸期间要持续排帧（只是不一定每帧都重绘）
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

    /**
     * 当前这条位移补间是不是**回弹段**（过冲点 -> 落点）。
     *
     * 是的话不用 easeOutCubic 插值，而是走 `PetState.springBack`
     * 的阻尼摆动：会**冲过落点、到另一侧一点点**再贴住 —— 就是"弹一下"。
     * 终点仍然是精确的（`springBack(1) == 0`），不会留缝。
     */
    private var tweenSpring = false

    /**
     * 这次滑动的**回弹段时长**。
     *
     * 呼出/滑回用用户设定的"回弹时长"（与靠边吸附同一个设置），
     * 这样两处的"弹一下"手感一致；默认值只在没显式传时兜底。
     */
    private var slideSettleMs = SLIDE_SETTLE_MS

    /**
     * 这次滑动是**进场**（呼出/滑回屏幕内）还是出场（收起）。
     *
     * 两者手感要求相反：进场要"冲进来然后刹住"（[PetState.easeInArrive]），
     * 出场要"慢慢平移出去"（[PetState.smoothstep]）。
     */
    private var slideEntry = false

    /**
     * **真实静止位置**（素材中心屏幕坐标）—— 最近一次"停稳"的地方。
     *
     * ⚠️ 与 [anchorX]/[anchorY] 是**两个不同的概念**，别混用：
     *   · anchor 是"当前这一帧画在哪"：回弹补间途中它在过冲点上，
     *     收起/呼出动画里它已经跑到屏幕外；
     *   · 这个 rest 是"最终会停在哪"：补间/滑动结束后的落点。
     *
     * 保存位置、以及"收起被打断要滑回哪里"一律用 rest，否则存下去的是中间态 ——
     * 用户反馈的"多次快速点击收起/呼入位置会变化"就是这么来的：
     *   ① 拖动后 400ms 的延迟保存撞上回弹补间 -> 存了过冲点附近的位置；
     *   ② 收起滑完 removePet 又存一次 -> 那时 anchor 已在屏幕外，靠夹取兜底；
     *   ③ 连点时的"滑回哪里"以前是在开始滑出那一刻按当时 anchor 现记的，
     *      若那一刻正在回弹，记下的同样是过冲点。
     * 现在这些路径全部落在同一个 rest 上，整条链路只认"落点"。
     */
    private var restCenterX = 0f
    private var restCenterY = 0f
    private var hasRestCenter = false

    private fun markRest(x: Float, y: Float) {
        restCenterX = x
        restCenterY = y
        hasRestCenter = true
    }

    /** 当前锚点（素材中心屏幕坐标）—— 呼出动画要拿它当滑入目标 */
    fun anchorXNow(): Float = anchorX
    fun anchorYNow(): Float = anchorY

    /** 静止位置（素材中心屏幕坐标）—— 收起被打断时要滑回这里 */
    fun restX(): Float = restCenterX
    fun restY(): Float = restCenterY

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
     * **静止**位置的足迹左上角 —— 一律按 [restCenterX]/[restCenterY] 反算。
     *
     * ⚠️ 保存位置必须走这个，不要用 [footLeft]/[footTop]：后者是"这一帧"，
     * 可能正处在回弹补间中或已被滑到屏幕外。
     * 与 [restore] 的换算严格互逆，所以反复"收起/呼出"不会越漂越远。
     */
    fun restFootLeft(): Int = (restCenterX - centerFromTrackX()).roundToInt()

    fun restFootTop(): Int = (restCenterY - centerFromTrackY()).roundToInt()

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
        markRest(x, y)
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
        markRest(x, y)
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
     * @return false 表示还没记录过静止位置（只有视图刚建好、还没 restore 过才会）
     */
    fun restoreRest(): Boolean {
        if (!hasRestCenter) return false
        forceSettleAt(restCenterX, restCenterY)
        return true
    }

    /** 动画期间不接受触摸，避免"正飞出去又被拖回来" */
    fun isSliding(): Boolean = sliding

    /**
     * 兜底：保证宠物此刻停在屏幕内（只把越界的锚点拉回来，不重新吸附）。
     *
     * ⚠️ 服务层在"呼出时宠物已在、又没在滑动、也没记住静止点"这种极端情况下调用它。
     * **绝不能改成 applySnap()**：那个会按状态重排到"标准位置"
     * （贴右边框 = 右边框**底部**），正是用户反馈的
     * "吸附在右边框多次快速点击呼入收起会瞬移到右下角"。
     */
    fun stayVisible() {
        if (sliding) return
        val b = host?.bounds() ?: return
        val hw = trackW / 2f
        val hh = trackH / 2f
        anchorX = anchorX.coerceIn(hw, (b.width() - hw).coerceAtLeast(hw))
        anchorY = anchorY.coerceIn(hh, (b.height() - hh).coerceAtLeast(hh))
        markRest(anchorX, anchorY)
        syncWindow()
    }

    /**
     * 退出方向：**按实际贴边情况**判断，不再只看状态。
     *
     * 规则：**取最近的那条边**出去（贴底/贴顶/贴左/贴右），悬空时也取最近的。
     * 平手时按 上 -> 下 -> 左 -> 右 的顺序，所以贴底角仍然往下收。
     *
     * ⚠️ 两个必须守住的点，都是踩过的坑：
     *
     * 1. **足迹要用"状态自己的"尺寸与素材中心**，不能用 [trackW]/[trackH] 与
     *    [centerFromTrackX]。那两个在**旋转冻结**期间（`frozen=true`，180ms 动画
     *    还没跑完）仍然是**旧形态**的值 —— 而 state 已经是新形态了。
     *    横竖互换会让边距差 42px、右边缘差 21px，四条边**一条都对不上**。
     * 2. 因此兜底**绝不能是"向下"**。原来四条边都用 `误差 2px 以内` 判定，
     *    全不匹配就 `return (0,1)` —— 于是贴右边框的人物会**竖着往下滑出屏幕**，
     *    也就是用户反馈的"跑到右下角跑出屏幕了"
     *    （快速点按时收起正好落在旋转动画窗口里，就是这个组合）。
     */
    private fun exitVector(): FloatArray {
        val b = host?.bounds() ?: return floatArrayOf(0f, 1f)
        // 用状态自己的足迹：不受 frozen 记账值影响，永远与画面一致
        val foot = footprintOf(state)
        val off = centerOffsetInTrack(state)
        val left = anchorX - off[0]
        val top = anchorY - off[1]
        val right = left + foot[0]
        val bottom = top + foot[1]

        // 到四条边的距离（贴边为 0，压出去为负），取最近的一条
        // —— 判定逻辑在 PetState.exitDir 里，纯函数、有单测
        return PetState.exitDir(left, top, right, bottom, b.width().toFloat(), b.height().toFloat())
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
        // ⚠️ 这里**不记**静止位置：静止位置是"落点"，由 markRest 在吸附/落位时维护。
        // 原来是在这一刻把**当前 anchor** 记成静止点，可如果这一刻正在播回弹补间，
        // anchor 还在过冲点上 —— 连点时就会滑回过冲点、位置也就变了
        // （用户反馈："多次快速点击收起/呼入位置会发生变化"）。
        clearPendingSnap()
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
        startSlidingTo(restX(), restY())
    }

    /**
     * 从**当前位置**滑到 (targetX, targetY)，并带上呼出回弹。
     *
     * 过冲量用固定像素（复用 prefs.bouncePx），不按行程比例 ——
     * 行程可能是"整个屏幕外"，按比例会冲出天文数字。
     */
    private fun startSlidingTo(targetX: Float, targetY: Float) {
        // 目标即落点：先记成静止位置，途中被打断也知道该回哪儿
        markRest(targetX, targetY)
        val fromX = anchorX
        val fromY = anchorY
        val dx = targetX - fromX
        val dy = targetY - fromY
        val len = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        // 过冲量：按行程给（见 PetState.bounceAmp）+ 用户在「呼出回弹」卡片里调的倍率，
        // 总开关关掉时直接按 0 处理（于是没有第二段，只剩一次位置补间）。
        val amp = if (len > 1f && prefs.summonBounceEnabled) prefs.summonPopPx(len) else 0f
        val overX: Float
        val overY: Float
        if (amp > 0f) {
            overX = targetX + dx / len * amp
            overY = targetY + dy / len * amp
        } else {
            overX = targetX
            overY = targetY
        }
        // 两段时长都来自「呼出回弹」卡片的"总时长"（1.0 = 560ms），
        // 按改动前的 320:380 比例切开。
        startSlide(
            fromX, fromY, overX, overY, null, targetX, targetY,
            entry = true,
            settleMs = prefs.summonBackMs,
            entryMs = prefs.summonInMs
        )
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
        markRest(targetX, targetY)
        clearPendingSnap()
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

        // 复用统一实现：从屏幕外滑回落点，并带上呼出回弹（过冲 + 弹回）。
        // entry=true：进场段用"冲进来然后刹住"的曲线（不是收起那种慢慢平移）
        startSlidingTo(targetX, targetY)
    }

    private fun startSlide(
        fromX: Float, fromY: Float, toX: Float, toY: Float, onDone: (() -> Unit)?,
        endX: Float = toX, endY: Float = toY,
        entry: Boolean = false,
        settleMs: Float = SLIDE_SETTLE_MS,
        entryMs: Float = SLIDE_IN_MS
    ) {
        slideFromX = fromX
        slideFromY = fromY
        slideToX = toX
        slideToY = toY
        slideEndX = endX
        slideEndY = endY
        slideHasPhase2 = (endX != toX || endY != toY)
        slideEntry = entry
        slideSettleMs = settleMs
        slidePhase2Running = false
        slideStart = SystemClock.uptimeMillis()
        // 进场比出场短：呼出要"啪一下到位"，收起才是"慢慢平移出去"。
        // 具体时长由「呼出回弹」卡片的"总时长"按比例切开后传进来。
        slideMs = if (entry) entryMs else SLIDE_MS
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

    /**
     * 被触摸打断的吸附补间：记下它的**落点**，点完再补上。
     *
     * 场景：松手吸附的补间要 220ms 位移 + 360ms 回弹，总共约 0.6 秒。
     * 这期间用户点一下宠物想互动 —— `ACTION_DOWN` 会把补间取消
     * （"抓住了就停住"是对的），可如果只是**点一下**（没拖动），
     * 补间就再也没人补完：宠物停在半路，要么跟屏幕边缘留一条缝、
     * 要么越界半张脸卡在边上（用户反馈："点击互动或者吸附后也偶尔会跟屏幕边缘留有缝隙"）。
     */
    private var pendingSnapX = Float.NaN
    private var pendingSnapY = Float.NaN

    private fun holdSnapTarget() {
        when {
            bouncePending -> {
                pendingSnapX = bounceToX
                pendingSnapY = bounceToY
            }
            tweening -> {
                pendingSnapX = tweenToX
                pendingSnapY = tweenToY
            }
        }
    }

    private fun clearPendingSnap() {
        pendingSnapX = Float.NaN
        pendingSnapY = Float.NaN
    }

    /** 点完（或手势被打断）把没走完的吸附补间补上 —— 必须回到贴边落点，不允许停在半路 */
    private fun resumePendingSnap() {
        val tx = pendingSnapX
        val ty = pendingSnapY
        clearPendingSnap()
        if (tx.isNaN() || ty.isNaN()) return
        if (sliding) return
        animateTo(tx, ty)
    }

    /**
     * 平滑移动到目标位置；任何"宠物自己动"的地方都走这里。
     *
     * @param spring `true` = 这条是**回弹段**：不走 `easeOutCubic` 的"减速停住"，
     *   而是用 `PetState.springBack` 的阻尼摆动 —— 冲过落点、荡到另一侧一点点、
     *   最后精确贴住，也就是"弹一下"（用户要求松手回弹也要这个手感）。
     */
    fun animateTo(
        toX: Float,
        toY: Float,
        durationMs: Long = SNAP_MS.toLong(),
        spring: Boolean = false
    ) {
        tweenFromX = anchorX
        tweenFromY = anchorY
        tweenToX = toX
        tweenToY = toY
        tweenSpring = spring
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
        tW: Int, tH: Int,
        offX: Float, offY: Float
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
                overCenterX - offX,
                overCenterY - offY,
                dimX, dimY,
                b.width().toFloat(), b.height().toFloat(),
                minOf(floorX, floorY)
            )
            overCenterX = cl[0] + offX
            overCenterY = cl[1] + offY
        }

        bounceToX = targetCenterX
        bounceToY = targetCenterY
        bouncePending = true
        animateTo(overCenterX, overCenterY)
    }

    /**
     * 旋转动画：在 0 与 ∓90 之间平滑过渡。
     *
     * **只走最短弧**：目标先按 (±)360 规约到与起点同一圈内（差值落在 (-180, 180]），
     * 所以永远不会绕远路（不会出现转 270° 这种）。
     *
     * ⚠️ 差值正好 180° 时**不转**：这只可能是"贴右边框 <-> 贴左边框"，
     * 而这两个形态的图案互为**水平镜像**（`rotate(+90) ∘ mirror ≡ mirror ∘ rotate(-90)`，
     * 见 [PetState.isSideMirrorPair]）—— 换图本身就是镜像，
     * 硬转 180° 只会让人物先立正再倒下，白转一大圈。
     * 这一下交给镜像翻转动画（[startFlip]）即可，观感也更小。
     *
     * @param durationMs 时长（拖动中切换用默认 180ms）
     */
    private fun startRotAnim(from: Float, to: Float, durationMs: Float = ROT_MS) {
        // 最短弧：把差值规约到 (-180, 180]
        var delta = (to - from) % 360f
        if (delta > 180f) delta -= 360f
        if (delta <= -180f) delta += 360f

        if (abs(delta) < 0.01f || abs(delta) > 179f) {
            // 0°：本来就不用转；180°：左右两侧互为水平镜像，直接换图
            shownRotation = to
            rotAnimRunning = false
            // 没真正开始转，必须立刻解冻 —— 否则尺寸会被永久锁在旧朝向
            onRotationDone()
            return
        }
        rotAnimFrom = from
        rotAnimTo = from + delta
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

        if (winPlaced && x == lastWinX && y == lastWinY &&
            bboxW == lastWinW && bboxH == lastWinH
        ) {
            return
        }
        // ⚠️ 只在**调用成功**之后才更新缓存。
        // 原来是把这四个值先写好再调 host.placeWindow()，而 updateViewLayout
        // 失败会被静默吞掉 —— 于是一次失败就把同坐标永久挡住，窗口再也不同步。
        if (host?.placeWindow(x, y, bboxW, bboxH) == true) {
            lastWinX = x
            lastWinY = y
            lastWinW = bboxW
            lastWinH = bboxH
            winPlaced = true
        } else {
            // 失败 -> 让缓存失效，下次同坐标还会重试
            winPlaced = false
        }
    }

    /** 把窗口尺寸同步过来（大小变了之后必须调用，否则宠物会超出窗口点不到） */
    fun syncWindow() {
        winPlaced = false
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
        // 纵向"标准位置"由状态自己决定：
        //   上边组 -> 贴顶
        //   躺侧边（VERT_NONE，纵向自由）、正立变体 -> **垂直居中**
        //   其余（下角 / 贴下沿）-> 贴底
        // ⚠️ 躺侧边**不能**掉进贴底分支：那会让贴右边框的人物"往右下角跑"
        // （用户反馈："复位之后吸附到右边框其他位置，快速点击会顺着右边框往下走"）。
        val trackTop = when (PetState.vertForState(newState)) {
            PetState.VERT_TOP -> 0f
            PetState.VERT_NONE -> ((b.height() - trackH) / 2f)
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
        clearPendingSnap()
        markRest(anchorX, anchorY)
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
        clearPendingSnap()
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
        markRest(anchorX, anchorY)
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
        clearPendingSnap()
        flip = 0f
        flipFrom = 0f
        flipTo = 0f
        lastZone = PetState.ZONE_B
        lastVert = PetState.vertForState(PetState.BORDER_R)
        alignFlipV(PetState.BORDER_R)
        markRest(anchorX, anchorY)
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

        // 反掉点击/长按动画与呼吸晃动的变换（与 onDraw 里的变换严格对应）
        val sq = currentSquash()
        val push = currentJump()
        val br = currentBreath()
        if (abs(sq) > 0.0001f || push > 0.0001f || abs(br) > 0.0001f || hopping) {
            // 与 onDraw 同一套：法线方向 (1-sq)(1+br*BREATH_SQ)、
            // 切向 (1+sq)(1-br*BREATH_TANGENT)，支点在吸附边上。
            // ⚠️ 支点不是素材中心（贴顶是格子上缘、其余是下缘）——
            // 先相对支点反缩放，再换算回"相对素材中心"的偏移，
            // 因为后面的旋转、换格子坐标都以素材中心为参考。
            val nSq = prefs.breathSq
            val swayPx = BREATH_SWAY_PX * (nSq / BREATH_SQ)
            val breathN = br * nSq
            val breathT = -breathN * BREATH_TANGENT
            val sx = (1f + sq) * (1f + breathT)
            val sy = (1f - sq) * (1f + breathN)
            val py = squashPivotLocalY()
            dx = (localX - cx) / sx - br * swayPx
            dy = (localY - py) / sy + (py - cy) - PetState.jumpSignY(state) * push
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

        // alpha 取样。
        //
        // ⚠️ 这里直接对**该状态的位图**采样，不要再去另存一份"预缩放副本"：
        //    Bitmap.getPixel 是单像素读，**O(1)，与位图大小无关** ——
        //    曾经为此维护过一个 scaledCache，收益为零，代价是每状态一份整尺寸
        //    副本（大小拉满时 10 份 x 3.3 MiB ≈ 33 MiB）。u/v 是归一化坐标，
        //    对全分辨率图采样与对缩放图采样结果一致。
        val bmp = runCatching { bitmapFor(state) }.getOrNull() ?: return true
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
        //
        // ⚠️ 但只用外接框的 **75%**（以框中心为基准等比缩小，见 [PetState.hitBox]）：
        // 整个矩形会让"人物旁边明显是空白的地方"也能点中。
        // 用户反馈："感觉可互动面积还是有点大，互动面积等比缩小到现在的 75%。"
        // 缩小后人物轮廓（不透明像素）照旧可点，只是框外侧那一圈空白不再命中。
        //
        // ⚠️⚠️ **四个角落不缩**（`hitBoxScaleFor` 对角落返回 1.0）。用户要求：
        // "吸附至四个角落时的面积不要改动，只改动没有吸附到四个角落情况的"。
        // 角落素材透明缝隙最多，缩小后容易点不中/抓不住。
        val box = PetState.hitBox(
            alphaBoundsCached(state, bmp), PetState.hitBoxScaleFor(state, HIT_BOX_SCALE)
        )
        return bx >= box[0] && bx < box[2] && by >= box[1] && by < box[3]
    }

    /**
     * 取该状态要画的位图（**镜像 / 上下翻转在这里定，不含旋转**）。
     *
     * ⚠️ mirror 与 flipV 都必须是 **state 的纯函数**：
     *   · [PetState.isLeft]   —— 左半屏水平镜像
     *   · [PetState.flipVFor] —— 上边组（上角 / 贴上沿）上下翻转
     *
     * 绝不能用"当前离上沿近不近"这类**运行时标志**来选翻转：
     * artCenterCache / hitBounds 都以 state 为键，翻转一旦不由状态唯一决定，
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
     * 命中判定用的 alpha 外接框，按 state 记忆化。
     *
     * [alphaBounds] 要逐行 `getPixels` 扫全图（约几 ms），而 hitsPet 每次
     * ACTION_DOWN 都会走到这里 —— 原来的写法是每次都重扫，而 [artCenterOf]
     * 早就缓存了同一份结果。这里只记**最近一个 state**：命中判定连续多次
     * 用的是同一个 state，单槽记忆化足够，且不引入新的生命周期风险
     * （位图一变就靠 [clearScaledCache] 清掉）。
     */
    private fun alphaBoundsCached(st: Int, bmp: Bitmap): IntArray {
        if (hitBoundsState == st) {
            val cached = hitBoundsRect
            if (cached != null) return cached
        }
        // alphaBounds 返回 [left, top, right, bottom]；全透明时退回整张图
        val r = alphaBounds(bmp) ?: intArrayOf(0, 0, bmp.width, bmp.height)
        hitBoundsState = st
        hitBoundsRect = r
        return r
    }

    /**
     * 换素材 / 换尺寸时清掉按 state 派生出来的缓存。
     *
     * ⚠️ 这里**不再回收位图** —— 之前会 recycle `scaledCache` 里的缩放副本，
     * 那个缓存已经删掉了（见 hitsPet 里的说明）。现在只清索引，
     * 位图的生命周期统一由 [PetSprite] 与 [PetService] 管。
     */
    private fun clearScaledCache() {
        artCenterCache.clear()
        hitBoundsState = null
        hitBoundsRect = null
    }

    private fun hopOffset(): Float {
        val t = hopPhase()
        return if (t < 0f) 0f else -jumpAt(t)
    }

    /**
     * **锁定互动**（用户要求）：三种状态，**都只在锁定时生效** ——
     *   · 开（[Prefs.MODE_ON]）  ：点击与长按照常，拖动仍被「锁定」挡掉；
     *   · 关（[Prefs.MODE_OFF]） ：任何操作都不响应（不播按压动画、不点击跳跃），形象实心；
     *   · 淡出（[Prefs.MODE_FADE]）：半透明 + 触控穿透（窗口挂 FLAG_NOT_TOUCHABLE，
     *     事件根本到不了这里；这里的 false 只是双保险）。
     *
     * ⚠️ 这里**现读 `prefs`**（[PetView] 自己有 Prefs 实例：`private val prefs by lazy`），
     *    **不要**改成"由服务下发一个标志"：用户实测过"明明关了锁定互动、点它还在跳" ——
     *    标志在某个路径上没被刷新就永久错位；现读 SharedPreferences 是内存缓存，
     *    每次按下读一次的成本可以忽略，但不可能过期。
     */
    private fun interactionLocked(): Boolean =
        prefs.locked && prefs.lockMode != Prefs.MODE_ON

    /** 「锁定」：形象不能被拖动（同样现读，见 [interactionLocked] 的说明） */
    private fun moveLocked(): Boolean = prefs.locked

    /**
     * 这一串手势的 DOWN **是否被我们受理**（触摸闸门放行）。
     *
     * ⚠️ 必须有这个标志：关掉「锁定互动」时 DOWN 直接 `return false`，
     * 但系统仍可能把 UP 送进来，而松手分流在 `pressing == false` 时算出 `held = 0`、
     * `0 <= SQ_MAX` 成立 —— 于是"没按下过"的松手照样触发点击跳跃
     * （用户报的"锁定互动关还能点着跳"就是这个）。UP / MOVE 都要先看它。
     */
    private var downAccepted = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!hitsPet(event.x + winShiftX, event.y + winShiftY)) return false
                // 「锁定互动：关」= 对屏幕上的形象**任何操作都不响应**（用户要求）：
                // 直接返回 false —— 连按压动画都不播，更不会点击跳跃。
                // 现读 prefs，不做服务下发的标志（见 [interactionLocked] 的说明）。
                if (interactionLocked()) return false
                downAccepted = true
                downRawX = event.rawX
                downRawY = event.rawY
                dragOffsetX = 0f
                dragOffsetY = 0f
                dragging = false
                // 按下会取消在途的吸附补间（"抓住了就停住"）。先把落点记下来：
                // 如果只是点一下，松手时要把它补完（见 [pendingSnapX]）。
                holdSnapTarget()
                cancelTween()
                // 长按压缩：**一按下就开始压**（用户确认的节奏）。压多深由按住时间决定；
                // 松手时按"压到多深"分流：浅的接着播点击动画，深的只回弹。
                hopping = false
                releasing = false
                landing = false
                pressing = true
                pressStart = SystemClock.uptimeMillis()
                choreographer.removeFrameCallback(frameCallback)
                scheduleFrame()
                // 记下手指作用点在窗口内的位置 —— 旋转就绕它转
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                // 锁定后**不能拖动**（用户要求）：长了位移也不进拖动分支，
                // 于是这次手势仍然只是"点击 / 长按"。同样是现读 prefs。
                // [downAccepted] 也要看：DOWN 没被受理时 downRawX/Y 是上一次的陈旧值，
                // 拿它算位移会凭空拖起来。
                if (downAccepted && !moveLocked() && !dragging &&
                    (abs(dx) > touchSlop || abs(dy) > touchSlop)
                ) {
                    dragging = true
                    // 真拖动了：以这次拖动为准，丢掉刚才记下的落点
                    clearPendingSnap()
                    // 拖动优先：长按压缩作废，压缩量平滑收回（不闪）
                    cancelPress(smooth = true)
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
                // ⚠️ **没有受理过 DOWN 的 UP 一律不算数**（这就是"锁定互动关还能点着跳"的根因）：
                // 关掉互动时 DOWN 直接返回 false，但系统仍可能把这个手势的 UP 送进来；
                // 而松手分流里 `val held = if (pressing) pressSquash() else 0f` 在
                // pressing=false 时给出 0，`0 <= SQ_MAX` 成立 -> 照样走 onTapped() -> 跳。
                // 所以先看 [downAccepted]：没受理过就什么都不做，并把它复位。
                if (!downAccepted) {
                    dragging = false
                    return false
                }
                if (dragging) {
                    // 固化当前位置，并清零位移 —— 否则 settle 里位移会被算两遍（瞬移）
                    anchorX = curCenterX()
                    anchorY = curCenterY()
                    dragOffsetX = 0f
                    dragOffsetY = 0f
                    cancelPress(smooth = true)
                    settle()
                } else {
                    // 松手分流（用户确认过）：
                    //   · 还没压到 [SQ_MAX]（相当于"点一下"）-> 接着播点击动画
                    //     （压缩量继续走完、再弹起落回），动画时钟按当前压缩量往前拨，
                    //     衔接不跳变，并触发点击互动；
                    //   · 已经压得更深（长按）-> 回弹，并按**压缩程度成正比**向上弹起
                    //     （用户要求："长按之后松手要向上弹起，弹起高度与压缩程度成正比"）。
                    //     仍然不触发点击互动。
                    val held = if (pressing) pressSquash() else 0f
                    val heldMs =
                        if (pressing) (SystemClock.uptimeMillis() - pressStart).toFloat() else 0f
                    pressing = false
                    if (held <= SQ_MAX) {
                        onTapped(held)
                    } else {
                        val jumpPx = cellH *
                            PetState.pressJumpFraction(held, PRESS_MAX_SQ, PRESS_JUMP_FRACTION)
                        // 落地回弹深度与**按压时间成正比**（单击那一档最轻）
                        val landSq = PetState.landSquashDepth(
                            heldMs, PRESS_FULL_MS, PRESS_LAND_SQ_TAP, PRESS_LAND_SQ_MAX
                        )
                        startSquashRelease(held, jumpPx, landSq)
                    }
                    // 点完把被打断的吸附补间补完，绝不停在半路（否则贴边会留缝）
                    resumePendingSnap()
                }
                downAccepted = false
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
        // 进到离上沿 240px 的带子里才是上边组（带边界滞回，见 PetState.vertOf）；
        // 带外一律按下边组算。选完基准才去量距离，于是上下两组共用同一套 A/B/E 判定。
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
        // 这次吸附就是权威落点，丢掉任何"待补的补间"
        clearPendingSnap()

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

        // 目标位置必须用 **want 自己的素材** 算：角落素材与正立素材的格子宽度
        // 不同（约 263 vs 289），拿当前状态的宽度去算，落点会差半个差值 ≈ 13px ——
        // 表现就是"吸附后跟屏幕边缘留一条缝"（反过来则是越界 13px），
        // 而且换素材那一下能看到跳变（闪烁）。
        val foot = footprintOf(want)
        val tW = foot[0]
        val tH = foot[1]
        val wantOff = centerOffsetInTrack(want)
        val wantRot = PetState.rotationForSnap(want)

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
        // ---- 落点：吸附的目标就是"静止位置" ----
        // 后面无论是补间、回弹还是被连点打断，保存与"滑回哪里"都以它为准，
        // 不再看动画途中的 anchor（用户反馈的"位置会变"就出在这儿）。
        markRest(targetCenterX, targetCenterY)
        // 注意：过冲点用的偏移必须是**目标状态**的（见 [centerOffsetInTrack]），
        // 不能拿当前状态的 centerFromTrack* —— 换素材时两者差 ≈13px。

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
            snapWithBounce(
                targetCenterX, targetCenterY, snapSide, snapVert,
                tW, tH, wantOff[0], wantOff[1]
            )
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
            snapWithBounce(
                targetCenterX, targetCenterY, snapSide, snapVert,
                tW, tH, wantOff[0], wantOff[1]
            )
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

    /**
     * 点击（轻跳）：**压扁 → 弹起 → 落回**，并通知服务（服务侧只做视觉反馈）。
     *
     * @param fromSquash 松手那一刻已经压下去的量。长按压缩是"一按下就开始压"，
     *   所以点击动画必须**从这个量接着走**才不跳变 —— 做法是把动画时钟往前拨：
     *   点击动画第一段本来就是"从 0 压到 [SQ_MAX]"。
     */
    private fun onTapped(fromSquash: Float) {
        val t0 = if (SQ_MAX > 0f) {
            (TAP_PEAK_T * (fromSquash / SQ_MAX)).coerceIn(0f, TAP_PEAK_T)
        } else 0f
        hopping = true
        hopStart = SystemClock.uptimeMillis() - (t0 * HOP_MS).toLong()
        bubbleFired = false          // 这一次点击还没冒过泡
        android.util.Log.i("BubbleGeom", "onTapped state=$state fromSquash=$fromSquash")
        choreographer.removeFrameCallback(frameCallback)
        scheduleFrame()
        host?.onTapped()
    }

    // ---------------------------------------------------------------- 思考气泡

    /**
     * 思考气泡的锚点：
     * `[人物盒中心X, 人物盒中心Y, 人物正立素材宽W, side, mirror, flipV, 旋转角]`。
     *
     *   · 尺寸基准 W = 人物**正立素材**的屏幕宽度（那张 1024×875 就是"包围矩形 A"）；
     *   · `side` = 气泡摆素材坐标系的哪一侧（[PetState.bubbleSide] 按用户规则算，含条件1/条件2）；
     *   · `mirror` / `flipV` / 旋转角 = 人物**素材**当前的变换（气泡要走同一条链）。
     *
     * ⚠️ 用**正立素材**而不是当前素材来定尺寸：气泡大小只应跟人物大小有关，
     * 不能因为"角落 / 正立"两套素材尺寸不同而变。
     */
    fun bubbleAnchor(): IntArray {
        val s = sprite ?: return IntArray(0)
        val b = host?.bounds() ?: return IntArray(0)
        val screenH = b.height().toFloat()
        val screenW = b.width().toFloat()
        val up = PetState.scaledSize(s.uprightW, s.uprightH, screenH * sizeFrac)
        val w = up[0].toFloat()
        val mirror = PetState.needsMirror(state)
        val flipV = PetState.flipVFor(state)
        // ⚠️ 两个旋转角必须分开：
        //   spriteRot —— **人物**的吸附旋转角，决定"气泡摆在人物的哪一侧"（偏移用这个）
        //   bubbleRot —— **气泡图案**自己的旋转角（右下角另有规定，见 PetState.bubbleRotation）
        // 2.1.36 把 bubbleRot 误用到了偏移上，于是右下角气泡被摆到人物**左下**去了。
        val spriteRot = PetState.rotationForSnap(state)
        val bubbleRot = PetState.bubbleRotation(state, spriteRot)

        // 条件2：按默认（素材右侧）摆法时，气泡窗口的最上沿落在哪
        val size = BubbleView.windowSize(w, bubbleRot)
        val offDefault = BubbleView.screenOffset(
            w, PetState.BUBBLE_SIDE_RIGHT, mirror, flipV, spriteRot
        )
        val defaultTopY = curCenterY() + offDefault[1] - size[1] / 2f

        val side = PetState.bubbleSide(
            state, curCenterX(), curCenterY(), screenW, screenH, defaultTopY
        )
        return intArrayOf(
            curCenterX().roundToInt(), curCenterY().roundToInt(), up[0], side,
            if (mirror) 1 else 0, if (flipV) 1 else 0,
            spriteRot.toInt(), bubbleRot.toInt(),
            // 人物**素材矩形**在屏幕上的位置（供"气泡不得进入"这条原则用）
            curTrackLeft().roundToInt(), curTrackTop().roundToInt(), trackW, trackH
        )
    }

    /**
     * 能不能冒泡 —— 用户 2026-10-16 改为：**推广到所有吸附状态**。
     *
     * 之前"当且仅当吸附右边框 + 下半屏"是旧限制；现在每个吸附位都能冒泡，
     * 位置/朝向由 [PetState.bubbleSide] 按规则算（右侧框那两个"条件"就是
     * 用来替代旧的"下半屏"限制的：人物太靠上时气泡自动改摆左侧）。
     *
     * ⚠️ 仍然只在**稳定吸附状态**下冒泡：拖拽 / 滑动过程中 state 会变，那会儿不冒。
     */
    private fun bubbleAllowed(): Boolean {
        val ok = PetState.isSnapped(state)
        android.util.Log.i("BubbleGeom", "decide state=$state snapped=$ok center=(${curCenterX()},${curCenterY()})")
        return ok
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

        // 点击/长按反馈 + 呼吸晃动：都在人物自己的坐标系里做
        val sq = currentSquash()
        val push = currentJump()
        val br = currentBreath()
        if (abs(sq) > 0.0001f || push > 0.0001f || abs(br) > 0.0001f || hopping) {
            // 压缩：沿"吸附边"的**法线** (1-sq)、切向 (1+sq)；
            // 呼吸：同样绕吸附边缩放（幅度极小），再沿吸附边平移一点点做出晃动。
            //
            // ⚠️ 四条边共用这一套（贴底/贴顶/贴左右边框）：贴左右边框时素材是旋转
            // 90° 画的，画布的 y 轴正好指向屏幕的左右方向，所以法线在画布上仍是 y。
            // 支点见 [squashPivotLocalY] —— 压缩与呼吸都绕它做，
            // **吸附边因此永远不离开屏幕边**（用户硬要求）。
            // 同样必须与 hitsPet 里的逆变换完全一致。
            //
            // ⚠️ 呼吸幅度必须先乘 [BREATH_SQ] 再乘切向比例：
            // 直接写 `1 - br * BREATH_TANGENT` 会得到 ±35% 的横向缩放（踩过这个坑，
            // 实测人物一顿一顿地变大变小，而不是"微微呼吸"）。
            // 幅度与摆动都按用户在「呼吸」卡片里调的倍率等比放大。
            val nSq = prefs.breathSq
            val swayPx = BREATH_SWAY_PX * (nSq / BREATH_SQ)
            val breathN = br * nSq
            val breathT = -breathN * BREATH_TANGENT
            val sx = (1f + sq) * (1f + breathT)
            val sy = (1f - sq) * (1f + breathN)
            canvas.scale(sx, sy, cx, squashPivotLocalY())
            // 弹起方向：背离吸附边、朝屏幕内侧，同为画布 y 轴上的符号
            //（贴顶往下弹，其余三种往上/朝屏幕内弹）。
            // 呼吸则沿画布 x 轴（= 沿吸附边）滑动，绝不垂直离开。
            canvas.translate(br * swayPx, PetState.jumpSignY(state) * push)
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
        // 曾经这里是先 Bitmap.createScaledBitmap 出"格子尺寸副本"再画：
        // 那个调用在 CPU 上重采样并**分配一块新位图**，缓存 key 含 cellW/cellH，
        // 所以进 A 区（素材纵横比变化 -> cellW 变）就会 cache miss，于是在拖拽的
        // 绘制里同步做一次大图重采样 —— 用户反馈的"拖到 A 区切换素材明显卡顿"
        // 就是它；反复 miss 还反复分配大块内存。那个缩放缓存后来整块删掉了
        // （连命中判定也不再需要它，见 hitsPet 的说明）。
        //
        // drawBitmap(bmp, src, dst, paint) 由 GPU 采样，paint 已带
        // FILTER_BITMAP_FLAG，质量一致，但**不再创建任何位图**。
        // ⚠️ 必须判 isRecycled。
        //
        // 这里用的是 PetSprite 持有的**原图**；收起桌宠时服务销毁、
        // 那批位图会被回收，而窗口往往还会再走一帧 traversal ——
        // 直接画就会抛 "Canvas: trying to use a recycled bitmap"，
        // 整个进程崩溃（用户看到的"点收起把 APP 关了"）。
        // 早先那个缩放副本自带这个检查，改成画原图时漏掉过，别再把判断删了。
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

    fun setPaused(p: Boolean) {
        if (paused == p) return
        paused = p
        if (p) {
            choreographer.removeFrameCallback(frameCallback)
        } else {
            // ⚠️ 亮屏时**必须重新排帧**，不能只 invalidate() 一次。
            //
            // 息屏摘掉了 Choreographer 回调，而 ticker 才是所有动画的驱动源。
            // 只 invalidate 的话 ticker 再也不会跑：
            //   · 滑出动画（收起）永远停在半路 -> sliding 一直为 true，
            //     而 removePet() 在滑出完成回调里，于是**桌宠卡在屏幕上收不掉**
            //   · 呼入 / 旋转 / 翻转 / 压缩 同样卡在中间态
            // 要等下一次触摸才恢复。呼出看门狗只覆盖 slide-in，救不了收起这条。
            //
            // 无条件排一帧即可：ticker 自己会在没有动画且不呼吸时停止排帧。
            invalidate()
            scheduleFrame()
        }
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

    /**
     * 减速曲线（旋转/翻转/轻跳/第一段补间都用它）。
     *
     * 与呼出进场段用的是**同一条**曲线，所以直接委托给 [PetState.easeInArrive] ——
     * 曲线定义只保留一份，免得以后调手感时两边不一致。
     */
    private fun easeOutCubic(t: Float): Float = PetState.easeInArrive(t)

    companion object Geometry {
        private const val FRAME_MS = 16L
        private const val HIT_ALPHA_MIN = 16

        /**
         * 非角落状态的可互动面积 = 素材 alpha 外接框的 **75%**
         * （以框中心为基准等比缩小，见 [PetState.hitBox]）。
         *
         * 用户要求："感觉可互动面积还是有点大，互动面积等比缩小到现在的 75%。"
         * 只影响"透明像素靠外接框兜底"那一档：人物轮廓本身照旧可点。
         *
         * ⚠️ **四个角落不缩**（[PetState.hitBoxScaleFor] 对角落返回 1.0）。
         */
        private const val HIT_BOX_SCALE = 0.75f
        private const val SNAP_MS = 220f

        /** 收起（滑出）的时长：要"慢慢平移出去" */
        private const val SLIDE_MS = 420f

        /**
         * 呼出（滑入）第一段的时长。
         *
         * 比收起短：进场行程很长（从屏幕外到落点），用 420ms 时末段几乎是原地飘，
         * 用户反馈"弹起的滞空时间有点长"。缩短后是"冲进来然后立刻刹住"。
         */
        private const val SLIDE_IN_MS = 320f

        /**
         * 呼出回弹第二段（过冲点 -> 落点）的时长。
         *
         * 阻尼摆动要能看清"弹一下"，但又不能拖 —— 210ms 时最后 40% 几乎不动，
         * 观感就是**滞空**，所以压到 150ms：一个来回干净利落。
         */
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

        /**
         * 点击动画里"**压到最扁**"的时刻（占整段的比例）。
         *
         * 前 30% 一路压扁、之后才是弹起（见 [squashAt] / [jumpAt]）。
         * 气泡要与"弹起"同步出现，所以三处（压缩曲线 / 弹起曲线 / 气泡通知）
         * 共用这一个常量，改一处不会失配。
         */
        private const val TAP_PEAK_T = 0.3f

        /** 点击时最大压缩比例（0.22 = 压掉 22% 的厚度） */
        private const val SQ_MAX = 0.22f

        /**
         * 长按能压到的最深比例：**50%**（用户要求"最大压缩至 50%"）。
         *
         * ⚠️ 窗口包络必须按这个值留余量（见 [SQ_ENV_MAX]）：压扁时横向会同步
         * 撑大 (1+sq) 倍，压到一半就是 1.5 倍宽 —— 包络不够就会被窗口裁掉。
         */
        private const val PRESS_MAX_SQ = 0.5f

        /** 长按压满（到 [PRESS_MAX_SQ]）所需时间：按住越久压得越深 */
        private const val PRESS_FULL_MS = 1100f

        /** 松手回弹时长（不带弹起时） */
        private const val PRESS_RELEASE_MS = 300f

        /**
         * 长按松手时**向上弹起的最大高度**占素材高度的比例（压满时）。
         *
         * 用户要求："长按之后松手要向上弹起，弹起高度与压缩程度成正比。"
         * 实际高度 = 这个比例 × (压缩量 / 压满量) × cellH，见
         * [PetState.pressJumpFraction]。
         *
         * ⚠️ 窗口上下的余量必须按它留（见 [HOP_PAD_FRACTION]）：
         * 弹起是画布平移，余量不够人物顶部就会被窗口裁掉。
         */
        private const val PRESS_JUMP_FRACTION = 0.28f

        /** 长按松手"弹起 + 回弹"的总时长（弹起是正弦半波，到点正好落回 0）。
         *
         * 实测（把这一段临时放慢到 3s 后逐帧量）：压满松手后人物顶部最高到
         * 离落点 **68~71px**（= 0.28 × 247 的等比弹起），静止后与松手前**零像素差**。
         */
        private const val PRESS_JUMP_MS = 380f

        /**
         * 落地回弹的压缩深度：**单击**用这个最轻的值。
         *
         * 用户要求："单击时的回弹效果轻一点；长按落地时的回弹效果与按压时间成正比。"
         * 长按从它起步、随按住时间线性加深到 [PRESS_LAND_SQ_MAX]
         * （见 [PetState.landSquashDepth]）。
         */
        private const val PRESS_LAND_SQ_TAP = 0.07f

        /** 长按**按满**时的落地回弹深度（按压时间越长越深） */
        private const val PRESS_LAND_SQ_MAX = 0.14f

        /**
         * 落地回弹时长（压下去 + 弹回来）。
         *
         * 实测（把这一段临时放慢到 3s 后逐帧量宽度，正常 289px、压 14% 时 329px）：
         *   单击：301 -> **323** -> 316 -> 288（压扁 → 回弹 → 落定）
         *   长按：421 -> **326** -> 315 -> 288（弹起落地 → 回弹 → 落定）
         * 两者结束后都与初始画面**零像素差**。
         */
        private const val PRESS_LAND_MS = 260f

        /**
         * 吸附静止多久之后开始**呼吸晃动**（用户后来改成 0：吸附动画一结束就开始，
         * 不再等 0.5s）。留着这个常量是为了将来想加延迟时只改一处。
         */
        private const val BREATH_DELAY_MS = 0f

        /**
         * 呼吸周期基准值已挪到 [Prefs.BREATH_PERIOD_BASE_MS] ——
         * 它现在是**用户可调**的（「呼吸速度倍率」），必须与 prefs 里的默认值
         * 用同一个真值，否则"恢复默认"会和这里的常量对不上。
         * 实际周期见 [Prefs.breathPeriodMs]。
         */

        /**
         * 呼吸幅度的**兜底**值（占素材高度）。
         *
         * 实际用的是 `prefs.breathSq`（用户在「呼吸」卡片里调，1.0 = 现在的 1.3 倍），
         * 这里只在 prefs 不可用时兜底。
         *
         * ⚠️ 无论放大还是缩小，绘制都是**绕吸附边**做的（见 [squashPivotLocalY]），
         * 所以贴边那条边永远不动 —— 这就是"素材吸附边不得离开手机被吸附边"。
         */
        private const val BREATH_SQ = 0.015f

        /**
         * 呼吸时切向的反向缩放比例（吸气变高就收窄一点，体积感）。
         *
         * ⚠️ 这是**比例**，乘的是已经缩放过的法线幅度（`br * BREATH_SQ`），
         * 实际切向只有 ±0.53%（≈±1.3px）。曾经误写成直接乘 `br`，
         * 结果横向缩放到了 ±35%，人物一顿一顿地变大变小。
         */
        private const val BREATH_TANGENT = 0.35f

        /**
         * 呼吸时**沿吸附边**的平移幅度（像素）。
         *
         * 只沿吸附边滑动、绝不垂直离开 —— 这样"吸附边不离开屏幕边"天然成立。
         */
        private const val BREATH_SWAY_PX = 1.5f

        /**
         * 窗口上下余量按哪个比例算 = 轻跳与长按弹起里**更大的那个**。
         *
         * `topPad()` / `bottomPad()` 与 `windowRectFor()` 都用它 ——
         * 三处必须一致，否则呼出时窗口初始尺寸和随后算出来的不一样，会闪一下。
         *
         * ⚠️ `const` 的初值必须是编译期常量，所以不能写 `maxOf(...)`；
         * 这里直接取长按弹起的比例（0.28 > 轻跳的 0.08）。**若以后把
         * [HOP_FRACTION] 调得比 [PRESS_JUMP_FRACTION] 还大，必须回来改这一行。**
         */
        private const val HOP_PAD_FRACTION = PRESS_JUMP_FRACTION

        /**
         * 窗口包络里预留的**最大压缩比例**。
         *
         * `cacheSizes()` 与 `windowRectFor()` 都用它 —— 两处必须完全一致，
         * 否则呼出时窗口初始尺寸与随后算出来的不一样，会闪一下。
         */
        private const val SQ_ENV_MAX = PRESS_MAX_SQ

        /** 窗口给"轻跳"和四角旋转留的余量，避免边缘被切 */
        const val HOP_PAD_X = 8

        /**
         * 轻跳余量的最小保底值。
         *
         * 上下两头**用同一个值**（见 [bottomPad]）：弹起方向永远朝屏幕内侧，
         * 贴上沿的宠物是向下弹的，底部同样需要 cellH×8% 的余量。
         */
        const val HOP_PAD_MIN = 8

        /**
         * 服务在 addView 前算初始窗口用。
         *
         * @param footLeft/footTop **存档的足迹左上角**；没有存档时传 null，
         *   这时才退回"该状态的标准位置"。
         *
         * ⚠️ 两个坑都在这里（都会表现成"每次呼出闪一下 / 顺着边框往下走一节"）：
         *
         *   ① **有存档就必须按存档摆窗口**。窗口是 addView 时就定好位置的，
         *      随后 `restore() → placeWindow()` 才把它挪到正确位置 ——
         *      中间那一帧宠物就画在"标准位置"上。
         *   ② **标准位置里躺侧边(纵向自由)必须是垂直居中**，绝不能贴底：
         *      贴右边框的人物标准位置若算成屏幕底部，呼出时就会先在右下角闪一下
         *      （用户反馈："复位之后吸附到右边框其他位置，快速点击收起呼入，
         *      就会顺着右边框往下走"）。
         *
         * 包络公式与 [cacheSizes] **完全一致**（含两套素材的并集）——
         * 只按当前状态算的话，角落状态会算出 360 而 cacheSizes 是 380，
         * 于是每次呼出都要先按小窗口画一帧再 resize，同样会闪。
         */
        fun windowRectFor(
            s: PetSprite,
            state: Int,
            sizeFrac: Float,
            screen: Rect,
            footLeft: Int? = null,
            footTop: Int? = null
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

            // 与 cacheSizes 用同一套包络公式（含并集），保证不裁切、且尺寸一致
            val diag = kotlin.math.hypot(cell[0].toDouble(), cell[1].toDouble()).toFloat()
            var envW = maxOf(diag, cell[0] * (1f + SQ_ENV_MAX))
            var envH = maxOf(diag, cell[1] * (1f + SQ_ENV_MAX))
            run {
                val other = if (PetState.isZoneless(state)) PetState.CORNER_BR else PetState.BORDER_R
                if (other != state) {
                    val co = PetState.scaledSize(
                        s.naturalWidth(other), s.naturalHeight(other), screenH * sizeFrac
                    )
                    val d2 = kotlin.math.hypot(co[0].toDouble(), co[1].toDouble()).toFloat()
                    envW = maxOf(envW, d2, co[0] * (1f + SQ_ENV_MAX))
                    envH = maxOf(envH, d2, co[1] * (1f + SQ_ENV_MAX))
                }
            }
            val pad = (cell[1] * HOP_PAD_FRACTION).toInt() + HOP_PAD_MIN
            val bboxW = envW.toInt() + HOP_PAD_X * 2
            val bboxH = envH.toInt() + pad + pad

            // 足迹盒在包络里的位置（居中，与 cacheSizes 一致）
            val footOffX = (bboxW - HOP_PAD_X * 2 - tw) / 2
            val footOffY = (bboxH - pad - pad - th) / 2

            val x: Int
            val y: Int
            if (footLeft != null && footTop != null) {
                // 有存档：窗口位置直接由存档反算 —— 与随后 restore→placeWindow
                // 得到的结果**逐像素相同**，所以不会有"先出现在别处再被挪走"
                //
                // 存档本身可能越界（老版本存过屏幕外坐标），那就会先按越界位置画一帧、
                // 再被 restore() 的夹取拉回来 —— 同样是一闪。这里按"整只宠物留在屏幕内"
                // 先夹一次（与 restore 的夹取语义一致：素材中心≈足迹中心）。
                val fl = footLeft.coerceIn(0, (screen.width() - tw).coerceAtLeast(0))
                val ft = footTop.coerceIn(0, (screen.height() - th).coerceAtLeast(0))
                x = fl - HOP_PAD_X - footOffX
                y = ft - pad - footOffY
            } else {
                val trackLeft = if (PetState.isLeft(state)) 0 else screen.width() - tw
                val trackTop = when (PetState.vertForState(state)) {
                    PetState.VERT_TOP -> 0
                    // 躺侧边：纵向自由 -> 标准位置是**垂直居中**
                    PetState.VERT_NONE -> (screen.height() - th) / 2
                    else -> if (PetState.isZoneless(state)) (screen.height() - th) / 2
                    else screen.height() - th
                }
                x = trackLeft - HOP_PAD_X - footOffX
                y = trackTop - pad - footOffY
            }

            return intArrayOf(x, y, bboxW, bboxH)
        }
    }
}
