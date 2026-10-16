package com.dsh.deskpet

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * 一个角色的两套素材（目录约定 `assets/pet/<角色>/`）：
 *
 *   corner.png    角落素材（正立，头朝上）—— A 区用
 *   upright.png   正立素材（正立，头朝上）—— B / C / E 区用，**左右共用同一套**
 *   pet.json      尺寸清单
 *
 * ## 左侧怎么来的
 *
 * 不额外存左侧图。实测确认过：
 *   flip(mirror(角落素材))   == 原来的「左下角」
 *   flip(mirror(正立素材))   == 原来的「左边框」
 * 所以左半屏在渲染时做 **水平镜像**（转 90° 的形态再叠加旋转角符号翻转）即可，
 * 省掉一半体积，也保证左右永远一致。
 *
 * ## 上方怎么来的
 *
 * 同理不额外存"上方"图：**上角 / 贴上沿 = 下角 / 贴下沿上下翻转**（flipV），
 * 于是上半屏是下半屏沿水平轴镜像过来的形态，左右两侧用同一套规则
 * （左半屏 = 镜像 + 上下翻转）。翻转量由 [PetState.flipVFor] 按状态给出。
 *
 * 用户要求：**不裁切、不改比例**，所以这里只做等比缩放，不裁剪任何像素。
 */
