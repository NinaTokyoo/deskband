package com.dsh.deskpet

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import org.json.JSONObject

/**
 * 一个角色的立绘素材。
 *
 * 每个角色两套图（目录约定 assets/pet/<角色>/）：
 *   corner.png  角落状态用，**正立、头朝上**
 *   border.png  边框状态用，源图也是正立，**渲染时逆时针转 90°** 变成头朝屏幕内侧
 *   pet.json    尺寸清单
 *
 * 用户要求：素材**不裁切、不改比例**，所以这里只做等比缩放（绘制时按 dstRect 缩放），
 * 不对像素做任何裁剪。
 *
 * 四个方向的实际位图在这里一次性生成并缓存：
 *   CORNER_BR  正立
 *   CORNER_BL  左源已含镜像，直接用
 *   BORDER_R   逆时针 90°
 *   BORDER_L   逆时针 90°
 */
class PetSprite private constructor(
    val dir: String,
    val displayName: String,
    val cornerW: Int,
    val cornerH: Int,
    val borderW: Int,
    val borderH: Int,
    private val cornerSrc: Bitmap,
    private val borderSrc: Bitmap
) {

    /** 缓存的、可直接绘制的位图（已含旋转/镜像） */
    private val cache = HashMap<Int, Bitmap>()

    /**
     * 取某个状态要画的位图（**不含旋转**）。
     *
     * 旋转改到绘制时做，因为"只有贴边才转"是跟距离有关的，
     * 不能按状态缓存成固定角度。
     *
     * @param mirrored 左侧状态需要水平镜像
     */
    fun bitmap(state: Int, mirrored: Boolean): Bitmap {
        val key = state * 2 + if (mirrored) 1 else 0
        cache[key]?.let { return it }
        val src = if (PetState.usesCornerArt(state)) cornerSrc else borderSrc
        val bmp = mirrorOnly(src, mirrored)
        cache[key] = bmp
        return bmp
    }

    /** 该状态下素材「正立」时的原始尺寸（未旋转），用于算显示大小 */
    fun naturalWidth(state: Int): Int =
        if (PetState.usesCornerArt(state)) cornerW else borderW

    fun naturalHeight(state: Int): Int =
        if (PetState.usesCornerArt(state)) cornerH else borderH

    /** 该状态下**屏幕上**的宽高（边框状态旋转后宽高互换） */
    fun displaySize(state: Int): IntArray {
        val w = naturalWidth(state)
        val h = naturalHeight(state)
        return if (PetState.isBorder(state)) intArrayOf(h, w) else intArrayOf(w, h)
    }

    fun evict() {
        for (b in cache.values) if (!b.isRecycled) b.recycle()
        cache.clear()
    }

    companion object {
        const val ROOT = "pet"

        /** 兜底：如果用户素材缺失，回退到这个角色 */
        const val FALLBACK = "后藤一里"

        fun listSkins(assets: AssetManager): List<String> =
            (assets.list(ROOT) ?: emptyArray())
                .filter { dir ->
                    // 只认含 pet.json 的目录
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
                val b = root.getJSONObject("border")
                val corner = decode(assets, "$base/" + c.getString("file")) ?: return null
                val border = decode(assets, "$base/" + b.getString("file")) ?: return null
                PetSprite(
                    dir = dir,
                    displayName = root.optString("name", dir),
                    cornerW = c.optInt("w", corner.width),
                    cornerH = c.optInt("h", corner.height),
                    borderW = b.optInt("w", border.width),
                    borderH = b.optInt("h", border.height),
                    cornerSrc = corner,
                    borderSrc = border
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
         * 只做水平镜像（不平移）。右侧和本地的素材都已经是镜像好的，
         * 这里主要给"临时跨半屏"用。
         *
         * 用显式的 scale 矩阵而不是 [Bitmap.createBitmap] 的重载：
         * 那个重载会把图裁回原尺寸，镜像后内容会偏。
         */
        private fun mirrorOnly(src: Bitmap, mirror: Boolean): Bitmap {
            if (!mirror) return src
            val m = Matrix()
            m.setScale(-1f, 1f)
            m.postTranslate(src.width.toFloat(), 0f)
            return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        }
    }
}
