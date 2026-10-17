package com.dsh.deskpet

import android.content.Context

/**
 * 「气泡内容」的落盘与读取（用户 2026-10-16 新增）。
 *
 * 用户在 App 里以**标签**形式维护若干条内容，点人物时气泡按顺序显示其中一条。
 * 限制（用户给定）：**最多 20 条**，每条**最多 20 个字**（汉字或数量相同的其它字符）。
 *
 * ⚠️ 序列化用**自己写的转义格式**，不用 org.json：
 *    · 内容里可能出现换行、反斜杠、任意 Unicode —— 用 `\n` 分隔 + 转义 `\` 与 `\n` 最稳；
 *    · 纯函数（[encode] / [decode] / [charCount] / [fit]）不依赖 Android，
 *      可以在 JVM 单测里直接验证（org.json 在单测里只有空壳实现，一调就抛）。
 */
object BubbleTextStore {

    /** 条数上限（用户要求） */
    const val MAX_ITEMS = 20

    /** 每条的字数上限（用户要求：20 汉字，或与其数量相同的其它字符） */
    const val MAX_CHARS = 20

    /**
     * 图片条目**转存时的长边上限**（像素）。
     *
     * 气泡里最大也就几百像素，标签里的缩略图只有一行字那么高；
     * 手机上随手一张 4000px 的图原样存下来，解码慢、内存也吃不消。
     */
    const val IMAGE_MAX_PX = 512

    private const val KEY = "bubble_texts"

    /**
     * 上一次输入是否**因为 20 字上限被吃掉了一部分**。
     *
     * 输入过滤器只能返回"收得下的前缀"，用户看到的是"字打不进去"——
     * 之前完全没有任何提示（用户实测：以为标签把内容截断了）。
     * 由界面在读完后复位（[consumeTruncated]）。
     */
    @Volatile
    var truncated: Boolean = false
        private set

    /** 读走"上次被截断"这个信号（读后自动复位） */
    fun consumeTruncated(): Boolean {
        val v = truncated
        truncated = false
        return v
    }

    /**
     * 一条气泡内容。
     *
     * [enabled] = 该条**是否会出现在气泡里**（用户 2026-10-16：标签右侧有「已启用 / 启用」
     * 按钮管理这件事）。被停用的条目仍留在列表里、仍可编辑删除，只是随机时不参与。
     *
     * [image] = 图片文件名（非空即"图片条目"，此时 [text] 为空）；空串 = 文本条目。
     * 图片**不塞进 prefs**，而是复制/转码到 [imageDir] 下按文件名引用 ——
     * prefs 里只存这个名字，列表顺序与启用状态才存在 prefs 里。
     */
    data class Item(
        val text: String,
        val enabled: Boolean,
        val image: String = ""
    ) {
        val isImage: Boolean get() = image.isNotEmpty()
    }

    /**
     * 每行开头的"启用标志"前缀：`1\u0001` / `0\u0001`。
     *
     * 用一个控制字符收尾是为了**老存档兼容**：早先存的是纯文本（没有标志），
     * 那种行不匹配前缀 -> 按"启用 + 文本"处理。若只用单个 '0'/'1' 当标志，
     * 一条以数字 1 开头的内容（例如"1号方案"）会被误吞掉首字符。
     */
    private const val FLAG_ON = "1\u0001"
    private const val FLAG_OFF = "0\u0001"

    /**
     * 紧随启用标志之后的"类型标志"：`T\u0001` = 文本、`I\u0001` = 图片。
     *
     * 加上它之后，一行是 `<启用><类型><载荷>`；2.1.76 及更早只有 `<启用><文本>`，
     * 解出来时"类型标志"不匹配就按文本处理 —— 老数据因此照常可用。
     */
    private const val TYPE_TEXT = "T\u0001"
    private const val TYPE_IMAGE = "I\u0001"

    // ------------------------------------------------------------ 纯函数（可单测）

    /**
     * 这个码点算不算"标点符号"（不计入 20 字上限）。
     *
     * 用户 2026-10-16："20 汉字不包括标点符号" —— 所以 `，。！？；：、"“”（）《》…—`
     * 以及半角 `,.!?;:'"()` 这些**都不占额度**，拉丁字母 / 数字 / emoji 照常占用。
     * 判据用 Unicode 的五个标点类别（破折号 / 前引号 / 后引号 / 连接符 / 其它标点），
     * 不自己列字符表 —— 否则全角半角、各国标点永远列不全。
     */
    fun isPunctuation(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt() -> true
        else -> false
    }