class PetSprite private constructor(
    val dir: String,
    val displayName: String,
    val cornerW: Int,
    val cornerH: Int,
    val uprightW: Int,
    val uprightH: Int,
    private val cornerSrc: Bitmap,
    private val uprightSrc: Bitmap
) {

    /**
     * 派生位图缓存。
     *
     * ⚠️ 键是「**素材种类** + mirror + flipV」，**不是 state**。用 state 当键有两个
     * 毛病（性能审查报告指出的）：
     *   1. 内容相同的状态会各存一份整尺寸副本 —— `BORDER_L` 与
     *      `BORDER_L_UPRIGHT` 都是 upright + 镜像，却存了两份 3.4 MiB。
     *   2. 键空间随状态数膨胀，最坏 44 份 ≈ 36 MiB 派生位图。
     * 素材种类只有 corner / upright 两种，于是组合最多 2x2x2 = 8 份。
     */
    private val cache = HashMap<Int, Bitmap>()

    /** 缓存键：素材种类占最高位，镜像 / 翻转各占 1 位 —— 与内容一一对应 */
    private fun cacheKey(corner: Boolean, mirror: Boolean, flipV: Boolean): Int =
        (if (corner) 4 else 0) or (if (mirror) 2 else 0) or (if (flipV) 1 else 0)

    /**
     * 取要绘制的位图（**不含旋转**，旋转在绘制时用 Canvas 做）。
     *
     * 两个变换都是 **state 的纯函数**（由调用方按 [PetState.isLeft] /
     * [PetState.flipVFor] 传入），缓存键才与内容一一对应。
     *
     * @param mirror 左半屏 -> 水平镜像
     * @param flipV  上边组（上角 / 贴上沿）-> 上下翻转。
     *               左右两侧同一套规则，所以左半屏是"镜像 + 上下翻转"
     *               （等价于旋转 180°）；旋转躺倒的侧边形态不翻。
     */
    fun bitmap(state: Int, mirror: Boolean, flipV: Boolean = false): Bitmap {
        val corner = PetState.usesCornerArt(state)
        val key = cacheKey(corner, mirror, flipV)
        cache[key]?.let { if (!it.isRecycled) return it }
        val src = if (corner) cornerSrc else uprightSrc
        val bmp = transform(src, mirror, flipV)
        // 不做任何变换时 transform 直接返回源图本身，**不能放进 cache** ——
        // 否则 evict() 会把源图一起回收（见 evict 的注释）
        if (bmp !== src) cache[key] = bmp
        return bmp
    }

    /**
     * 是否已被 [evict] 回收过。
     *
     * ⚠️ [PetService.showPet] 靠它判断"能不能复用这个 sprite"：evict 之后派生位图
     * 已被 recycle，再拿来画就是 "Canvas: trying to use a recycled bitmap" 崩溃。
     * 所以被 evict 过的 sprite 必须整体作废、重新 load。
     */
    var isEvicted: Boolean = false
        private set

    /** 该状态用的素材原始宽（正立） */
    fun naturalWidth(state: Int): Int =
        if (PetState.usesCornerArt(state)) cornerW else uprightW

    /** 该状态用的素材原始高（正立） */
    fun naturalHeight(state: Int): Int =
        if (PetState.usesCornerArt(state)) cornerH else uprightH

    /**
     * 回收所有**派生**位图（镜像 / 翻转副本）。
     *
     * ⚠️ 源图 [cornerSrc] / [uprightSrc] **不在这里回收**。它们同样是"本对象持有、
     * 别人只借用"的位图，但回收时机由 [PetService] 在销毁时统一决定，而且
     * [PetView] 的绘制路径可能正拿着它们（那边有 isRecycled 兜底，但没必要去踩）。
     * 只要 [bitmap] 不把源图放进 cache，这里就碰不到源图 —— 两处要一起维护。
     */
    fun evict() {
        for (b in cache.values) {
            if (!b.isRecycled && b !== cornerSrc && b !== uprightSrc) b.recycle()
        }
        cache.clear()
        isEvicted = true
    }

    companion object {
        const val ROOT = "pet"

        /**
         * 解码时较长边的目标像素上限（见 [decode]）。
         *
         * 1280 的来由：桌宠高度 = 屏幕高 x SIZE_BASE(0.08) x sizeScale，
         * 放大上限 SIZE_SCALE_MAX = 5，1440x3084 的屏上给足余量约 1234 px。
         * 所以在正常倍率范围内不会因降采样而看出模糊，把大小拉满也够用。
         */
        const val DECODE_MAX_PX = 1280

        /**
         * 素材缺失时的兜底角色。
         *
         * ⚠️ 2.2.0 起内置形象只剩 QDS（用户要求删掉其余人物，作为正式版）——
         *    这里必须跟着改，否则找不到任何形象时会去加载一个已经不存在的目录。
         */
        const val FALLBACK = "QDS"

        /**
         * 全部可选形象：**内置的在前（按目录名排序）、自定义的在后（按创建顺序）**。
         *
         * 自定义排在后面是有意的 —— 刚新增的那个一定在列表最下方，不用翻。
         */
        fun listSkins(ctx: Context): List<String> =
            builtinSkins(ctx.assets) + CustomSkinStore.list(ctx)

        /** APK 内置素材：`assets/pet/` 下带 pet.json 的子目录，按 [BUILTIN_ORDER] 排序 */
        private fun builtinSkins(assets: AssetManager): List<String> {
            val dirs = (assets.list(ROOT) ?: emptyArray()).filter { dir ->
                (assets.list("$ROOT/$dir") ?: emptyArray()).contains("pet.json")
            }
            // 用户 2026-10-17 指定的默认顺序：QDS、后藤、虹夏、凉、郁代；
            // 名单里没提到的（以后新增的）按目录名接在后面，顺序仍然稳定。
            return dirs.sortedWith(
                compareBy({ BUILTIN_ORDER.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it })
            )
        }

        /**
         * 内置形象在「当前形象」列表里的**固定顺序**（用户 2026-10-17 指定）。
         *
         * ⚠️ 2.2.0（正式版）起内置只剩 **QDS** —— 其余人物素材按用户要求已从 APK 删除，
         *    名单里留着的其它名字不会再出现在列表里（目录不存在就列不出来）。
         *    用户自建的形象仍按创建顺序排在内置之后（见 [listSkins]）。
         */
        private val BUILTIN_ORDER = listOf(
            "QDS", "后藤一里", "伊地知虹夏", "山田凉", "喜多郁代"
        )

        /**
         * 只读一个形象的**显示名**，不解码任何图片。
         *
         * 「当前形象」列表在每次换角色 / 切明暗时都要重建，而重建要拿到所有名字 ——
         * 原来这里走的是 [load]（每个形象解码两张 PNG），自定义形象一多，
         * 这个代价就直接压在 UI 线程上。
         */
        fun readName(ctx: Context, dir: String): String? =
            if (CustomSkinStore.exists(ctx, dir)) {
                CustomSkinStore.readName(ctx, dir)
            } else {
                try {
                    val json = ctx.assets.open("$ROOT/$dir/pet.json")
                        .use { it.readBytes().toString(Charsets.UTF_8) }
                    JSONObject(json).optString("name", "").takeIf { it.isNotEmpty() }
                } catch (e: Exception) {
                    null
                }
            }

        /**
         * 加载一个形象（含解码两张素材）。
         *
         * **自定义优先**：filesDir 下有这个 id 就用它，否则回落到 APK 内置素材。
         * 判据是"目录里有没有 pet.json"，而不是给 id 加前缀 ——
         * 前缀一旦和角色名撞上（内置目录名就是中文角色名）就再也分不清了。
         */
        fun load(ctx: Context, dir: String): PetSprite? {
            if (CustomSkinStore.exists(ctx, dir)) {
                val base = CustomSkinStore.dirOf(ctx, dir)
                val json = try {
                    File(base, CustomSkinStore.JSON).readText(Charsets.UTF_8)
                } catch (e: Exception) {
                    return null
                }
                return parse(dir, json) { name -> CustomSkinStore.openFileStream(base, name) }
            }
            return loadFromAssets(ctx.assets, dir)
        }

        private fun loadFromAssets(assets: AssetManager, dir: String): PetSprite? {
            val base = "$ROOT/$dir"
            val json = try {
                assets.open("$base/pet.json").use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: Exception) {
                return null
            }
            return parse(dir, json) { name ->
                try {
                    assets.open("$base/$name")
                } catch (e: Exception) {
                    null
                }
            }
        }

        /**
         * pet.json + 两个图片流 -> [PetSprite]。
         *
         * 内置素材与自定义形象共用这一段：区别只有"清单从哪读、图片流怎么开"，
         * 都由 [open] 传进来，所以解码 / 降采样 / 缓存键这些规则永远只有一份。
         */
        private fun parse(
            dir: String, json: String, open: (String) -> InputStream?
        ): PetSprite? = try {
            val root = JSONObject(json)
            val c = root.getJSONObject("corner")
            val u = root.getJSONObject("upright")
            val corner = decode { open(c.getString("file")) } ?: return null
            val upright = decode { open(u.getString("file")) } ?: return null
            PetSprite(
                dir = dir,
                displayName = root.optString("name", dir),
                cornerW = c.optInt("w", corner.width),
                cornerH = c.optInt("h", corner.height),
                uprightW = u.optInt("w", upright.width),
                uprightH = u.optInt("h", upright.height),
                cornerSrc = corner,
                uprightSrc = upright
            )
        } catch (e: Exception) {
            null
        }

        /**
         * 解码素材。
         *
         * ⚠️ **必须降采样**。素材原图很大（实测 corner.png 1440x1352，ARGB_8888
         * 占 7.4 MiB），而桌宠在屏幕上只画到约 247 px 高（缩放 18%）——
         * 原尺寸解码等于白占约 30 倍内存。而且 [transform] 生成的镜像 / 翻转
         * 副本也是同分辨率，会把这个浪费一起放大。
         *
         * 目标尺寸见 [DECODE_MAX_PX]。先只读 bounds 再解码：inSampleSize 只能取
         * 2 的幂，得先知道原尺寸才能算出"最接近又不小于目标"的那一档。
         * 解码要开两次流 —— 内置素材是仓库里的 asset、自定义形象是本机文件，
         * 重复打开都没有代价。
         *
         * [open] 由调用方给：assets 走 AssetManager，自定义形象走 FileInputStream。
         * 自定义形象落盘前已经被 [CustomSkinStore] 缩到 [DECODE_MAX_PX] 以内，
         * 所以这里的 `inSampleSize` 对它们恒为 1，清单里的宽高与位图尺寸必然一致。
         */
        private fun decode(open: () -> InputStream?): Bitmap? = try {
            val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open()?.use { input ->
                BitmapFactory.decodeStream(input, null, probe)
            }
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sampleSizeFor(probe.outWidth, probe.outHeight)
            }
            open()?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            }
        } catch (e: Exception) {
            null
        }

        /**
         * 算 2 的幂的 inSampleSize，使解码后的**较长边**最接近但不小于 [DECODE_MAX_PX]。
         *
         * 用较长边而不是高：素材有横有竖（corner 是 1440x1352，upright 是
         * 1024x875），按较长边判能保证两个方向都不低于目标。
         */
        private fun sampleSizeFor(w: Int, h: Int): Int {
            if (w <= 0 || h <= 0) return 1
            val longest = maxOf(w, h)
            var n = 1
            while (longest / (n * 2) >= DECODE_MAX_PX) n *= 2
            return n
        }

        /**
         * 用显式 scale 矩阵做镜像 / 上下翻转。
         *
         * 不用 [Bitmap.createBitmap] 的 Matrix 重载：那个会把结果裁回原尺寸，
         * 镜像后内容会偏。
         */
        private fun transform(src: Bitmap, mirror: Boolean, flipV: Boolean): Bitmap {
            if (!mirror && !flipV) return src
            val m = Matrix()
            m.setScale(if (mirror) -1f else 1f, if (flipV) -1f else 1f)
            // 负缩放会把内容推到负坐标，平移回来
            m.postTranslate(if (mirror) src.width.toFloat() else 0f,
                if (flipV) src.height.toFloat() else 0f)
            return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        }
    }
}
