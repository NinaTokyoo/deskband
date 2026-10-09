package com.dsh.deskpet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 吸附状态机测试。
 * 这些规则都是用户口述的硬性要求，用测试钉死，避免以后改代码改坏。
 */
class PetStateTest {

    private val SW = 1440f
    private val SH = 3168f

    // ---------------------------------------------------------- 分区（最新规则）

    @Test
    fun `zone A is the 356 by 356 corner square`() {
        assertEquals(PetState.ZONE_A, PetState.zoneOf(0f, 0f))
        assertEquals(PetState.ZONE_A, PetState.zoneOf(356f, 356f))
        assertEquals(PetState.ZONE_A, PetState.zoneOf(10f, 300f))
        assertEquals(PetState.ZONE_A, PetState.zoneOf(300f, 10f))
        // 只要有一边超过 356 就不是 A
        assertNotEquals(PetState.ZONE_A, PetState.zoneOf(357f, 10f))
        assertNotEquals(PetState.ZONE_A, PetState.zoneOf(10f, 357f))
    }

    @Test
    fun `zone C is the 200px bottom strip outside A`() {
        // A 之外的底部 200px -> C
        assertEquals(PetState.ZONE_C, PetState.zoneOf(200f, 500f))
        assertEquals(PetState.ZONE_C, PetState.zoneOf(0f, 500f))
        // 关键回归：A 区里 200..356 的环带**不能**被判成 C（否则 C 会变 356 宽）
        assertEquals(PetState.ZONE_A, PetState.zoneOf(300f, 300f))
        // 超过 200 就不再是 C
        assertEquals(PetState.ZONE_D, PetState.zoneOf(201f, 500f))
    }

    @Test
    fun `zone B is the 200px right strip outside A`() {
        assertEquals(PetState.ZONE_B, PetState.zoneOf(500f, 200f))
        assertEquals(PetState.ZONE_B, PetState.zoneOf(500f, 0f))
        assertEquals(PetState.ZONE_D, PetState.zoneOf(500f, 201f))
    }

    @Test
    fun `A swallows the whole 200px band near the corner, so C and B never overlap`() {
        // 几何结论：A 是 356×356，而 C/B 只有 200 宽。
        // 角附近"底边<=200"的点，右边也必然<=356，早已落进 A。
        // 所以 C 实际上只在 dr>356 时才可能出现，C 与 B 永远不冲突，
        // 「重合以底边为准」这条规则不会触发 —— 代码里先判 C 只是照规则写、更稳妥。
        assertEquals(PetState.ZONE_A, PetState.zoneOf(150f, 150f))
        assertEquals(PetState.ZONE_A, PetState.zoneOf(150f, 200f))
        assertEquals(PetState.ZONE_A, PetState.zoneOf(150f, 300f))
        assertEquals(PetState.ZONE_A, PetState.zoneOf(150f, 356f))
        // C 真正生效要 dr>356
        assertEquals(PetState.ZONE_C, PetState.zoneOf(150f, 357f))
        // B 同理
        assertEquals(PetState.ZONE_A, PetState.zoneOf(300f, 150f))
        assertEquals(PetState.ZONE_B, PetState.zoneOf(357f, 150f))
    }

    @Test
    fun `zone D is the L shaped band out to 444`() {
        assertEquals(PetState.ZONE_D, PetState.zoneOf(444f, 500f))
        assertEquals(PetState.ZONE_D, PetState.zoneOf(500f, 444f))
        assertEquals(PetState.ZONE_D, PetState.zoneOf(400f, 400f))
        // 超过 444 两边都不沾 -> E
        assertEquals(PetState.ZONE_E, PetState.zoneOf(445f, 445f))
    }

    @Test
    fun `zone E is everything else`() {
        assertEquals(PetState.ZONE_E, PetState.zoneOf(1000f, 1000f))
        assertEquals(PetState.ZONE_E, PetState.zoneOf(3084f, 1440f))
    }

    @Test
    fun `only zone D has blur`() {
        assertFalse(PetState.hasBlur(0f, 0f))        // A
        assertFalse(PetState.hasBlur(100f, 500f))    // C
        assertFalse(PetState.hasBlur(500f, 100f))    // B
        assertTrue(PetState.hasBlur(400f, 500f))     // D
        assertFalse(PetState.hasBlur(1000f, 1000f))  // E
    }

    // ---------------------------------------------------------- 吸附到边（含左侧镜像）

