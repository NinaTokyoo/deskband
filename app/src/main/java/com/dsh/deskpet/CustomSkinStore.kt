package com.dsh.deskpet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 「自定义形象」的落盘与读取。
 *
 * ## 目录结构与内置素材**同构**
 *
 * ```
 * filesDir/custom_pet/<id>/pet.json     尺寸清单（与 assets 里的格式完全一样）
 *                        /upright.png   主素材（必选）—— 对应"吸附到非角落"时用的那张
 *                        /corner.png    角落素材（可选）
 * ```
 *
 * 之所以照抄 assets 那一套：加载路径就只多一个"根目录从哪来"，
 * 解码 / 降采样 / 镜像 / 缓存全部与内置形象共用（见 [PetSprite.load]）。
 *
 * ## 角落素材可选的实现方式
 *
 * **不做"缺角时回退"的分支**，而是在 pet.json 里把 `corner.file` 直接指向
 * `upright.png`、宽高也照抄主素材。于是"没有角落素材 = 角落状态也用主素材"，
 * 而 PetSprite 那边一行判断都不需要，也不可能出现"只改了一处回退逻辑"的漏网。
 *
 * ## id 与显示名分离
 *
 * 目录名是 `u<毫秒时间戳>`（内部 id，只用于定位目录），显示名存在 pet.json 的
 * `name` 里。这样用户可以给两个形象取一样的名字而不会互相覆盖，
 * 名字里也能带 `/`、emoji 等文件系统不接受的字符。
 */
object CustomSkinStore {

    /** filesDir 下的根目录名 */
    const val DIR = "custom_pet"

    /** 目录内固定文件名（与 assets/pet/<角色>/ 保持一致） */
    const val JSON = "pet.json"
    const val UPRIGHT = "upright.png"
    const val CORNER = "corner.png"

    /** 预览图（选图后显示在方框里）的降采样上限（px） */
    private const val PREVIEW_MAX_PX = 320

    fun root(ctx: Context): File = File(ctx.filesDir, DIR)

    fun dirOf(ctx: Context, id: String): File = File(root(ctx), id)

    /** 该 id 是否是一个**有效**的自定义形象（目录里必须有 pet.json） */
    fun exists(ctx: Context, id: String): Boolean = File(dirOf(ctx, id), JSON).isFile

    /**
     * 列出全部自定义形象 id。
     *
     * 用 id（= 创建时间戳）升序 —— 也就是**按创建先后**排，
     * 新增的排在最后，用户一眼能找到刚加的那个。
     */
    fun list(ctx: Context): List<String> =
        (root(ctx).listFiles() ?: emptyArray())
            .filter { it.isDirectory && File(it, JSON).isFile }
            .map { it.name }
            .sorted()

