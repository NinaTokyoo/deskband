package com.dsh.deskpet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 「当前形象」二级菜单的**视口**：一页显示固定行数，内容更高时用手指在里面拖动滚动，
 * 右侧画一根常显的**滚动指示条**。
 *
 * ## 为什么不用 ScrollView
 *
 * 菜单是嵌套在页面 ScrollView 里的，两个竖向滚动容器叠在一起时**外层总会先把拖动
 * 抢走**：实测内层 `canScrollVertically=true`、内容 1331px > 视口 810px，但在菜单上
 * 滑动，滚的却是整页 —— 日志里我们的手势直接收到了 `ACTION_CANCEL`。
 * 根因是外层在 `onInterceptTouchEvent` 里比内层先拿到事件；而
 * `requestDisallowInterceptTouchEvent` 又**不能对自己调**（对自己调等于自己也放弃拦截，
 * 那就彻底滚不动了），所以"内层 ScrollView + 自己 disallow"这条路走不通。
 *
 * ## 于是自己拿住这件事
 *
 * 1. `ACTION_DOWN` 起就只对**父链**调 disallow —— 外层永远抢不走，而自己照常判定；
 * 2. 竖直位移超过 touchSlop 才接管手势（在那之前不拦，行的**点击**照常触发）；
 * 3. 接管后按位移平移内容，`scrollY` 夹在 `[0, content - viewport]`；
 * 4. 指示条自己画（见 [dispatchDraw]），颜色由外部按当前主题给（[indicatorColor]），
 *    内容装得下时不画。
 */
class SkinMenuViewport @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density

    private var dragging = false
    private var lastY = 0f
    private var scrollY = 0

    /** 指示条颜色（由 MainActivity 按当前强调色 / 明暗模式下发） */
    var indicatorColor: Int = 0x805FD3F3.toInt()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barRect = RectF()

    private val barW = 3f * density

    /**
     * 指示条两端的内缩（dp）：**上下各留 [barPadV]，右沿留 [barPadH]**。
     *
     * ⚠️ 上下都要真的留出来。原来只在起点加了内缩、`travel` 没扣掉两端，
     * 于是滑到底时条的**下端会顶到视口最下沿**，正好落在菜单描边的**圆角弧线之外**，
     * 露在外面（用户截图反馈）。
     * 右沿留 6dp 而不是 2dp：圆角半径是 10dp，条离右沿太近时它的下端仍会
     * 探出弧线 —— 6dp 处弧线距底边只剩 0.8dp，配 4dp 的底端内缩就稳稳在里面了。
     */
    private val barPadV = 4f * density
    private val barPadH = 6f * density
    private val barRadius = 1.5f * density
    private val minThumb = 24f * density

    private val content: View? get() = if (childCount > 0) getChildAt(0) else null

    private fun maxScroll(): Int = ((content?.height ?: 0) - height).coerceAtLeast(0)

    /**
     * ⚠️ 内容必须按 **UNSPECIFIED** 量。
     *
     * 视口高度被我们限制成"一页 5 行"（EXACTLY），若照 FrameLayout 的默认做法把
     * 这个规格传给子视图，它是 AT_MOST(视口高) —— 内容会被**夹到视口那么高**，
     * `maxScroll()` 恒为 0，于是永远滚不动（实测就是这个现象）。
     * 所以量内容时放开高度、自己再按父规格定自己的高度；
     * 摆位交给默认的 FrameLayout.onLayout（它用子视图的 measuredHeight），
     * 超出视口的部分由 clipChildren 裁掉。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val c = content
        c?.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val contentH = c?.measuredHeight ?: 0
        val hSize = MeasureSpec.getSize(heightMeasureSpec)
        val myH = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> hSize
            MeasureSpec.AT_MOST -> minOf(hSize, contentH)
            else -> contentH
        }
        setMeasuredDimension(width, myH)
    }

    /** 重建菜单时把视口拉回顶部（换角色 / 切明暗都会重建） */
    fun resetScroll() {
        scrollY = 0
        dragging = false
        content?.translationY = 0f
        invalidate()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // ⚠️ 只对**父链**调：对自己调会连自己的 onInterceptTouchEvent 一起跳过，
                //    那样连"判定要不要接管手势"都做不了
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = false
                lastY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && maxScroll() > 0 && abs(ev.y - lastY) > slop) {
                    dragging = true
                    lastY = ev.y
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val dy = ev.y - lastY
                lastY = ev.y
                scrollY = (scrollY - dy.toInt()).coerceIn(0, maxScroll())
                content?.translationY = -scrollY.toFloat()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = dragging
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return was
            }
        }
        return dragging
    }

    /** 内容比视口高时，在右侧画一根指示条（长度按可视比例、位置按滚动进度） */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val max = maxScroll()
        val contentH = content?.height ?: 0
        if (max <= 0 || height <= 0 || contentH <= 0) return
        val thumbH = (height.toFloat() * height / contentH).coerceAtLeast(minThumb)
        // ⚠️ 行程要扣掉**两端**的内缩，否则滑到底时条的下端会越过下边界
        //    （那里正好是菜单描边的圆角，会露在弧线外面）
        val travel = (height - thumbH - barPadV * 2).coerceAtLeast(0f)
        val top = barPadV + travel * (scrollY.toFloat() / max)
        barRect.set(
            width - barPadH - barW, top,
            width - barPadH, top + thumbH
        )
        paint.color = indicatorColor
        canvas.drawRoundRect(barRect, barRadius, barRadius, paint)
    }
}