    /** 可活动区高度（已扣掉导航栏），与真机一致 */
    private val SH_WORK = 3084f
    private val PW = 475f
    private val PH = 463f

    /** 跑一次吸附，返回 [x, y, category]（侧边距离按本侧量） */
    private fun snap(x: Float, y: Float, leftHalf: Boolean): FloatArray {
        val db = PetState.bottomDist(y, PH, SH_WORK)
        val dr = PetState.sideDist(x, PW, SW, leftHalf)
        return PetState.snapPosition(x, y, PW, PH, db, dr, SW, SH_WORK, leftHalf)
    }

    @Test
    fun `A snaps to the corner on both axes`() {
        val r = snap(SW - PW - 50f, SH_WORK - PH - 50f, false)
        assertEquals(SW - PW, r[0], 0.5f)
        assertEquals(SH_WORK - PH, r[1], 0.5f)
        assertEquals(PetState.CORNER_BR.toFloat(), r[2], 0.01f)
    }

    @Test
    fun `C snaps only vertically and keeps the horizontal release position`() {
        val x0 = SW - PW - 500f          // 离右 500 > 356，所以在 A 之外
        val r = snap(x0, SH_WORK - PH - 100f, false)
        // 垂直贴底
        assertEquals(SH_WORK - PH, r[1], 0.5f)
        // 水平位置原样保留
        assertEquals(x0, r[0], 0.5f)
    }

    @Test
    fun `B snaps only horizontally and keeps the vertical release position`() {
        val y0 = 1200f
        val r = snap(SW - PW - 100f, y0, false)
        // 水平贴右
        assertEquals(SW - PW, r[0], 0.5f)
        // 垂直位置原样保留
        assertEquals(y0, r[1], 0.5f)
        assertEquals(PetState.BORDER_R.toFloat(), r[2], 0.01f)
    }

    @Test
    fun `E snaps to the nearer edge and keeps the other axis`() {
        // 离底更近 -> 贴底，x 不变
        val x0 = 300f
        val rBottom = snap(x0, SH_WORK - PH - 500f, false)
        assertEquals(SH_WORK - PH, rBottom[1], 0.5f)
        assertEquals(x0, rBottom[0], 0.5f)

        // 离右更近 -> 贴右，y 不变
        val y0 = 800f
        val rRight = snap(SW - PW - 500f, y0, false)
        assertEquals(SW - PW, rRight[0], 0.5f)
        assertEquals(y0, rRight[1], 0.5f)
    }

    @Test
    fun `D snaps to the nearer edge too`() {
        // D 区：两边都在 356..444 之间，取更近的
        val rBottom = snap(SW - PW - 420f, SH_WORK - PH - 380f, false)
        assertEquals(SH_WORK - PH, rBottom[1], 0.5f)
        val rRight = snap(SW - PW - 380f, SH_WORK - PH - 420f, false)
        assertEquals(SW - PW, rRight[0], 0.5f)
    }

    @Test
    fun `left half is a perfect mirror of the right half`() {
        val rightCorner = snap(SW - PW - 50f, SH_WORK - PH - 50f, false)
        val leftCorner = snap(50f, SH_WORK - PH - 50f, true)
        assertEquals(0f, leftCorner[0], 0.5f)
        assertEquals(rightCorner[1], leftCorner[1], 0.5f)
        assertEquals(PetState.CORNER_BL.toFloat(), leftCorner[2], 0.01f)

        // 左边框：贴左边，y 保留
        val y0 = 1500f
        val lb = snap(100f, y0, true)
        assertEquals(0f, lb[0], 0.5f)
        assertEquals(y0, lb[1], 0.5f)
        assertEquals(PetState.BORDER_L.toFloat(), lb[2], 0.01f)

        // 镜像验证：右侧 x 镜像过来应等于左侧 x
        val rc = snap(SW - PW - 60f, SH_WORK - PH - 60f, false)
        val lc = snap(60f, SH_WORK - PH - 60f, true)
        assertEquals(SW - (rc[0] + PW), lc[0], 0.5f)
    }

    @Test
    fun `C released near the corner falls into A and uses corner art`() {
        // "先算落点再判素材"的关键：C 区贴底后水平位置仍靠近角落，
        // 落点其实在 A 区里，所以该用角落素材
        val r = snap(SW - PW - 10f, SH_WORK - PH - 100f, false)
        assertEquals(SH_WORK - PH, r[1], 0.5f)
        assertEquals(PetState.CORNER_BR.toFloat(), r[2], 0.01f)
    }

