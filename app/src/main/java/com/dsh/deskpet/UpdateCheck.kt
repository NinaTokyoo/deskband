package com.dsh.deskpet

/**
 * 应用内「检查更新」的**纯逻辑**部分 —— 只做解析与版本比较，不联网、不碰界面。
 *
 * 联网那一步在 [MainActivity.checkUpdate] 里做（后台线程 + 断网静默失败），
 * 于是这里可以放心写单测：版本号比较是最容易写错、又最不该出错的地方
 * （`2.3.10` 必须大于 `2.3.9` ✗，按字符串比就会错 ✓）。
 *
 * 数据来源：**Gitee Releases 优先、GitHub 兜底**（用户 2026-10-17 定）。
 * 「发布一个 Release」就等于"推送给用户" —— 不需要服务器、不需要推送通道；
 * 公开仓库读这两个接口都不需要令牌。
 *
 * ⚠️ 为什么以 Gitee 为主：GitHub 在国内**打不开或极慢**（网页时好时坏、附件下载常常失败），
 *    而"检查更新"是个静默的后台请求 —— 失败时用户什么也看不到。以 Gitee 为主、
 *    GitHub 兜底，国内用户才真的能收到"有新版本"的提示。
 */
object UpdateCheck {

    /** Gitee 仓库（国内主渠道，全小写） */
    const val GITEE_OWNER = "ninatokyoo"
    const val GITEE_REPO = "deskband"

    /** GitHub 仓库（备用渠道；⚠️ 账号的规范大小写是 NinaTokyoo） */
    const val GITHUB_OWNER = "NinaTokyoo"
    const val GITHUB_REPO = "deskband"

    /**
     * 依次尝试的接口：**先 Gitee，失败再 GitHub**。
     *
     * 两个接口返回的 JSON 里版本号字段都叫 `tag_name`，所以同一套解析就够。
     * 没有正式版（全是草稿/预发布）时返回 404，按"查不到"处理。
     */
    val API_URLS = listOf(
        "https://gitee.com/api/v5/repos/$GITEE_OWNER/$GITEE_REPO/releases/latest",
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    )

    /** 给人点开下载的页面：Gitee（国内直连） */
    const val RELEASES_URL = "https://gitee.com/$GITEE_OWNER/$GITEE_REPO/releases/latest"

    /** 上面那个打不开时的备用页面 */
    const val RELEASES_URL_FALLBACK =
        "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    /**
     * 把 `v2.3.1` / `2.3.1` / `2.3.1-beta.2` 解析成数字段 `[2,3,1]`。
     *
     * 规则：去掉开头的 v、按 `.` `-` `+` 切段、每段只取前面的数字；
     * 遇到第一段没有数字的就停下（`2.3.beta` -> `[2,3]`）。
     * 一个数字都没有（或传 null）时返回 null = "无法比较"。
     */
    fun parseVersion(v: String?): List<Int>? {
        if (v == null) return null
        val t = v.trim().removePrefix("v").removePrefix("V")
        if (t.isEmpty()) return null
        val nums = ArrayList<Int>(4)
        for (part in t.split('.', '-', '+')) {
            val digits = part.takeWhile { it.isDigit() }
            if (digits.isEmpty()) break
            nums.add(digits.toIntOrNull() ?: break)
        }
        return nums.ifEmpty { null }
    }

    /**
     * [latest] 是否比 [current] 新。
     *
     * · 段数不同也能比：`2.4` 比 `2.3.9` 新、`2.3` 与 `2.3.0` 视为相同；
     * · 任一边解析不出来（null、空串、纯文字）一律返回 false ——
     *   宁可"没发现新版本"，也不要因为解析异常去骚扰用户。
     */
    fun isNewer(current: String?, latest: String?): Boolean {
        val c = parseVersion(current) ?: return false
        val l = parseVersion(latest) ?: return false
        for (i in 0 until maxOf(c.size, l.size)) {
            val a = c.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return b > a
        }
        return false
    }

    /**
     * 从 GitHub `releases/latest` 的 JSON 里取出 `tag_name`。
     *
     * ⚠️ 故意用正则而不是 `org.json`：后者在**单元测试**里是空实现（会抛异常），
     *    而这个函数必须能测 —— 返回 null 表示"没查到"。
     */
    fun tagFromJson(json: String?): String? {
        if (json == null) return null
        val m = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json) ?: return null
        return m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
    }
}