    /** 读显示名；读不到返回 null（调用方自行退回目录名） */
    fun readName(ctx: Context, id: String): String? = try {
        val json = File(dirOf(ctx, id), JSON).readText(Charsets.UTF_8)
        JSONObject(json).optString("name", "").takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    /**
     * 保存一个新形象。
     *
     * @param name       显示名；空串表示"用调用方给的默认名"由上层处理
     * @param uprightUri 主素材（必选）
     * @param cornerUri  角落素材（可选；为 null 时角落也用主素材）
     * @return 新形象的 id；任何一步失败都返回 null
     */
    fun save(ctx: Context, name: String, uprightUri: Uri, cornerUri: Uri?): String? {
        val upright = decodeScaled(ctx, uprightUri, PetSprite.DECODE_MAX_PX) ?: return null
        val corner = cornerUri?.let { decodeScaled(ctx, it, PetSprite.DECODE_MAX_PX) }

        val id = newId(ctx)
        val dir = dirOf(ctx, id)
        if (!dir.mkdirs() && !dir.isDirectory) {
            upright.recycle()
            corner?.recycle()
            return null
        }

        return try {
            writePng(File(dir, UPRIGHT), upright)
            // 角落素材缺省 -> 让 pet.json 指向主素材（见类注释）
            val cornerFile = if (corner != null) {
                writePng(File(dir, CORNER), corner)
                CORNER
            } else {
                UPRIGHT
            }
            val cornerW = corner?.width ?: upright.width
            val cornerH = corner?.height ?: upright.height

            File(dir, JSON).writeText(
                buildJson(
                    name = name,
                    cornerFile = cornerFile, cornerW = cornerW, cornerH = cornerH,
                    uprightW = upright.width, uprightH = upright.height
                ),
                Charsets.UTF_8
            )
            id
        } catch (e: Exception) {
            // 半成品目录会让它在「当前形象」里以坏形象出现，直接清掉
            dir.deleteRecursively()
            null
        } finally {
            upright.recycle()
            corner?.recycle()
        }
    }

    /** 删除一个自定义形象（连同目录里的图片） */
    fun delete(ctx: Context, id: String) {
        runCatching { dirOf(ctx, id).deleteRecursively() }
    }

    /**
     * 从相册/文件选择器返回的 Uri 解码一张位图，**较长边不超过 [maxPx]**。
     *
     * 两步走：先按 2 的幂降采样解码（保住质量），再精确缩放到 [maxPx] ——
     * 精确缩放这一步是必须的：inSampleSize 只能取 2 的幂，4000px 的原图
     * 降一档还有 2000px，而 [PetSprite] 的 `DECODE_MAX_PX` 是 1280。
     *
     * ⚠️ 写进 pet.json 的 `w/h` 必须是**落盘 PNG 的实际尺寸**：
     * PetSprite 用它们算缩放比，而实际位图又会再走一次自己的降采样。
     * 两边不一致（比如清单写原图 4000、位图却被降到 2000）时，
     * 人物会按 4000 算比例，实际画出来只有一半大 —— 且只在"用户自己选的图
     * 比 1280 大"时才出现，内置素材（都 ≤1440）永远踩不到。
     */
    fun decodeScaled(ctx: Context, uri: Uri, maxPx: Int): Bitmap? = try {
        val cr = ctx.contentResolver
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, probe) }
        val srcW = probe.outWidth
        val srcH = probe.outHeight
        if (srcW <= 0 || srcH <= 0) {
            null
        } else {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sampleSizeFor(srcW, srcH, maxPx)
            }
            val decoded = cr.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            decoded?.let { exactScale(it, maxPx) }
        }
    } catch (e: Exception) {
        null
    }

    /** 选图后给界面看的缩略图（不落盘） */
    fun preview(ctx: Context, uri: Uri): Bitmap? = decodeScaled(ctx, uri, PREVIEW_MAX_PX)

    // ------------------------------------------------------------ 内部

    /** 生成一个当前不存在的 id（同一毫秒内连点也不会撞） */
    private fun newId(ctx: Context): String {
        var t = System.currentTimeMillis()
        while (File(root(ctx), "u$t").exists()) t++
        return "u$t"
    }

    private fun writePng(file: File, bmp: Bitmap) {
        FileOutputStream(file).use { out ->
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw IllegalStateException("PNG encode failed: ${file.name}")
            }
        }
    }

    private fun buildJson(
        name: String, cornerFile: String, cornerW: Int, cornerH: Int,
        uprightW: Int, uprightH: Int
    ): String = JSONObject().apply {
        put("name", name)
        put("corner", JSONObject().apply {
            put("file", cornerFile); put("w", cornerW); put("h", cornerH)
        })
        put("upright", JSONObject().apply {
            put("file", UPRIGHT); put("w", uprightW); put("h", uprightH)
        })
    }.toString(2)

    /** 与 PetSprite.sampleSizeFor 同一套规则：降一档就不低于目标 */
    private fun sampleSizeFor(w: Int, h: Int, maxPx: Int): Int {
        var n = 1
        val longest = maxOf(w, h)
        while (longest / (n * 2) >= maxPx) n *= 2
        return n
    }

    /** 精确缩放到较长边 = [maxPx]（本来就够小则原样返回） */
    private fun exactScale(src: Bitmap, maxPx: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxPx) return src
        val scale = maxPx.toFloat() / longest
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        if (scaled !== src) src.recycle()
        return scaled
    }

    /** 给 [PetSprite] 用的文件流（读不出时返回 null，由上层当"素材缺失"处理） */
    fun openFileStream(dir: File, name: String): java.io.InputStream? = try {
        FileInputStream(File(dir, name))
    } catch (e: Exception) {
        null
    }
}
