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

    private const val KEY = "bubble_texts"

    /**
     * 一条气泡内容。
     *
     * [enabled] = 该条**是否会出现在气泡里**（用户 2026-10-16：标签右侧有「已启用 / 启用」
     * 按钮管理这件事）。被停用的条目仍留在列表里、仍可编辑删除，只是轮播时跳过。
     */
    data class Item(val text: String, val enabled: Boolean)

    /**
     * 每行开头的"启用标志"前缀：`1\u0001` / `0\u0001`。
     *
     * 用一个控制字符收尾是为了**老存档兼容**：早先存的是纯文本（没有标志），
     * 那种行不匹配前缀 -> 按"启用"处理。若只用单个 '0'/'1' 当标志，
     * 一条以数字 1 开头的内容（例如"1号方案"）会被误吞掉首字符。
     */
    private const val FLAG_ON = "1\u0001"
    private const val FLAG_OFF = "0\u0001"

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

    /** 带启用标志的编码（界面落盘用这个） */
    fun encodeItems(items: List<Item>): String = items.joinToString("\n") {
        (if (it.enabled) FLAG_ON else FLAG_OFF) + escape(it.text)
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

    /**
     * 解析 [encodeItems] 写出的串（带启用标志）；空串 = 空列表。
     *
     * 老存档（没有标志前缀）按"启用"处理 —— 见 [FLAG_ON] 的说明。
     */
    fun decodeItems(raw: String?): List<Item> =
        decode(raw).filter { it.isNotEmpty() }.map { line ->
            when {
                line.startsWith(FLAG_OFF) -> Item(line.substring(FLAG_OFF.length), enabled = false)
                line.startsWith(FLAG_ON) -> Item(line.substring(FLAG_ON.length), enabled = true)
                else -> Item(line, enabled = true)          // 老存档：一律视为启用
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

    /** 只要**已启用**的条目文字 —— 气泡轮播用这个 */
    fun enabledTexts(ctx: Context): List<String> =
        items(ctx).filter { it.enabled }.map { it.text }

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
                if (n >= MAX_CHARS) return@InputFilter src.subSequence(0, i).toString()
                n++
            }
            i += Character.charCount(cp)
        }
        null
    }
}
