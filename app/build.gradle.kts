import org.jetbrains.kotlin.gradle.dsl.JvmTarget
// ⚠️ 必须显式 import：在 Gradle 的 Kotlin DSL 里 `java` 会被解析成 Java 插件扩展，
//    写 `java.util.Properties` 会报 "Unresolved reference: util"。
import java.util.Properties

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
        //   2.0.02 -> 20002（二级菜单宽度收到卡片左侧 2/3 处、整个展开区加描边、
        //                     与名称框连成一整块；展开期间收起右下角人物避免重叠）
        //   2.0.03 -> 20003（名称框与二级菜单统一为卡片左侧 2/3 同宽；
        //                     人物素材始终显示在卡片右下角，展开时不再收起）
        //   2.0.04 -> 20004（二级菜单加展开/收起动画：高度从 0 长开 +
        //                     每行依次淡入；收起反向、更干脆；三角旋转同步）
        //   2.0.05 -> 20005（修动画卡顿与收起抽搐：
        //                     动画期间不再每帧改高度（改 translationY，零布局）；
        //                     收起距离改取 skinMenu.height（原来取容器高度，
        //                     而它此时是 0，导致"先瞬移再动画"））
        //   2.0.06 -> 20006（按用户要求**取消**菜单展开/收起动画，回到即时开关。
        //                     连带删掉动画专用的 ValueAnimator、时长常量与两个辅助函数；
        //                     状态复位逻辑保留 —— 那不是动画，是正确性）
        //   2.0.07 -> 20007（外观模式由下拉框改成一行三个按钮（跟随系统/浅色/深色）；
        //                     选中态 = 主题色文字 + 镂空底色，未选中 = 常规文字 + 同款镂空；
        //                     顺带清理死代码：dropdownBackground / ThemedAdapter /
        //                     SpinnerPopupAnim —— 两个下拉框都没了，它们再无调用点）
        //   2.0.08 -> 20008（重画启动图标：小生物扒在桌边上（用户选定方向），
        //                     配色沿用深蓝底 + 青色；前景全部改用三次贝塞尔）
        //   2.0.09 -> 20009（换成用户提供的成套素材：应用图标用 1254x1254 满幅图、
        //                     开屏用带透明圆角的 623x698/628x704 图；
        //                     深浅两套走 night 资源限定符（跟随系统）；
        //                     应用内卡片顺序布局等一律未动）
        //   2.0.10 -> 20010（撤掉开屏（用户：冷启动很快，没必要）；
        //                     图标改成**不裁切**：自适应图标用"纯色铺底 + 整图缩进安全区"，
        //                     老式位图用整图铺满；两处都不再裁图）
        //   2.0.11 -> 20011（修浅色模式冷启动闪两帧藏青：AppTheme 的 windowBackground
        //                     换成 @color/launch_bg —— values 里纯白、values-night 里
        //                     保持深藏青。窗口一建出来就是对的颜色，没有"先闪再改"）
        //   2.0.12 -> 20012（图标改成**直接用整张位图**、彻底放弃自适应图标：
        //                     自适应图标要么裁图（铺满 viewport），要么用纯色铺底，
        //                     而浅色那张图四角颜色并不一致，铺底会在圆角外露出
        //                     一圈可见色差边（用户反馈的"粉色框"）。
        //                     现在不裁切、不拼接、无色差）
        //   2.0.13 -> 20013（发布前安全整改：签名口令从 app/build.gradle.kts 挪到
        //                     local.properties（已被 .gitignore 排除）；
        //                     该文件会进仓库，明文口令会随仓库公开）
        //   2.0.14 -> 20014（通知栏标题可在 App 内自定义：用 {name} 表示角色名，
        //                     想插在哪里就写哪里；留空用默认。
        //                     默认措辞按用户要求由「正在陪伴你」改为「正在陪你」。
        //                     拼接逻辑抽成 PetState.buildTitle 纯函数并补 9 个单测）
        //   2.0.15 -> 20015（按用户要求：标题输入框去掉提示词；
        //                     默认文案改回「<角色名> 正在陪伴你」）
        //   2.0.16 -> 20016（通知文案留空保存时自动回填：标题留空填默认模板、
        //                     正文留空填当前标题；回填规则抽成 PetState.fillNotifyTexts
        //                     纯函数并补 6 个单测）
        //   2.0.17 -> 20017（通知标题：**存模板、显示时按当前角色解析** ——
        //                     输入框里看到的是实际名字，换角色/重开 App 都会跟着变；
        //                     保存时把当前角色名反向换成 {name}；旧的字面名字值
        //                     自动迁移成模板。标题与正文的显示/存储互转见 PetState）
        //   2.0.20 -> 20020（按四路专项审查做的一批修复，详见提交信息：
        //                     位图降采样与缓存键归一化、呼出复用 sprite、
        //                     看门狗按实际时长算、窗口 attach 判据、placeWindow
        //                     返回成功与否、亮屏重新排帧、旋转重算窗口、
        //                     通知刷新闸门改 alive、通知颜色跟随角色）
        //   2.0.18 -> 20018（通知卡片两个输入框的提示语按语义对调：
        //                     标题=留空使用默认文案、正文=留空使用当前标题）
        //   2.0.19 -> 20019（修通知**正文**里的 {name} 没解析：界面端解析了、
        //                     服务端漏了，通知里正文原样显示「{name} 正在陪伴你」。
        //                     另按用户要求：正文留空时直接复制当前标题）
        //   2.0.21 -> 20021（通知栏按呼出/收起状态区分：收起时标题固定为
        //                     「<角色名> 待呼出」、正文固定为「点击回到应用」，
        //                     自定义文案只作用于呼出状态；动作按钮由恒定的「收起」
        //                     改成按状态切换，收起时点它能把桌宠叫回来。
        //                     ⚠️ 2.0.21 这个版本号曾被「交互区域」用过又回滚，
        //                        这里重新启用，历史见 292a79d）
        //   2.0.22 -> 20022（呼吸卡片新增「呼吸速度倍率」0.1~10，与幅度**互相独立**：
        //                     周期 = 基准 2600ms / 速度；幅度倍率只管幅度。
        //                     ⚠️ 2.0.22 这个版本号曾被「交互区域」用过又回滚，
        //                        这里重新启用，历史见 292a79d）
        //   2.0.23 -> 20023（滑条只允许**按住滑块**拖动，点轨道不再改值；
        //   2.0.24 -> 20024（修「吸附状态下把尺寸调小，人物会悬浮而不吸附」：
        //                     reloadPet 里 setSizeFraction 之后的 restore 会用
        //                     没变过的旧 posLeft/posTop 重新摆位，把吸附边锚定覆盖掉）
        //                     修「收起状态下调节外观尺寸，人物会自己出现」；
        //                     「外观大小」->「外观尺寸」、「大小倍率」->「尺寸倍率」）
        // 规律：去掉小数点当整数（单调递增）。发版时把这行末位 +1 即可。
        // ⚠️ 手改这一行，别用脚本/正则批量替换 —— 会把文件编码和换行搞坏。
        val vName = "2.0.24"
        versionName = vName
        versionCode = vName.split(".").let {
            it[0].toInt() * 10000 + it[1].toInt() * 100 + it[2].toInt()
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
