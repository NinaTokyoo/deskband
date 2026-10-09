package com.dsh.deskpet

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import org.json.JSONObject

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
 * 所以左半屏在渲染时做 **水平镜像 + 上下翻转**（等价于旋转 180°）即可，
 * 省掉一半体积，也保证左右永远一致。
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

    /** 缓存：key = state*4 + (mirror?2:0) + (flipV?1:0) */
    private val cache = HashMap<Int, Bitmap>()

    /**
     * 取要绘制的位图（**不含旋转**，旋转在绘制时用 Canvas 做）。
     *
     * @param mirror 左半屏 -> 水平镜像
     * @param flipV  左半屏且带旋转时 -> 再上下翻转（否则人物会上下颠倒）
     */
    fun bitmap(state: Int, mirror: Boolean, flipV: Boolean = false): Bitmap {
        val key = state * 4 + (if (mirror) 2 else 0) + (if (flipV) 1 else 0)
        cache[key]?.let { return it }
        val src = if (PetState.usesCornerArt(state)) cornerSrc else uprightSrc
        val bmp = transform(src, mirror, flipV)
        cache[key] = bmp
        return bmp
    }

    /** 该状态用的素材原始宽（正立） */
    fun naturalWidth(state: Int): Int =
        if (PetState.usesCornerArt(state)) cornerW else uprightW

    /** 该状态用的素材原始高（正立） */
    fun naturalHeight(state: Int): Int =
        if (PetState.usesCornerArt(state)) cornerH else uprightH

    fun evict() {
        for (b in cache.values) if (!b.isRecycled) b.recycle()
        cache.clear()
    }

    companion object {
        const val ROOT = "pet"

        /** 素材缺失时的兜底角色 */
        const val FALLBACK = "后藤一里"

        fun listSkins(assets: AssetManager): List<String> =
            (assets.list(ROOT) ?: emptyArray())
                .filter { dir ->
                    (assets.list("$ROOT/$dir") ?: emptyArray()).contains("pet.json")
                }
                .sorted()

        fun load(assets: AssetManager, dir: String): PetSprite? {
            val base = "$ROOT/$dir"
            val json = try {
                assets.open("$base/pet.json").use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: Exception) {
                return null
            }
            return try {
                val root = JSONObject(json)
                val c = root.getJSONObject("corner")
                val u = root.getJSONObject("upright")
                val corner = decode(assets, "$base/" + c.getString("file")) ?: return null
                val upright = decode(assets, "$base/" + u.getString("file")) ?: return null
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
        }

        private fun decode(assets: AssetManager, path: String): Bitmap? = try {
            assets.open(path).use { input ->
                BitmapFactory.decodeStream(
                    input, null,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                )
            }
        } catch (e: Exception) {
            null
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
