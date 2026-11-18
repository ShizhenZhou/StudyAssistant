import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// 应用版本（供 versionName 与 APK 命名使用）
// 版本号约定（2026-09-25 用户确定）：
//   · `0.x.x`   —— 每次都要在 README「版本记录」+ App 内「更新内容」记一次
//   · `0.x.x.x` —— 同系列小修补（patch），也要在 README + App 四语言「更新内容」记录
fun readDistributionProperties(file: File) = Properties().apply {
    file.reader(Charsets.UTF_8).use(::load)
}
val distributionFlavors = mapOf("modern" to "main", "compat" to "compat")
require(!providers.gradleProperty("distribution").isPresent) {
    "Use an explicit modern/compat task instead of -Pdistribution"
}
val distributionProfiles = distributionFlavors.values.associateWith { name ->
    readDistributionProperties(rootProject.file("config/distributions/$name.properties")).also { profile ->
        require(profile.getProperty("distribution") == name)
        val min = profile.getProperty("minSdk").toInt()
        val max = profile.getProperty("maxSdk").toInt()
        require(if (name == "main") min == 36 && max == 0 else min == 28 && max == 35)
        require(profile.getProperty("versionCode").toInt() > 0)
        val version = profile.getProperty("versionName")
        require(version.matches(Regex("[0-9]+(?:\\.[0-9]+){2,3}(?:_beta(?:[1-9][0-9]*)?)?(?:_Compat)?")))
        require(name == "compat" || !version.endsWith("_Compat"))
    }
}

