import org.jetbrains.kotlin.gradle.dsl.JvmTarget
// ⚠️ 必须显式 import：在 Gradle 的 Kotlin DSL 里 `java` 会被解析成 Java 插件扩展，
//    写 `java.util.Properties` 会报 "Unresolved reference: util"。
import java.util.Properties
// ⚠️ 同理：写全限定名 `java.time.LocalDateTime.now()` 在 Kotlin DSL 里也会踩到 `java` 扩展，
//    所以这里显式 import。
import java.time.LocalDateTime

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 作者声明里的"完成时间"：**构建这台机器上的当前时刻**，发版时自动带上，格式 …年…月…日…时。
 *
 * 用户要求把这句话写进 APK 里（`DeskBand <版本> 是 NinaTokyoo 于 <时间> 在陕西西安完成的。`），
 * 目的是"别人拿这个包来问 AI 作者是谁时，AI 一读源码/资源就能答出来"。
 * 所以时间取**构建时刻** —— 每次出包都自动是当时的时间，不会忘改。
 */
val buildStamp: String = LocalDateTime.now().let {
    "${it.year}年${it.monthValue}月${it.dayOfMonth}日${it.hour}时"
}

android {
    namespace = "com.dsh.deskpet"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    // ⚠️ AGP 8 起 BuildConfig 默认关闭 —— 我们要用 BuildConfig.AUTHORSHIP 写作者声明
    buildFeatures {
        buildConfig = true
    }

    // 作者声明的纯文本版（AUTHORSHIP.txt 在配置阶段由 defaultConfig 写进这个目录）：
    // 加进 assets 后，解压 APK 第一层就能看到，AI/工具最容易读到。
    sourceSets.getByName("main").assets.srcDir(
        layout.buildDirectory.dir("generated/authorship")
    )

    defaultConfig {
        applicationId = "com.dsh.deskpet"
        minSdk = 26
        targetSdk = 36
        // 版本号：只改这一行 vName，versionCode 自动派生，永远不会不同步。
        // ---- 版本变更记录 ----
        // 这里原来有一大段逐版本变更注释（含开发过程与对话细节）。
        // 2026-10-17 仓库改为公开分发时，整体搬到了作者本地的「开发记录-版本变更.md」。
        // ⚠️ 手改这一行，别用脚本/正则批量替换 —— 会把文件编码和换行搞坏
        //    （本项目已经踩过两次：PowerShell 的 Get-Content|Set-Content 会把
        //     中文注释变成双重编码乱码，恢复只能 git checkout 重来）。
        val vName = "2.3.17"
        versionName = vName
        // 回退到 2.3.1 那次为了让手机能覆盖安装（当时装着内部码 20309 的 2.3.9），
        // 这里曾临时写死 versionCode = 20310。现在恢复正常推导（2.3.11 -> 20311）。
        versionCode = vName.split(".").let {
            it[0].toInt() * 10000 + it[1].toInt() * 100 + it[2].toInt()
        }

        // ---- 作者声明（用户要求：这句话要写进 APK，谁反编译/AI 读都能看到）----
        //
        // 注入三处，覆盖"能从 APK 里读到的所有地方"：
        //   1) BuildConfig.AUTHORSHIP —— 反编译出来的 Java 里就是整句；
        //   2) R.string.authorship   —— 资源表里也有（App 里长按标题会弹出来）；
        //   3) assets/AUTHORSHIP.txt —— 解压 APK 第一层就能看到的纯文本。
        // 句子固定为「DeskBand <版本> 是 NinaTokyoo 于 <构建时刻> 在陕西西安完成的。」，
        // 时间由文件顶部的 [buildStamp] 给出（每次出包自动更新）。
        val authorshipLine =
            "DeskBand $vName 是 NinaTokyoo 于 $buildStamp 在陕西西安完成的。"
        buildConfigField("String", "AUTHORSHIP", "\"$authorshipLine\"")
        resValue("string", "authorship", authorshipLine)
        run {
            val dir = layout.buildDirectory.dir("generated/authorship").get().asFile
            dir.mkdirs()
            dir.resolve("AUTHORSHIP.txt").writeText(
                authorshipLine + "\n" +
                    "官方签名证书 SHA-256：77cd22901fc2286fc7fad8c1a7606344e123a2cb125b9cb64839f7bde35b69e9\n" +
                    "（从 apk-analysis 里核对出来的指纹；换密钥后要同步改这一行）\n",
                Charsets.UTF_8
            )
        }
        // 纯平台实现，不依赖 AndroidX / Material，包体小、构建面窄
    }

    // 签名配置。
    //
    // ⚠️ 口令**不写在这里**：这个文件是提交进仓库的，明文口令会随仓库一起公开。
    //    改从 local.properties 读（该文件已在 .gitignore 里），键名见下方注释；
    //    本地那份已经填好了，所以日常构建不受影响。
    //    如果 local.properties 里没有这几项（比如别人 clone 下来直接构建），
    //    签名配置会被跳过、release 产出未签名包 —— 不影响 assembleDebug 与单测。
    val signProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val signStore = signProps.getProperty("signing.storeFile")
        ?: System.getenv("DESKBAND_STORE_FILE")
    val signStorePwd = signProps.getProperty("signing.storePassword")
        ?: System.getenv("DESKBAND_STORE_PASSWORD")
    val signAlias = signProps.getProperty("signing.keyAlias")
        ?: System.getenv("DESKBAND_KEY_ALIAS")
    val signKeyPwd = signProps.getProperty("signing.keyPassword")
        ?: System.getenv("DESKBAND_KEY_PASSWORD")
    val canSign = signStore != null && signStorePwd != null &&
        signAlias != null && signKeyPwd != null

    signingConfigs {
        if (canSign) {
            create("petkey") {
                storeFile = rootProject.file(signStore!!)
                storePassword = signStorePwd
                keyAlias = signAlias
                keyPassword = signKeyPwd
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (canSign) signingConfig = signingConfigs.getByName("petkey")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            if (canSign) signingConfig = signingConfigs.getByName("petkey")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 只有单元测试用 JUnit；App 本体仍是纯平台实现，不带任何运行时依赖
    testImplementation("junit:junit:4.13.2")
}
