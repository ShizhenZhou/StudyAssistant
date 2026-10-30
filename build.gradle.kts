buildscript {
    repositories {
        maven("https://maven.aliyun.com/repository/central")
        mavenCentral()
    }
    dependencies {
        // Keep AGP's built-in Kotlin compiler aligned with Compose and serialization.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
