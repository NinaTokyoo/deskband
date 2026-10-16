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

    /** 转义后按行拼接：[decode] 的逆运算 */
    fun encode(items: List<String>): String = items.joinToString("\n") {
        it.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
    }

    /** 解析 [encode] 写出的串；空串 = 空列表 */
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

    fun list(ctx: Context): List<String> =
        decode(Prefs(ctx).bubbleTextsRaw).take(MAX_ITEMS)

    /** 覆盖式写入；超过条数上限的部分直接丢掉（界面本来就不让加） */
    fun save(ctx: Context, items: List<String>) {
        Prefs(ctx).bubbleTextsRaw = encode(items.take(MAX_ITEMS))
    }

    /** 追加一条并返回新列表；已达上限或内容为空时原样返回（由界面提示） */
    fun add(ctx: Context, text: String): List<String> {
        val t = fit(text.trim())
        val cur = list(ctx)
        if (t.isEmpty() || cur.size >= MAX_ITEMS) return cur
        val next = cur + t
        save(ctx, next)
        return next
    }

    /** 删掉第 [index] 条（越界则原样返回） */
    fun removeAt(ctx: Context, index: Int): List<String> {
        val cur = list(ctx)
        if (index !in cur.indices) return cur
        val next = cur.toMutableList().also { it.removeAt(index) }
        save(ctx, next)
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
