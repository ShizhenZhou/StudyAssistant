package com.zsz.studyassistant.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 应用内更新：查 GitHub Releases → 下载 → **校验（SHA-256 + 签名证书）** → 交给系统安装器。
 *
 * 设计要点：
 *  - 仓库是**公开**的，所以不需要任何 token（未认证 API 限流 60 次/小时，自用足够）。
 *  - 每个 Release 约定**恰好 1 个 APK 附件**（见 README「发布签名」），所以优先挑 `StudyAssistant-*.apk`。
 *  - **双重校验**：
 *      ① SHA-256：与 GitHub Release API 里 asset 的 `digest` 字段（`sha256:…`）比对；
 *      ② 签名证书：读下载包的签名证书 SHA-256，与**内置的期望指纹**比对 → 防伪造包。
 *  - ⚠️ 期望指纹是硬编码常量：[EXPECTED_CERT_SHA256]。**将来若更换签名证书（key rotation 再换一次），
 *    必须同步改这里**，否则 App 会把合法的新包判定为「校验失败」。见 DEV_ENV_NOTES 的签名章节。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    const val REPO = "ShizhenZhou/StudyAssistant"
    const val REPO_URL = "https://github.com/$REPO"
    const val RELEASES_URL = "$REPO_URL/releases"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    /** 本项目 release 包的签名证书 SHA-256（小写十六进制）；换证书时必须同步修改 */
    const val EXPECTED_CERT_SHA256 = "aa5829963eff107cab82e13c555bb9032358b58725548caec7f471a900beb533"

    /** 一个可下载的新版本 */
    data class ReleaseInfo(
        val tagName: String,        // v0.6.1
        val version: String,        // 0.6.1
        val notes: String,          // Release 说明（Markdown）
        val assetName: String,
        val assetUrl: String,       // browser_download_url
        val assetSha256: String?,   // API 的 digest 字段，形如 sha256:xxxx（可能为空）
        val assetSize: Long,
        /** 附件上传时间（epoch ms）：用于识别「同一版本被替换成新构建」 */
        val assetUploadedAt: Long = 0L,
        val minSdk: Int? = legacyMinSdk(version),
        val versionCode: Long? = null,
        /** Transient diagnosis only; downloaded records retain their existing schema. */
        val compatibilityIssue: String? = null
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Only immutable, already published legacy versions have an implicit platform requirement. */
    internal fun legacyMinSdk(version: String): Int? =
        if (Regex("0\\.\\d+\\.\\d+(?:\\.\\d+)?").matches(version) &&
            version.split('.').all { it.toIntOrNull() != null } && !isNewer(version, "0.6.7.8")) 28 else null

    fun compatibilityError(release: ReleaseInfo, deviceSdk: Int = Build.VERSION.SDK_INT): String? = when {
        release.minSdk == null || release.minSdk <= 0 -> release.compatibilityIssue ?: "update.compatibilityUnknown"
        release.minSdk > deviceSdk -> "update.incompatible"
        else -> null
    }

    internal fun isReleaseAssetUrl(url: String, tag: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.port == -1 &&
            uri.query == null && uri.fragment == null &&
            uri.path.startsWith("/$REPO/releases/download/$tag/") &&
            uri.path.substringAfter("/$REPO/releases/download/$tag/").let { it.isNotEmpty() && '/' !in it }
    }.getOrDefault(false)

    /** Bind compatibility data to the selected signed APK, including its digest when GitHub supplies one. */
    internal fun withManifest(release: ReleaseInfo, text: String): ReleaseInfo? = runCatching {
        require(text.toByteArray(Charsets.UTF_8).size <= 65536)
        val root = json.parseToJsonElement(text.removePrefix("\uFEFF")).jsonObject
        fun value(key: String) = root[key]!!.jsonPrimitive.content
        require(value("schema") == "1" && value("version") == release.version)
        val min = value("minSdk").toInt().also { require(it > 0) }
        val code = value("versionCode").toLong().also { require(it > 0) }
        val digest = value("apkSha256").lowercase().also { require(Regex("[0-9a-f]{64}").matches(it)) }
        require(value("apkName") == release.assetName && value("apkSize").toLong() == release.assetSize && release.assetSize > 0)
        require(release.assetSha256 == null || release.assetSha256.equals(digest, true))
        release.copy(minSdk = min, versionCode = code, assetSha256 = digest, compatibilityIssue = null)
    }.getOrNull()

    private const val MANIFEST_MARKER = "<!-- studyassistant-update-manifest"
    private val inlineManifest = Regex("<!-- studyassistant-update-manifest\\r?\\n(.*?)\\r?\\n-->", RegexOption.DOT_MATCHES_ALL)

    /** Hide the wire format even from the app's plain-text release notes renderer. */
    internal fun visibleReleaseNotes(notes: String): String =
        inlineManifest.replace(notes, "").trim()

    internal data class ManifestEndpoint(val url: String, val api: Boolean = false)
    internal sealed interface ManifestFetch {
        data class Body(val text: String) : ManifestFetch
        data object Unavailable : ManifestFetch
        data object Invalid : ManifestFetch
    }

    /** Pure resolver: all supported sources bind the exact same APK identity. */
    internal fun resolveManifest(release: ReleaseInfo, assets: List<JsonObject>,
                                 read: (ManifestEndpoint) -> ManifestFetch): ReleaseInfo {
        val inline = inlineManifest.findAll(release.notes).toList()
        val markerCount = Regex(Regex.escape(MANIFEST_MARKER)).findAll(release.notes).count()
        var invalid = markerCount > 0
        if (inline.size == 1 && markerCount == 1) {
            withManifest(release, inline.single().groupValues[1])?.let { return it }
        }
        val entries = assets.filter { (it["name"] as? JsonPrimitive)?.contentOrNull == "update-manifest.json" }
        fun failed(issue: String) = release.copy(minSdk = null, compatibilityIssue = issue)
        if (entries.isEmpty()) return if (invalid) failed("update.compatibilityInvalid") else release
        if (entries.size != 1) return failed("update.compatibilityInvalid")
        val asset = entries.single()
        fun value(key: String) = (asset[key] as? JsonPrimitive)?.contentOrNull
        val url = value("browser_download_url").orEmpty()
        val size = value("size")?.toLongOrNull()
        if (size == null || size !in 1..65536 || !isReleaseAssetUrl(url, release.tagName))
            return failed("update.compatibilityInvalid")
        val endpoints = mutableListOf(ManifestEndpoint(url))
        value("id")?.toLongOrNull()?.takeIf { it > 0 }?.let { id ->
            endpoints += ManifestEndpoint("https://api.github.com/repos/$REPO/releases/assets/$id", api = true)
        }
        for (endpoint in endpoints) {
            when (val result = read(endpoint)) {
                is ManifestFetch.Body -> {
                    withManifest(release, result.text)?.let { return it }
                    invalid = true
                }
                ManifestFetch.Invalid -> invalid = true
                ManifestFetch.Unavailable -> Unit
            }
        }
        return failed(if (invalid) "update.compatibilityInvalid" else "update.compatibilityUnavailable")
    }

    private fun fetchManifest(release: ReleaseInfo, assets: List<JsonObject>): ReleaseInfo =
        resolveManifest(release, assets, ::readManifest)

    private fun readManifest(endpoint: ManifestEndpoint): ManifestFetch {
        return try {
            val request = Request.Builder().url(endpoint.url).header("User-Agent", "StudyAssistant-Update")
            if (endpoint.api) request.header("Accept", "application/octet-stream")
                .header("X-GitHub-Api-Version", "2022-11-28")
            manifestClient.newCall(request.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Update manifest HTTP ${response.code}; api=${endpoint.api}")
                    return ManifestFetch.Unavailable
                }
                val body = response.body ?: return ManifestFetch.Unavailable
                if (body.contentLength() > 65536) return ManifestFetch.Invalid
                val source = body.source()
                source.request(65537)
                if (source.buffer.size > 65536) return ManifestFetch.Invalid
                ManifestFetch.Body(source.readUtf8())
            }
        } catch (error: java.io.IOException) {
            // Do not log URL queries, payloads or other app/user data.
            Log.w(TAG, "Update manifest ${error.javaClass.simpleName}; api=${endpoint.api}")
            ManifestFetch.Unavailable
        }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val manifestClient: OkHttpClient by lazy {
        client.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS).build()
    }

    /** 当前已安装版本（PackageManager 读，避免与构建号不一致） */
    fun installedVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    } catch (e: Exception) {
        "0.0.0"
    }

    /** 去掉前缀 v，并截掉 `_beta` 这类后缀，便于比较 */
    fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V").substringBefore('_').trim()

    /**
     * 判断 a 是否比 b 新：按 `.` 分段做**数值**比较（1.10 > 1.9 ✓）。
     * 段数不同时，缺失段按 0 处理。
     */
    fun isNewer(a: String, b: String): Boolean {
        val pa = normalizeVersion(a).split('.').map { it.toIntOrNull() ?: 0 }
        val pb = normalizeVersion(b).split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** ISO8601（如 2026-09-25T03:11:00Z）→ epoch ms；解析失败返回 0 */
    private fun parseIsoMillis(iso: String?): Long = try {
        if (iso.isNullOrBlank()) 0L else java.time.Instant.parse(iso).toEpochMilli()
    } catch (e: Exception) { 0L }

    /** 本机安装/更新时间（epoch ms）：判断"发布物是否比本机新"用 */
    fun installedLastUpdateTime(context: Context): Long = try {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    } catch (e: Exception) { 0L }

    private val installedDigestCache = InstalledApkDigestCache()

    /** 本机**已安装 APK 自身**的 SHA-256（读 packageCodePath）：用于判断发布物内容是否变了 */
    fun installedApkSha256(context: Context): String? = try {
        installedDigestCache.read(File(context.packageCodePath), installedLastUpdateTime(context), ::sha256)
    } catch (e: Exception) {
        Log.w(TAG, "hash installed apk failed: ${e.message}")
        null
    }

    /** New APKs embed the same UTC timestamp used in their attachment name. Legacy APKs use digest fallback. */
    fun installedBuildTime(context: Context): Long = try {
        context.assets.open("build-time-utc.txt").bufferedReader(Charsets.UTF_8).use { BuildIdentity.parseStamp(it.readText().trim()) }
    } catch (_: Exception) { 0L }

    fun sameVersionNewBuild(context: Context, rel: ReleaseInfo): Boolean = BuildIdentity.isReplacement(
        installedBuildTime(context), BuildIdentity.assetTime(rel.assetName, rel.version),
        installedApkSha256(context), rel.assetSha256
    )

    /** 是否有更新：**版本更高**，或**同一版本但发布物是更晚的新构建** */
    fun hasUpdate(context: Context, rel: ReleaseInfo): Boolean {
        val installed = installedVersion(context)
        if (isNewer(rel.version, installed)) return true
        if (normalizeVersion(rel.version) == normalizeVersion(installed)) {
            return sameVersionNewBuild(context, rel)
        }
        return false
    }

    // ── 「跳过此版本」与「上次检查时间」的本地持久化 ──────────────────────────
    // 与主题/界面语言共用 settings 这个 SharedPreferences（自用场景，不必另开文件）
    private const val PREFS = "settings"
    private const val KEY_SKIPPED = "update_skipped_version"
    private const val KEY_LAST_CHECK = "update_last_check_at"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 用户「跳过此版本」记下的版本号（已规范化）；null = 没有跳过任何版本 */
    fun skippedVersion(context: Context): String? =
        prefs(context).getString(KEY_SKIPPED, null)?.takeIf { it.isNotBlank() }

    /** 这个发布物是否正是用户跳过的那一版 */
    fun isSkipped(context: Context, rel: ReleaseInfo): Boolean =
        normalizeVersion(rel.version) == skippedVersion(context)

    fun skipVersion(context: Context, version: String) {
        prefs(context).edit().putString(KEY_SKIPPED, normalizeVersion(version)).apply()
    }

    fun clearSkippedVersion(context: Context) {
        prefs(context).edit().remove(KEY_SKIPPED).apply()
    }

    /** 上次**成功**检查的时间（epoch ms；0 = 从未成功检查过） */
    fun lastCheckAt(context: Context): Long = prefs(context).getLong(KEY_LAST_CHECK, 0L)

    fun markChecked(context: Context, at: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_LAST_CHECK, at).apply()
    }

    /**
     * 是否该提示用户更新 = [hasUpdate] **且**不是用户已跳过的版本。
     * 跳过只影响"提示"，不影响手动检查时看到的最新版本信息。
     */
    fun shouldNotify(context: Context, rel: ReleaseInfo): Boolean =
        compatibilityError(rel) == null && hasUpdate(context, rel) && !isSkipped(context, rel)

    /**
     * Release 说明（Markdown）→ 适合直接显示的纯文本。
     * GitHub 的说明是 `### 标题` / `- 列表` / `**加粗**` 这种 Markdown，
     * 直接塞进 Text 会看到一堆 `#`、`*`；这里做**最小化**清洗（不引 Markdown 渲染依赖）：
     *   · 行首 `#` 去掉；`**粗体**`/`__粗体__` 去掉标记；行首 `-`/`*` 换成 `·`；
     *   · 行内 ` 反引号 ` 去掉；连续空行压成一个；首尾空白去掉。
     */
    fun prettyReleaseNotes(markdown: String): String {
        if (markdown.isBlank()) return ""
        val out = StringBuilder()
        var blank = false
        for (raw in markdown.lines()) {
            var line = raw.trimEnd()
            line = line.replace(Regex("^\\s{0,3}#{1,6}\\s*"), "")   // ### 标题
            line = line.replace(Regex("^\\s*[-*+]\\s+"), "· ")      // - 列表
            line = line.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")   // **粗体**
            line = line.replace(Regex("__(.+?)__"), "$1")
            line = line.replace("`", "")
            if (line.isBlank()) {
                if (out.isEmpty() || blank) continue
                blank = true
            } else {
                blank = false
            }
            out.append(line).append('\n')
        }
        return out.toString().trim()
    }

    /**
     * 查最新 Release。网络失败/无 Release/解析失败 → 返回 null（调用方按"静默失败"处理）。
     */    suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(API_LATEST)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "releases/latest HTTP ${resp.code}")
                    return@withContext null
                }
                val text = resp.body?.string().orEmpty()
                if (text.isBlank()) return@withContext null
                val root = json.parseToJsonElement(text).jsonObject
                val tag = root["tag_name"]?.jsonPrimitive?.content.orEmpty()
                val notes = root["body"]?.jsonPrimitive?.content.orEmpty()
                // 附件：优先 StudyAssistant-*.apk，其次任意 .apk
                val assets = root["assets"]?.jsonArray ?: return@withContext null
                val objs = assets.mapNotNull { it as? JsonObject }
                val apks = objs.filter { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
                val asset = apks.filter { it["name"]?.jsonPrimitive?.content?.startsWith("StudyAssistant-") == true }
                    .maxByOrNull { BuildIdentity.assetTime(it["name"]!!.jsonPrimitive.content, normalizeVersion(tag)) }
                    ?: apks.firstOrNull() ?: return@withContext null
                val name = asset["name"]?.jsonPrimitive?.content.orEmpty()
                val url = asset["browser_download_url"]?.jsonPrimitive?.content.orEmpty()
                val size = asset["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                // digest 形如 "sha256:xxxx"（GitHub 较新才返回；为空时只做签名校验）
                val digest = asset["digest"]?.jsonPrimitive?.content?.removePrefix("sha256:")?.lowercase()
                // 附件上传时间（updated_at 优先，退到 created_at）：识别「同版本被替换成新构建」
                val uploadedAt = parseIsoMillis(asset["updated_at"]?.jsonPrimitive?.content)
                    .takeIf { it > 0L } ?: parseIsoMillis(asset["created_at"]?.jsonPrimitive?.content)
                if (!isReleaseAssetUrl(url, tag)) return@withContext null
                fetchManifest(ReleaseInfo(
                    tagName = tag,
                    version = normalizeVersion(tag),
                    notes = notes,
                    assetName = name,
                    assetUrl = url,
                    assetSha256 = digest?.takeIf { it.length == 64 },
                    assetSize = size,
                    assetUploadedAt = uploadedAt
                ), objs).let { it.copy(notes = visibleReleaseNotes(it.notes)) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchLatest failed: ${e.message}")
            null
        }
    }

    /** 下载到 [dest]，[onProgress] 回调 0..100（-1 表示总长度未知） */
    suspend fun download(url: String, dest: File, onProgress: (Int) -> Unit): Boolean = kotlinx.coroutines.supervisorScope {
        val call = client.newCall(Request.Builder().url(url).build())
        val work = async(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            call.execute().use { response ->
                check(response.isSuccessful)
                val body = checkNotNull(response.body)
                val total = body.contentLength()
                body.byteStream().use { input -> dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L; var last = -2
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read); done += read
                        val percent = if(total > 0) ((done * 100) / total).toInt() else -1
                        if(percent != last) { last = percent; onProgress(percent) }
                    }
                    output.flush()
                } }
            }
            true
        }
        try { work.await() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { false }
        finally { call.cancel() }
    }

    /** 文件的 SHA-256（小写十六进制） */
    fun sha256(file: File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = ins.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }

    /** 读取 APK 的签名证书 SHA-256（小写十六进制）；读不到返回 null */
    @Suppress("DEPRECATION")
    fun apkSignerSha256(context: Context, apk: File): String? = try {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        val signers = info?.signingInfo?.apkContentsSigners
            ?: info?.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        val first = signers?.firstOrNull() ?: return null
        MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
            .joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        Log.w(TAG, "read signer failed: ${e.message}")
        null
    }

    /** 校验结果 */
    data class VerifyResult(val ok: Boolean, val shaOk: Boolean, val signerOk: Boolean, val actualSigner: String?)

    /**
     * 双重校验：SHA-256（有 digest 才查）+ 签名证书。
     * 注意：**签名证书必须匹配**（这是防伪造的关键）；SHA 只在 API 给了 digest 时作为附加校验。
     */
    internal fun apkSdkCompatible(actualMinSdk: Int?, deviceSdk: Int, expectedMinSdk: Int? = null): Boolean =
        actualMinSdk != null && actualMinSdk > 0 && actualMinSdk <= deviceSdk &&
            (expectedMinSdk == null || actualMinSdk == expectedMinSdk)

    fun verify(context: Context, apk: File, expectedSha256: String?, expectedVersion: String? = null,
        expectedMinSdk: Int? = null, expectedVersionCode: Long? = null): VerifyResult {
        val actualSha = sha256(apk)
        val shaOk = expectedSha256 == null || (actualSha != null && actualSha.equals(expectedSha256, true))
        val actualSigner = apkSignerSha256(context, apk)
        val signerOk = actualSigner != null && actualSigner.equals(EXPECTED_CERT_SHA256, true)
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val identityOk = info != null && info.packageName == context.packageName &&
            info.longVersionCode >= installed.longVersionCode &&
            (expectedVersionCode == null || info.longVersionCode == expectedVersionCode) &&
            (expectedVersion == null || normalizeVersion(info.versionName.orEmpty()) == expectedVersion)
        val sdkOk = apkSdkCompatible(info?.applicationInfo?.minSdkVersion, Build.VERSION.SDK_INT, expectedMinSdk)
        return VerifyResult(ok = shaOk && signerOk && identityOk && sdkOk, shaOk = shaOk, signerOk = signerOk, actualSigner = actualSigner)
    }

    /** 下载目录（cacheDir/updates，配合 FileProvider 的 file_paths.xml） */
    fun downloadDir(context: Context): File = File(context.cacheDir, "updates").apply { mkdirs() }

    /** 清理旧的下载包，避免 cacheDir 堆积 */
    fun clearDownloads(context: Context) {
        try { downloadDir(context).listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
    }
}
