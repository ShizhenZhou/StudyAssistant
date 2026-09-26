import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
}

// 应用版本（供 versionName 与 APK 命名使用）
// 版本号约定（2026-09-25 用户确定）：
//   · `0.x.x`   —— 每次都要在 README「版本记录」+ App 内「更新内容」记一次
//   · `0.x.x.x` —— 同系列小修补（patch），**不需要**改「更新内容」，发 Release 即可
//   · `versionCode` **每次发版必须 +1**（Android 判定版本高低/能否覆盖安装的权威是 versionCode，不是 versionName）
val appVersionName = "0.6.3"

// ── 发布签名材料（自有证书 + key rotation）──────────────────────────────────
// 从 secrets.properties（已 gitignore）读取；**文件缺失时回退到默认 debug 签名**，
// 这样在没有密钥的机器/CI 上仍能正常构建（只是签出来的包无法覆盖已装的新证书版本）。
// 详见 README「发布签名（自有证书 + key rotation）」。
// ⚠️ 两个必须注意的点（都踩过）：
//   ① 写全限定名 `java.util.Properties` 会被解析成 `java` 扩展 → 必须用顶部 import
//   ② 必须用 `load(Reader)` 按 UTF-8 读：`load(InputStream)` 按 ISO-8859-1 解码，
//      路径里的非 ASCII 字符（例如中文目录名）会被搞坏 → 签名材料判定为"无效"而悄悄回退到 debug 签名
val signingProps = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
}
val releaseStorePath = signingProps.getProperty("RELEASE_STORE_FILE")?.takeIf { it.isNotBlank() }
val releaseStorePass = signingProps.getProperty("RELEASE_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias = signingProps.getProperty("RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val hasReleaseSigning = releaseStorePath != null && releaseStorePass != null &&
        releaseKeyAlias != null && file(releaseStorePath).exists()
// 配置期打一行日志：签名材料没吃到时最容易"悄悄回退"，导致打出来的包装不上去
if (hasReleaseSigning) {
    logger.lifecycle("[签名] 使用自有证书 alias=$releaseKeyAlias（${file(releaseStorePath!!).name}）")
} else {
    logger.lifecycle("[签名] ⚠️ 未启用自有证书签名（secrets.properties 缺失或路径无效）→ 回退 debug 签名；" +
            "产出的包无法覆盖已安装的新证书版本。解析到的 RELEASE_STORE_FILE=${releaseStorePath ?: "(空)"}")
}

android {
    namespace = "com.zsz.studyassistant"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.zsz.studyassistant"
        minSdk = 28
        targetSdk = 37
        versionCode = 42
        versionName = appVersionName
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePass
                keyAlias = releaseKeyAlias
                keyPassword = releaseStorePass
            }
        }
    }

    buildTypes {
        debug {
            // ★ debug 变体也使用同一份自有证书：这样 debug / release 可以**互相覆盖安装**。
            //   2026-09-25 起 release 已轮换到自有证书，若 debug 还用 debug 证书就会装不上去
            //   （`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
        release {
            // R8 代码裁剪/混淆 + 资源压缩（仅 release 变体；debug 保持可调试）
            isMinifyEnabled = true
            isShrinkResources = true
            // 有自有证书就用它（最终发布还要用 tools/sign-release.ps1 补 lineage）；
            // 没有则回退到 debug 签名，保证构建不中断
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        // 单元测试是纯 JVM 的：只测解析/版本比较/文案表这类**不碰 Android 实现**的纯逻辑。
        // 万一某条路径碰到 android.* 的桩方法，返回默认值而不是抛 "not mocked"，避免测试假失败。
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    // 预览工具仅在 debug 变体需要（代码中未使用 @Preview，正式包不打包）
    debugImplementation(libs.compose.ui.tooling.preview)

    // 相机
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // 网络
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // 本地存储（错题本）
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    // 单元测试（纯 JVM）：答案解析、版本比较、文案表 —— 见 app/src/test
    testImplementation(libs.junit)
}

// APK naming: StudyAssistant-<version>-<yyyyMMddHHmm>.apk（debug / release 各自目录）
tasks.whenTaskAdded {
    if (name == "assembleDebug" || name == "assembleRelease") {
        doLast {
            try {
                val variant = if (name == "assembleDebug") "debug" else "release"
                val src = layout.buildDirectory.file("outputs/apk/$variant/app-$variant.apk").get().asFile
                if (src.exists()) {
                    val ts = SimpleDateFormat("yyyyMMddHHmm").format(Date())
                    val newName = "StudyAssistant-$appVersionName-$ts.apk"
                    src.copyTo(src.parentFile.resolve(newName), overwrite = true)
                    println("APK named: $variant/$newName")
                }
            } catch (e: Exception) {
                // ignore rename failure
            }
        }
    }
}