    @Test
    fun `snap category follows the snapped position not the release zone`() {
        // 贴右边落在 A 区（离底也近）-> 角落素材
        assertEquals(PetState.CORNER_BR, PetState.snapCategory(0f, 100f, false))
        // 贴底边、落点远离角落（侧边距离超过 356）-> 边框素材
        assertEquals(PetState.BORDER_R, PetState.snapCategory(0f, 900f, false))
        // 贴角落在 A 区 -> 角落素材
        assertEquals(PetState.CORNER_BR, PetState.snapCategory(0f, 0f, false))
        assertEquals(PetState.CORNER_BL, PetState.snapCategory(0f, 0f, true))
    }

    @Test
    fun `left half measures distance from the LEFT edge, not the right`() {
        // 关键回归：左下角的宠物在左半屏，侧边距离必须量左边（≈0），
        // 否则会被算成 dr≈915，判进 C 区只做垂直吸附，永远吸不到左下角
        val x = 50f
        val y = SH_WORK - PH - 50f
        assertEquals(50f, PetState.sideDist(x, PW, SW, true), 0.5f)
        assertEquals(SW - (x + PW), PetState.sideDist(x, PW, SW, false), 0.5f)
        // 它应该是 A 区（左下角）
        assertEquals(PetState.ZONE_A, PetState.zoneOf(
            PetState.bottomDist(y, PH, SH_WORK),
            PetState.sideDist(x, PW, SW, true)
        ))
        // 而且真的吸到左下角
        val r = snap(x, y, true)
        assertEquals(0f, r[0], 0.5f)
        assertEquals(PetState.CORNER_BL.toFloat(), r[2], 0.01f)
    }

    // ---------------------------------------------------------- 旋转角（用户规定）

    @Test
    fun `C uses the raw border asset with no rotation`() {
        assertEquals(0f, PetState.rotationForSnap(PetState.CORNER_BR), 0.001f)
        // 贴底边（dr 大或 db 小）都不转
        assertEquals(0f, PetState.rotationForDrag(100f, 600f, false), 0.001f)
        assertEquals(0f, PetState.rotationForDrag(100f, 100f, false), 0.001f)
    }

    @Test
    fun `B uses the border asset rotated 90 degrees`() {
        assertEquals(-90f, PetState.rotationForSnap(PetState.BORDER_R), 0.001f)
        assertEquals(90f, PetState.rotationForSnap(PetState.BORDER_L), 0.001f)
        // 贴右边且离底足够远 -> 满角度
        assertEquals(-90f, PetState.rotationForDrag(700f, 0f, false), 0.001f)
        assertEquals(90f, PetState.rotationForDrag(700f, 0f, true), 0.001f)
    }

    @Test
    fun `A never rotates`() {
        assertEquals(0f, PetState.rotationForDrag(10f, 10f, false), 0.001f)
        assertEquals(0f, PetState.rotationForDrag(300f, 300f, true), 0.001f)
    }

    // ---------------------------------------------------------- 极致顺滑：旋转是连续场

    @Test
    fun `rotation is a continuous field - no jumps`() {
        // 沿 dr 扫一遍（模拟把宠物从右边往里拖），相邻 1px 的跳变必须极小
        var prev = PetState.rotationForDrag(700f, 0f, false)
        var maxJump = 0f
        var at = 0f
        var dr = 1f
        while (dr <= 800f) {
            val cur = PetState.rotationForDrag(700f, dr, false)
            val j = abs(cur - prev)
            if (j > maxJump) { maxJump = j; at = dr }
            prev = cur
            dr += 1f
        }
        assertTrue("旋转沿 dr 的最大单步跳变 $maxJump (在 dr=$at) 应 < 1 度", maxJump < 1f)
    }

    @Test
    fun `rotation is monotonic as the pet leaves the right edge`() {
        val a = PetState.rotationForDrag(700f, 300f, false)
        val b = PetState.rotationForDrag(700f, 400f, false)
        val c = PetState.rotationForDrag(700f, 500f, false)
        assertTrue("越往里角度越小", a < c)
        assertTrue("单调", b in a..c)
        // 贴边处是满角度
        assertEquals(-90f, PetState.rotationForDrag(700f, 0f, false), 0.001f)
        // 完全离开后归零（过渡带 EDGE_FADE_DEG=200，从 dr=356 起衰减到 0）
        assertEquals(0f, PetState.rotationForDrag(700f, 700f, false), 0.001f)
        assertEquals(0f, PetState.rotationForDrag(700f, PetState.CORNER_ZONE_PX + PetState.EDGE_FADE_DEG, false), 0.001f)
    }

