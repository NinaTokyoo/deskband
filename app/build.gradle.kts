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
        //   1.3.10 -> 10310（可互动面积缩到 alpha 外接框的 75%）
        //   1.3.11 -> 10311（四个角落的可互动面积保持 100%，只缩非角落状态）
        //   1.3.12 -> 10312（贴底边/下角：点击压缩改"底边不动、只压上边"；新增长按一直压到 50%、松手回弹）
        //   1.3.13 -> 10313（长按松手改为"回弹 + 向上弹起"，弹起高度与压缩程度成正比）
        //   1.3.14 -> 10314（单击与长按弹起落地后都加一次回弹）
        //   1.3.15 -> 10315（单击落地回弹减轻一半；长按落地回弹深度与按压时间成正比）
        //   1.3.16 -> 10316（压缩/弹起泛化到四条边：判据改成"贴哪条屏幕边"，旋转由 canvas 负责）
        //   1.3.17 -> 10317（吸附静止 0.5s 后加呼吸晃动；吸附边全程不动）
        //   1.3.18 -> 10318（呼吸去掉延迟立刻开始；新增「呼出回弹」「呼吸」两张卡片；全部卡片提高信息密度）
        //   2.0.00 -> 20000（悬浮窗权限 / 通知权限拆成独立卡片，并挪到整个页面的最底下；
        //                     排版与原样一致，其余不变。基线仍是干净的 1.3.18）
        //   2.0.01 -> 20001（切换形象卡片改成"框 + 二级菜单"：名称与倒三角同框、
        //                     点整框展开其余角色、行间横线、展开时三角正立）
        // 规律：去掉小数点当整数（单调递增）。发版时把这行末位 +1 即可。
        // ⚠️ 手改这一行，别用脚本/正则批量替换 —— 会把文件编码和换行搞坏。
        val vName = "2.0.01"
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
