import java.text.SimpleDateFormat
import java.util.Date
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
}

// 应用版本（供 versionName 与 APK 命名使用）
val appVersionName = "0.5.7"

android {
    namespace = "com.zsz.studyassistant"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.zsz.studyassistant"
        minSdk = 28
        targetSdk = 37
        versionCode = 31
        versionName = appVersionName
    }

    buildTypes {
        release {
            // R8 代码裁剪/混淆 + 资源压缩（仅 release 变体；debug 保持可调试）
            isMinifyEnabled = true
            isShrinkResources = true
            // 用 debug 签名（与已安装版本同签名，便于覆盖安装）
            signingConfig = signingConfigs.getByName("debug")
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
