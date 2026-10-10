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
        // 版本号：只改这一行 vName，versionCode 自动派生，永远不会不同步。
        //   1.1.05 -> 10105
        //   1.2.00 -> 10200
        //   1.3.00 -> 10300（新增上边吸附：四个角 + 四条边全部可吸附）
        //   1.3.01 -> 10301（权限前的对号/错号改为黑白）
        //   1.3.02 -> 10302（上边带收窄为"离上沿 240px 以内"，其余上方区域同下边）
        //   1.3.03 -> 10303（旋转只走最小角度；位置保存改认"落点"，修连点漂移）
        //   1.3.04 -> 10304（点一下不再打断吸附补间；服务兜底不许 applySnap，杜绝瞬移到右下角）
        //   1.3.05 -> 10305（角落边界加滞回修素材闪烁；落点按目标素材算；呼出看门狗带世代号修"点收起没反应"）
        //   1.3.06 -> 10306（呼出时窗口按存档位置创建、包络与 cacheSizes 一致；躺侧边标准位置改回垂直居中）
        //   1.3.07 -> 10307（呼出动画改两段曲线：进场 easeInArrive + 回弹 springBack 阻尼摆动）
        //   1.3.08 -> 10308（收起方向取最近边，修"贴右边框却竖着往下滑出屏幕"；呼出滞空缩短；松手回弹也用阻尼摆动）
        //   1.3.09 -> 10309（呼出过冲量按行程放大、回弹时长用用户的"回弹时长"，与靠边吸附同一套 Q 弹手感）
        // 规律：去掉小数点当整数（单调递增）。发版时把这行末位 +1 即可。
        // ⚠️ 手改这一行，别用脚本/正则批量替换 —— 会把文件编码和换行搞坏。
        val vName = "1.3.9"
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