// Generated only when build inputs change; one UTC identity is shared by embedded metadata and APK names.
abstract class GenerateBuildStamp : DefaultTask() {
    @get:org.gradle.api.tasks.Input
    abstract val distributionMetadata: Property<String>
    @get:org.gradle.api.tasks.InputFiles
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection
    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputDirectory: DirectoryProperty
    @org.gradle.api.tasks.TaskAction
    fun generate() {
        val directory = outputDirectory.get().asFile.apply { mkdirs() }
        val stamp = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
            .withZone(ZoneOffset.UTC).format(Instant.now())
        directory.resolve("build-time-utc.txt").writeText(stamp, Charsets.UTF_8)
        directory.resolve("distribution.json").writeText(distributionMetadata.get(), Charsets.UTF_8)
    }
}
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
    if (System.getenv("CI") != "true" && f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
}
val releaseStorePath = signingProps.getProperty("RELEASE_STORE_FILE")?.takeIf { it.isNotBlank() }
val releaseStorePass = signingProps.getProperty("RELEASE_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias = signingProps.getProperty("RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val hasReleaseSigning = releaseStorePath != null && releaseStorePass != null &&
        releaseKeyAlias != null && file(releaseStorePath).exists()
// Only local debug builds may use the existing local identity. Release signing is a separate local operation.
logger.lifecycle(if (hasReleaseSigning) "[Signing] Local debug identity available; release candidate is unsigned."
    else "[Signing] Temporary debug identity only; release candidate is unsigned.")

android {
    namespace = "com.zsz.studyassistant"
    compileSdk = 37
    buildToolsVersion = libs.versions.androidBuildTools.get()

    defaultConfig {
        applicationId = "com.zsz.studyassistant"
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"
    productFlavors {
        distributionFlavors.forEach { (flavor, name) ->
            create(flavor) {
                dimension = "distribution"
                val profile = distributionProfiles.getValue(name)
                minSdk = profile.getProperty("minSdk").toInt()
                versionCode = profile.getProperty("versionCode").toInt()
                versionName = profile.getProperty("versionName")
                buildConfigField("String", "DISTRIBUTION", "\"$name\"")
                buildConfigField("int", "SUPPORTED_MAX_SDK", profile.getProperty("maxSdk"))
                buildConfigField("String", "SUPPORTED_ANDROID_LABEL", "\"${profile.getProperty("androidLabel")}\"")
            }
        }
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

    testBuildType = "integration"
    buildTypes {
        create("integration") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".integration"
            versionNameSuffix = "-integration"
            matchingFallbacks += listOf("debug")
        }
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
            // CI/local builds produce an unsigned candidate; official keys are used only by the local signing step.
            signingConfig = null
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
        buildConfig = true
    }

    distributionFlavors.forEach { (flavor, name) ->
        sourceSets.getByName(flavor) {
            kotlin.directories += "src/distributions/$name/java"
            assets.srcDir("src/distributions/$name/assets")
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // These unused PQC algorithms are removed by R8; retain all PDF fonts and active crypto resources.
        resources.excludes += setOf(
            "org/bouncycastle/pqc/crypto/picnic/lowmc.properties",
            "org/bouncycastle/pqc/crypto/sike/p434.properties",
            "org/bouncycastle/pqc/crypto/sike/p503.properties",
            "org/bouncycastle/pqc/crypto/sike/p610.properties",
            "org/bouncycastle/pqc/crypto/sike/p751.properties"
        )
    }

    testOptions {
        // 单元测试是纯 JVM 的：只测解析/版本比较/文案表这类**不碰 Android 实现**的纯逻辑。
        // Unexpected Android calls must fail; framework behavior belongs in isolated device tests.
        unitTests.isReturnDefaultValues = false
    }
}

androidComponents {
    onVariants { variant ->
        val flavor = variant.productFlavors.single { it.first == "distribution" }.second
        val name = distributionFlavors.getValue(flavor)
        val profile = distributionProfiles.getValue(name)
        val min = profile.getProperty("minSdk")
        val max = profile.getProperty("maxSdk").toInt()
        val metadata = """{"distribution":"$name","minSdk":$min${if (max > 0) ",\"maxSdk\":$max" else ""}}"""
        val stamp = tasks.register<GenerateBuildStamp>("generate${variant.name.replaceFirstChar { it.uppercaseChar() }}BuildStamp") {
            distributionMetadata.set(metadata)
            sourceFiles.from(fileTree("src/main"), fileTree("src/distributions/$name"),
                fileTree("src/${variant.buildType}"), rootProject.file("config/distributions/$name.properties"),
                file("build.gradle.kts"), rootProject.file("gradle/libs.versions.toml"),
                rootProject.file("gradle.properties"), rootProject.file("build.gradle.kts"), rootProject.file("settings.gradle.kts"))
            outputDirectory.set(layout.buildDirectory.dir("generated/build-stamp/${variant.name}/assets"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(stamp) { it.outputDirectory }
    }
    onVariants(selector().withBuildType("release")) { variant ->
        // Direct APK downloads benefit from compressed DEX. ART extracts it at install time,
        // trading additional installed storage for a smaller download; keep JNI packaging unchanged.
        variant.packaging.dex.useLegacyPackaging.set(true)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// All variants use the same database schema. Track the directory for KSP's incremental inputs.
class RoomSchemaArgProvider(
    @get:org.gradle.api.tasks.InputDirectory
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    val schemaDir: File
) : org.gradle.process.CommandLineArgumentProvider {
    override fun asArguments() = listOf("room.schemaLocation=${schemaDir.path}")
}

ksp {
    // Room 3 generates Kotlin DAOs; keep schema snapshots outside packaged assets.
    arg(RoomSchemaArgProvider(layout.projectDirectory.dir("schemas").asFile))
}

// The SQLite verifier consumes these extra test outputs. Include them in Gradle's
// task cache so a cached successful test run also restores the files it produced.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    if (name.endsWith("IntegrationUnitTest")) {
        val distributionName = if (name == "testCompatIntegrationUnitTest") "compat" else "main"
        // These tests read files directly instead of through the runtime classpath.
        // Declare them so README/HTML-only changes invalidate cached test results.
        inputs.files(rootProject.file("README.md"), project.file("build.gradle.kts"),
            rootProject.file("config/distributions/$distributionName.properties"),
            rootProject.fileTree("docs/readme") { include("README*.md") },
            rootProject.fileTree("docs/compatible") { include("README*.md") })
            .withPropertyName("documentationInputs")
            .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
        inputs.dir(layout.projectDirectory.dir("src/main/assets"))
            .withPropertyName("renderAssets")
            .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
        inputs.dir(layout.projectDirectory.dir("src/distributions/$distributionName/assets"))
            .withPropertyName("distributionAssets")
            .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
        outputs.file(layout.buildDirectory.file("identity-work/migration-plan.json"))
            .withPropertyName("identityMigrationPlan")
        outputs.file(layout.buildDirectory.file("identity-work/tag-merge-schema.sql"))
            .withPropertyName("tagUndoSchema")
    }
}

dependencies {
    implementation(libs.pdfbox.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.work.runtime)
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
    implementation(libs.room.wrapper)
    implementation(libs.sqlite.framework)
    ksp(libs.room.compiler)

    // 单元测试（纯 JVM）：答案解析、版本比较、文案表 —— 见 app/src/test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

// APK names remain variant-qualified; release tooling names the signed public artifacts.
