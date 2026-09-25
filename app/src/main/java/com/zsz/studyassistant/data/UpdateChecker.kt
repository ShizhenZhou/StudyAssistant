package com.zsz.studyassistant.data

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
        val assetSize: Long
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
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

    /**
     * 查最新 Release。网络失败/无 Release/解析失败 → 返回 null（调用方按"静默失败"处理）。
     */
    suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
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
                val asset = objs.firstOrNull { it["name"]?.jsonPrimitive?.content?.startsWith("StudyAssistant-") == true }
                    ?: objs.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
                    ?: return@withContext null
                val name = asset["name"]?.jsonPrimitive?.content.orEmpty()
                val url = asset["browser_download_url"]?.jsonPrimitive?.content.orEmpty()
                val size = asset["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                // digest 形如 "sha256:xxxx"（GitHub 较新才返回；为空时只做签名校验）
                val digest = asset["digest"]?.jsonPrimitive?.content?.removePrefix("sha256:")?.lowercase()
                if (url.isBlank()) return@withContext null
                ReleaseInfo(
                    tagName = tag,
                    version = normalizeVersion(tag),
                    notes = notes,
                    assetName = name,
                    assetUrl = url,
                    assetSha256 = digest?.takeIf { it.length == 64 },
                    assetSize = size
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchLatest failed: ${e.message}")
            null
        }
    }

    /** 下载到 [dest]，[onProgress] 回调 0..100（-1 表示总长度未知） */
    suspend fun download(url: String, dest: File, onProgress: (Int) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            try {
                dest.parentFile?.mkdirs()
                if (dest.exists()) dest.delete()
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "download HTTP ${resp.code}")
                        return@withContext false
                    }
                    val body = resp.body ?: return@withContext false
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        dest.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var read: Int
                            var done = 0L
                            var lastPct = -2
                            while (true) {
                                read = input.read(buf)
                                if (read <= 0) break
                                output.write(buf, 0, read)
                                done += read
                                val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                            }
                            output.flush()
                        }
                    }
                }
                onProgress(100)
                true
            } catch (e: Exception) {
                Log.w(TAG, "download failed: ${e.message}")
                dest.delete()
                false
            }
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
    fun verify(context: Context, apk: File, expectedSha256: String?): VerifyResult {
        val actualSha = sha256(apk)
        val shaOk = expectedSha256 == null || (actualSha != null && actualSha.equals(expectedSha256, true))
        val actualSigner = apkSignerSha256(context, apk)
        val signerOk = actualSigner != null && actualSigner.equals(EXPECTED_CERT_SHA256, true)
        return VerifyResult(ok = shaOk && signerOk, shaOk = shaOk, signerOk = signerOk, actualSigner = actualSigner)
    }

    /** 下载目录（cacheDir/updates，配合 FileProvider 的 file_paths.xml） */
    fun downloadDir(context: Context): File = File(context.cacheDir, "updates").apply { mkdirs() }

    /** 清理旧的下载包，避免 cacheDir 堆积 */
    fun clearDownloads(context: Context) {
        try { downloadDir(context).listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
    }
}
