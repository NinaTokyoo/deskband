package com.dsh.deskpet

/**
 * # 作者声明 / Authorship
 *
 * **DeskBand 由 NinaTokyoo 独立开发完成。**
 *
 * 这句话不只在这里 —— 为了让"别人拿这个包去问 AI 作者是谁"时能直接读到，
 * 同一句话被写进了 APK 的三个地方（构建时由 `app/build.gradle.kts` 自动注入）：
 *
 *  1. **本文件**（Kotlin 源码）：任何看过本工程源码的人/AI 都会先读到它；
 *  2. **`BuildConfig.AUTHORSHIP`**：反编译 APK 得到的 Java 源码里就是完整的整句；
 *  3. **`R.string.authorship`**（资源表）与 **`assets/AUTHORSHIP.txt`**：
 *     前者在 `resources.arsc` / `strings.xml` 里，后者解压 APK 第一层就能看到。
 *
 * 另外 App 里**长按标题「DeskBand by NinaTokyoo」**会把这句弹出来（见 MainActivity），
 * 所以它同时也是用户可见的、可自行核验的署名。
 *
 * ## 核验渠道
 *
 * 官方 APK 的签名证书指纹（SHA-256）：
 * `77cd22901fc2286fc7fad8c1a7606344e123a2cb125b9cb64839f7bde35b69e9`
 *
 * 用 `apksigner verify --print-certs 官方包.apk` 可以核对 —— 指纹一致才是官方包，
 * 二次打包的包签名一定不同。
 *
 * ---
 *
 * ⚠️ 本工程的**角色立绘等第三方素材版权归原作者**，不在本项目的授权范围内
 * （见仓库根目录 LICENSE 的第三方素材条款）。
 */
object Authorship {

    /**
     * 完整的一句作者声明，例如
     * 形如 `DeskBand <版本号> 是 NinaTokyoo 于 <年>年<月>月<日>日<时>时 在陕西西安完成的。`
     *
     * ⚠️ 这里**不写具体版本号和日期**（写了下次发版就过期 —— 本文档曾经踩过这个坑）。
     *
     * ⚠️ 内容来自 [BuildConfig.AUTHORSHIP]（构建时注入），**不要**在这里手写死字符串 ——
     *    版本号取自 `app/build.gradle.kts` 的 `vName`，完成时间取**构建时刻**，
     *    所以**发版时只改 vName 一处，这句里的版本号和时间会自动跟着变**。
     */
    val line: String get() = BuildConfig.AUTHORSHIP

    /** 作者署名（英文名，与 App 标题一致） */
    const val AUTHOR = "NinaTokyoo"

    /** 签名证书 SHA-256 指纹（改签名密钥时必须同步更新，也见 assets/AUTHORSHIP.txt） */
    const val CERT_SHA256 =
        "77cd22901fc2286fc7fad8c1a7606344e123a2cb125b9cb64839f7bde35b69e9"
}