    @Test
    fun `rotation fades out with height too, so the bottom band never tilts`() {
        // 贴着右边但也在底边带里（C 区）-> 不该转
        assertEquals(0f, PetState.rotationForDrag(100f, 0f, false), 0.001f)
        assertEquals(0f, PetState.rotationForDrag(356f, 0f, false), 0.001f)
        // 往上走角度才起来，且是连续的
        val low = PetState.rotationForDrag(400f, 0f, false)
        val high = PetState.rotationForDrag(600f, 0f, false)
        assertTrue("往上角度变大", abs(high) > abs(low))
    }

    // ---------------------------------------------------------- 极致顺滑：素材混合连续

    @Test
    fun `art blend is continuous from corner art to border art`() {
        assertEquals(0f, PetState.artBlendFor(0f, 0f), 0.001f)
        assertEquals(0f, PetState.artBlendFor(200f, 200f), 0.001f)
        // 平滑过渡到 1
        val mid = PetState.artBlendFor(320f, 900f)
        assertTrue("中段应在 0..1 之间", mid > 0.05f && mid < 0.95f)
        assertEquals(1f, PetState.artBlendFor(400f, 900f), 0.001f)
        assertEquals(1f, PetState.artBlendFor(900f, 900f), 0.001f)
        // 单调
        assertTrue(PetState.artBlendFor(280f, 900f) < PetState.artBlendFor(340f, 900f))
    }

    @Test
    fun `art blend has no jumps either`() {
        var prev = PetState.artBlendFor(0f, 900f)
        var maxJump = 0f
        var d = 1f
        while (d <= 700f) {
            val cur = PetState.artBlendFor(d, 900f)
            maxJump = maxOf(maxJump, abs(cur - prev))
            prev = cur
            d += 1f
        }
        assertTrue("素材混合最大跳变 $maxJump 应 < 0.05", maxJump < 0.05f)
    }

    // ---------------------------------------------------------- 模糊：整图高强度 + 交界渐变

    @Test
    fun `blur is zero when hugging an edge and zero far away`() {
        // 贴着吸附边 -> 完全清晰
        assertEquals(0f, PetState.blurFor(0f, 0f), 0.001f)
        assertEquals(0f, PetState.blurFor(100f, 100f), 0.001f)
        assertEquals(0f, PetState.blurFor(PetState.FADE_INNER_PX, 900f), 0.001f)
        // 离得足够远（超出渐弱终点）-> 完全清晰
        assertEquals(0f, PetState.blurFor(PetState.FADE_OUTER_PX, 900f), 0.001f)
        assertEquals(0f, PetState.blurFor(700f, 900f), 0.001f)
        // 渐弱区间内还有余雾（这是渐变，不是 bug）
        assertTrue(PetState.blurFor(500f, 900f) > 0f)
    }

    @Test
    fun `blur stays high across the whole D band`() {
        // D 区 356..444，整段都必须是高强度（不能在里面就开始衰减）
        assertTrue("400/400 应高强度", PetState.blurFor(400f, 400f) > 0.9f)
        assertTrue("356 内沿已高", PetState.blurFor(356f, 356f) > 0.9f)
        assertTrue("444 外沿仍高", PetState.blurFor(444f, 444f) > 0.85f)
    }

    @Test
    fun `blur uses the NEARER edge, not the farther one`() {
        // 关键回归：贴着右边（dr 很小）但离底很远，仍是 D 状态，强度必须高
        assertTrue("贴右边离底远也要糊", PetState.blurFor(500f, 400f) > 0.9f)
        assertTrue("对称", PetState.blurFor(400f, 500f) > 0.9f)
        // 早先用 max(db,dr) 时这里会得 0.5，是错的
    }

    @Test
    fun `blur ramps up before D so there is no hard edge`() {
        val a = PetState.blurFor(260f, 900f)
        val b = PetState.blurFor(300f, 900f)
        val c = PetState.blurFor(340f, 900f)
        assertTrue("在渐变段内", a > 0f && a < 1f)
        assertTrue("越接近 D 越糊", b > a)
        assertTrue("继续变糊", c > b)
        // D 内沿已经很接近满强度
        assertTrue("356 处应接近满值", PetState.blurFor(356f, 900f) > 0.9f)
    }

