import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsh.deskpet"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.dsh.deskpet"
        minSdk = 26
        targetSdk = 36
        // 版本号：只改这一行 versionName，versionCode 自动派生，永远不会不同步。
        //   1.1.02 -> 10102
        //   1.2.00 -> 10200
        // 规律：去掉小数点当整数（单调递增）。
        // 下次发版改这里即可，也可以跑 tools/bump_version.ps1 自动 +1。
        val vName = "1.1.02"
        versionName = vName
        versionCode = vName.split(".").let {
            it[0].toInt() * 10000 + it[1].toInt() * 100 + it[2].toInt()
        }
        // 纯平台实现，不依赖 AndroidX / Material，包体小、构建面窄
    }

    signingConfigs {
        create("petkey") {
            storeFile = rootProject.file("keystore/deskpet.jks")
            storePassword = "deskpet2026"
            keyAlias = "deskpet"
            keyPassword = "deskpet2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("petkey")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("petkey")
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