    /**
     * 字数：按 **Unicode 码点**数（emoji、生僻字这些代理对算 **1 个字**），
     * 并**跳过标点符号**（用户要求：标点不占 20 字额度）。
     */
    fun charCount(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!isPunctuation(cp)) n++
            i += Character.charCount(cp)
        }
        return n
    }

    /**
     * 截到上限（输入框另有 InputFilter 实时卡，这里只是兜底）。
     *
     * ⚠️ 语义是"**丢掉多出来的非标点字符**"，不是"从超限处一刀切断"：
     *    标点不占额度，所以 `20 个汉字 + ，。！？` 应当**一个标点都不丢**；
     *    而 `21 个汉字 + ，。` 只丢第 21 个汉字、后面的标点照样留着。
     */
    fun fit(s: String): String {
        val sb = StringBuilder(s.length)
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val w = Character.charCount(cp)
            if (isPunctuation(cp)) {
                sb.append(s, i, i + w)
            } else if (n < MAX_CHARS) {
                n++
                sb.append(s, i, i + w)
            }
            i += w
        }
        return sb.toString()
    }

    /**
     * 转义后按行拼接（**不带**启用标志）：[decode] 的逆运算。
     *
     * ⚠️ 带标志的是 [encodeItems] —— 两者必须配套使用：
     *    `decode(encode(x)) == x`，`decodeItems(encodeItems(x)) == x`。
     */
    fun encode(items: List<String>): String = items.joinToString("\n") { escape(it) }

    /** 带启用/类型标志的编码（界面落盘用这个） */
    fun encodeItems(items: List<Item>): String = items.joinToString("\n") {
        val flag = if (it.enabled) FLAG_ON else FLAG_OFF
        if (it.isImage) flag + TYPE_IMAGE + escape(it.image)
        else flag + TYPE_TEXT + escape(it.text)
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

    /**
     * 解析 [encodeItems] 写出的串（带启用 + 类型标志）；空串 = 空列表。
     *
     * 兼容两种老存档：没有标志的纯文本行、以及 2.1.76 那种"只有启用标志"的行 ——
     * 都按"启用 + 文本"读，且首字符不会被吞。
     */
    fun decodeItems(raw: String?): List<Item> =
        decode(raw).filter { it.isNotEmpty() }.map { line ->
            var rest = line
            var enabled = true
            when {
                rest.startsWith(FLAG_OFF) -> { enabled = false; rest = rest.substring(FLAG_OFF.length) }
                rest.startsWith(FLAG_ON) -> { enabled = true; rest = rest.substring(FLAG_ON.length) }
            }
            when {
                rest.startsWith(TYPE_IMAGE) ->
                    Item(text = "", enabled = enabled, image = rest.substring(TYPE_IMAGE.length))
                rest.startsWith(TYPE_TEXT) ->
                    Item(text = rest.substring(TYPE_TEXT.length), enabled = enabled)
                else -> Item(text = rest, enabled = enabled)      // 老存档：纯文本
            }
        }

    /** 解析 [encode] 写出的串（不带标志），返回每行文字；空串 = 空列表 */
    fun decode(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length -> {
                    when (raw[i + 1]) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        '\\' -> sb.append('\\')
                        else -> sb.append(raw[i + 1])
                    }
                    i += 2
                }
                c == '\n' -> { out.add(sb.toString()); sb.setLength(0); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        out.add(sb.toString())
        return out
    }

    // ------------------------------------------------------------ 落盘

    /** 全部条目（含被停用的），顺序即界面顺序 */
    fun items(ctx: Context): List<Item> =
        decodeItems(Prefs(ctx).bubbleTextsRaw).take(MAX_ITEMS)

    /** 只要**已启用**的条目 —— 气泡随机取用这个（文本与图片都在里面） */
    fun enabledItems(ctx: Context): List<Item> = items(ctx).filter { it.enabled }

    /** 只要**已启用**的条目文字（图片条目为空串，气泡那边按 [Item.isImage] 分支） */
    fun enabledTexts(ctx: Context): List<String> =
        items(ctx).filter { it.enabled }.map { it.text }

    // ------------------------------------------------------------ 图片条目

    /** 图片条目的存放目录（应用私有目录，不需要任何存储权限） */
    fun imageDir(ctx: Context): java.io.File =
        java.io.File(ctx.filesDir, "bubble_images").apply { mkdirs() }

    /** 图片条目的绝对路径（文件不在则返回 null） */
    fun imageFile(ctx: Context, name: String): java.io.File? {
        if (name.isEmpty()) return null
        val f = java.io.File(imageDir(ctx), name)
        return if (f.isFile) f else null
    }

    /**
     * 把用户选中的图片**转存**成气泡用的 PNG，返回文件名。
     *
     * · 支持常见格式与 GIF —— 统一由 `BitmapFactory` 解码（动图取**第一帧**），
     *   再转成 PNG 落盘，之后气泡与标签都只读这一份；
     * · 顺带**限制长边**（[IMAGE_MAX_PX]）：手机上随手一张 4000px 的图，
     *   原样存下来解码会很慢、内存也吃不消，而气泡里最大也就几百像素；
     * · 解码失败返回 null（界面提示"这张图片读不出来"）。
     */
    fun importImage(ctx: Context, uri: android.net.Uri): String? {
        val name = "img_${System.currentTimeMillis()}.png"
        val bmp = try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val raw = android.graphics.BitmapFactory.decodeStream(input) ?: return null
                val long = maxOf(raw.width, raw.height)
                if (long <= IMAGE_MAX_PX) raw else {
                    val k = IMAGE_MAX_PX.toFloat() / long
                    android.graphics.Bitmap.createScaledBitmap(
                        raw,
                        (raw.width * k).toInt().coerceAtLeast(1),
                        (raw.height * k).toInt().coerceAtLeast(1),
                        true
                    ).also { raw.recycle() }
                }
            }
        } catch (e: Exception) {
            null
        } ?: return null
        return try {
            java.io.FileOutputStream(java.io.File(imageDir(ctx), name)).use {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bmp.recycle()
            name
        } catch (e: Exception) {
            bmp.recycle()
            null
        }
    }

    /** 追加一条**图片**（已达上限或转存失败时返回 null，由界面提示） */
    fun addImage(ctx: Context, uri: android.net.Uri): List<Item>? {
        val cur = items(ctx)
        if (cur.size >= MAX_ITEMS) return null
        val name = importImage(ctx, uri) ?: return null
        val next = cur + Item(text = "", enabled = true, image = name)
        saveItems(ctx, next)
        return next
    }

    /** 删掉第 [index] 条（越界原样返回）；图片条目连同文件一起删 */
    fun removeAtItem(ctx: Context, index: Int): List<Item> {
        val cur = items(ctx)
        if (index !in cur.indices) return cur
        val victim = cur[index]
        val next = cur.toMutableList().also { it.removeAt(index) }
        saveItems(ctx, next)
        if (victim.isImage) {
            runCatching { java.io.File(imageDir(ctx), victim.image).delete() }
        }
        return next
    }

    /** 文本列表（兼容旧调用：全部条目的文字） */
    fun list(ctx: Context): List<String> = items(ctx).map { it.text }

    fun saveItems(ctx: Context, items: List<Item>) {
        Prefs(ctx).bubbleTextsRaw = encodeItems(items.take(MAX_ITEMS))
    }

    /** 覆盖式写入（全部按启用处理）；超过条数上限的部分直接丢掉 */
    fun save(ctx: Context, items: List<String>) {
        saveItems(ctx, items.take(MAX_ITEMS).map { Item(it, enabled = true) })
    }

    /** 追加一条并返回新列表；已达上限或内容为空时原样返回（由界面提示） */
    fun add(ctx: Context, text: String): List<String> {
        val t = fit(text.trim())
        val cur = items(ctx)
        if (t.isEmpty() || cur.size >= MAX_ITEMS) return cur.map { it.text }
        saveItems(ctx, cur + Item(t, enabled = true))
        return items(ctx).map { it.text }
    }

    /** 删掉第 [index] 条（越界则原样返回） */
    fun removeAt(ctx: Context, index: Int): List<String> {
        val cur = items(ctx)
        if (index !in cur.indices) return cur.map { it.text }
        saveItems(ctx, cur.toMutableList().also { it.removeAt(index) })
        return items(ctx).map { it.text }
    }

    /**
     * 切换第 [index] 条的**启用**状态（用户 2026-10-16：标签右侧那个按钮）。
     *
     * 返回新列表；越界则原样返回。
     */
    fun setEnabled(ctx: Context, index: Int, enabled: Boolean): List<Item> {
        val cur = items(ctx)
        if (index !in cur.indices) return cur
        val next = cur.toMutableList()
        next[index] = next[index].copy(enabled = enabled)
        saveItems(ctx, next)
        return next
    }

    /**
     * 改第 [index] 条的**文本**（用户 2026-10-17：点标签进入行内编辑）。
     *
     * · 只对**文本条目**有效（图片条目没有可编辑的文本，原样返回）；
     * · 传进来的文本仍按 [MAX_CHARS] 收敛（标点不计），与新增表单同一套规则；
     * · 启用状态、图片文件名都保持不动；返回新列表（越界原样返回）。
     */
    fun updateText(ctx: Context, index: Int, text: String): List<Item> {
        val cur = items(ctx)
        if (index !in cur.indices) return cur
        val old = cur[index]
        if (old.isImage) return cur
        val next = cur.toMutableList()
        next[index] = old.copy(text = fit(text).also { consumeTruncated() })
        saveItems(ctx, next)
        return next
    }

    /**
     * 输入框用的过滤器：**标点不占额度**，超出 20 个非标点字符的部分直接拒收。
     *
     * ⚠️ 不能只在 XML 写 `android:maxLength="20"` —— 那个把标点也算进去，
     *    于是"你好，世界！"这种带标点的句子会被提前截断（用户 2026-10-16 明确要求）。
     *    返回 null = 原样接受；返回替换串 = 只收下能放进去的前缀。
     */
    fun inputFilter(): android.text.InputFilter = android.text.InputFilter { src, _, _, dest, _, _ ->
        if (src == null) return@InputFilter null
        var n = charCount(dest.toString())
        var i = 0
        while (i < src.length) {
            val cp = Character.codePointAt(src, i)
            if (!isPunctuation(cp)) {
                if (n >= MAX_CHARS) {
                    truncated = true          // 给界面一个"被吃掉了"的信号（见 MainActivity）
                    return@InputFilter src.subSequence(0, i).toString()
                }
                n++
            }
            i += Character.charCount(cp)
        }
        null
    }
}