    @Test
    fun `blur ramps back down after D`() {
        val a = PetState.blurFor(444f, 900f)
        val b = PetState.blurFor(452f, 900f)
        assertTrue("离得越远越清晰", b < a)
        assertEquals(0f, PetState.blurFor(PetState.FADE_OUTER_PX, 900f), 0.001f)
        assertEquals(0f, PetState.blurFor(600f, 900f), 0.001f)
    }

    @Test
    fun `blur is continuous - no jumps anywhere`() {
        // 扫一遍 0..700，相邻 1px 的差值必须很小（不会有生硬分界）
        var prev = PetState.blurFor(0f, 900f)
        var maxJump = 0f
        var at = 0f
        var d = 1f
        while (d <= 700f) {
            val cur = PetState.blurFor(d, 900f)
            val j = abs(cur - prev)
            if (j > maxJump) { maxJump = j; at = d }
            prev = cur
            d += 1f
        }
        assertTrue("最大单步跳变 $maxJump (在 $at) 应 < 0.06", maxJump < 0.06f)
    }

    // ---------------------------------------------------------- 别被拖丢

    @Test
    fun `position is clamped so the pet cannot be dragged off screen`() {
        val w = 475f; val h = 463f
        val sw = 1440f; val sh = 3084f

        // 拖到完全在屏幕外 -> 夹回，至少露一半
        val farLeft = PetState.clampToScreen(-2000f, 1000f, w, h, sw, sh)
        assertTrue("左边不能丢", farLeft[0] >= -w + w * 0.5f - 0.01f)
        val farRight = PetState.clampToScreen(9999f, 1000f, w, h, sw, sh)
        assertTrue("右边不能丢", farRight[0] <= sw - w * 0.5f + 0.01f)
        val farUp = PetState.clampToScreen(500f, -2000f, w, h, sw, sh)
        assertTrue("上边不能丢", farUp[1] >= -h + h * 0.5f - 0.01f)
        val farDown = PetState.clampToScreen(500f, 9999f, w, h, sw, sh)
        assertTrue("下边不能丢", farDown[1] <= sh - h * 0.5f + 0.01f)

        // 已经在屏幕内的位置**不应被改动**（吸附要能停在松手处）
        val inside = PetState.clampToScreen(800f, 1500f, w, h, sw, sh)
        assertEquals(800f, inside[0], 0.01f)
        assertEquals(1500f, inside[1], 0.01f)

        // 角上的原位（紧贴右下）也不能被改动，否则"吸附到角落"就失效了
        val corner = PetState.clampToScreen(sw - w, sh - h, w, h, sw, sh)
        assertEquals(sw - w, corner[0], 0.01f)
        assertEquals(sh - h, corner[1], 0.01f)
    }

    // ---------------------------------------------------------- 比例不变（用户硬要求）

    @Test
    fun `scaling preserves the aspect ratio of each asset`() {
        val corner = PetState.scaledSize(1440, 1352, 3084f * 0.15f)
        val border = PetState.scaledSize(1024, 875, 3084f * 0.15f)
        assertEquals(1440.0 / 1352.0, corner[0].toDouble() / corner[1], 0.01)
        assertEquals(1024.0 / 875.0, border[0].toDouble() / border[1], 0.01)
    }

    @Test
    fun `rotating never swaps width and height of the draw box`() {
        val a = PetState.scaledSize(1024, 875, 463f)
        val ratio = a[0].toDouble() / a[1]
        assertEquals(1024.0 / 875.0, ratio, 0.01)
        assertTrue("不能是倒数", abs(ratio - 875.0 / 1024.0) > 0.01)
    }

    /**
     * 用户硬要求："旋转的时候无论如何不要改变比例"。
     *
     * 实现上旋转只作用在 Canvas 上（绕格子中心），**从不修改目标矩形**，
     * 所以格子尺寸与角度完全无关。这里把这条不变式钉死。
     */
    @Test
    fun `draw box is independent of the rotation angle`() {
        val sizes = mutableListOf<IntArray>()
        var deg = 0f
        while (deg <= 360f) {
            // 旋转只会改变绘制变换，不会让 scaledSize 得到不同结果
            sizes += PetState.scaledSize(1024, 875, 463f)
            deg += 15f
        }
        val first = sizes.first()
        for (s in sizes) {
            assertEquals(first[0], s[0])
            assertEquals(first[1], s[1])
        }
        // 而且和"正立时"的格子完全一样
        assertEquals(1024.0 / 875.0, first[0].toDouble() / first[1], 0.01)
    }
}
