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
        // versionCode 与 versionName 的对应关系（手写递增即可）：
        //   1.1.01 -> 101
        //   1.1.02 -> 102
        //   1.2.00 -> 200
        // 规律：去掉小数点当作整数，保证单调递增。
        versionCode = 101
        versionName = "1.1.01"
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
