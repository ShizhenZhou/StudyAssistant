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
val appVersionName = "0.3.5"

android {
    namespace = "com.zsz.studyassistant"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.zsz.studyassistant"
        minSdk = 28
        targetSdk = 37
        versionCode = 6
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation(libs.compose.ui.tooling.preview)

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

// APK naming: StudyAssistant-<version>-<yyyyMMddHHmm>.apk
tasks.whenTaskAdded {
    if (name == "assembleDebug") {
        doLast {
            try {
                val src = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
                if (src.exists()) {
                    val ts = SimpleDateFormat("yyyyMMddHHmm").format(Date())
                    val name = "StudyAssistant-$appVersionName-$ts.apk"
                    src.copyTo(src.parentFile.resolve(name), overwrite = true)
                    println("APK named: $name")
                }
            } catch (e: Exception) {
                // ignore rename failure
            }
        }
    }
}
