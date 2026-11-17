package com.zsz.studyassistant.data

import android.content.Context
import java.net.URI

/** Transport addresses never replace canonical release/download identities. No app credentials are used. */
object UpdateTransport {
    const val DEFAULT_BASE_URL = "https://gh-proxy.org/"
    data class Config(val enabled: Boolean = true, val baseUrl: String = DEFAULT_BASE_URL)

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences("update_transport", Context.MODE_PRIVATE)

    fun load(context: Context): Config {
        val prefs = prefs(context)
        val base = normalizedBaseUrl(prefs.getString("base_url", DEFAULT_BASE_URL).orEmpty())
        return Config(prefs.getBoolean("enabled", true) && base != null, base ?: DEFAULT_BASE_URL)
    }

    fun save(context: Context, config: Config): Boolean {
        val base = normalizedBaseUrl(config.baseUrl) ?: return false
        prefs(context).edit().putBoolean("enabled", config.enabled).putString("base_url", base).apply()
        return true
    }

    /** A public HTTPS hostname and optional path prefix; reject credentials and literal/local addresses. */
    fun normalizedBaseUrl(input: String): String? = runCatching {
        val uri = URI(input.trim())
        val host = uri.host?.lowercase() ?: return null
        require(uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443))
        require(uri.rawQuery == null && uri.rawFragment == null)
        require(host.length <= 253 && host.split('.').size >= 2)
        require(host.split('.').all { Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?").matches(it) })
        // Reject every IP literal (including numeric alternatives), local-only suffix and mDNS name.
        require(Regex("[a-z]{2,63}").matches(host.substringAfterLast('.')))
        require(listOf("localhost", "local", "internal", "lan", "home", "test", "invalid", "example", "arpa")
            .none { host == it || host.endsWith(".$it") })
        require(uri.rawPath.orEmpty().let { path ->
            path.length <= 300 && Regex("[/A-Za-z0-9_.~-]*").matches(path) &&
                path.split('/').none { it == "." || it == ".." }
        })
        "https://$host${uri.rawPath.orEmpty().trimEnd('/')}/"
    }.getOrNull()

    /** Only this public repository's update endpoints can be sent through a configured mirror. */
    fun isCanonicalUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        require(uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && uri.fragment == null)
        require(uri.rawPath == uri.path)
        val repo = UpdateChecker.REPO
        when (uri.host) {
            "api.github.com" -> when (uri.path) {
                "/repos/$repo/releases" -> uri.rawQuery == null ||
                    Regex("per_page=100&page=([1-9][0-9]?|100)").matches(uri.rawQuery)
                "/repos/$repo/releases/latest" -> uri.query == null
                else -> uri.query == null &&
                    Regex("/repos/${Regex.escape(repo)}/releases/assets/[1-9][0-9]*").matches(uri.path)
            }
            "raw.githubusercontent.com" -> value == AppNoticeStore.URL
            "github.com" -> uri.query == null &&
                Regex("/${Regex.escape(repo)}/releases/download/[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*")
                    .matches(uri.path)
            else -> false
        }
    }.getOrDefault(false)

    fun urls(canonicalUrl: String, config: Config): List<String> {
        require(isCanonicalUrl(canonicalUrl)) { "Unsupported update endpoint" }
        val base = normalizedBaseUrl(config.baseUrl)
        return if (config.enabled && base != null) listOf(canonicalUrl, base + canonicalUrl)
        else listOf(canonicalUrl)
    }
}
