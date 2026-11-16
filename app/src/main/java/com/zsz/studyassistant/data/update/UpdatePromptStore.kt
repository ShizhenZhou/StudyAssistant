package com.zsz.studyassistant.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Local update policy, separate from business data/backups and notification permission. */
object UpdatePromptStore {
    private val mutex = Mutex()
    private val mutableRelease = MutableStateFlow<UpdateChecker.ReleaseInfo?>(null)
    val release = mutableRelease.asStateFlow()
    private val manuallyChecked = MutableStateFlow<String?>(null)
    private val manualMonitor = Any()
    private var foregroundCycle = 0L
    val manualCheckIdentity = manuallyChecked.asStateFlow()
    fun manualCheckCycle(): Long = synchronized(manualMonitor) { foregroundCycle }
    fun invalidateManualChecks() = synchronized(manualMonitor) {
        foregroundCycle++
        manuallyChecked.value = null
    }
    fun wasManuallyChecked(value: UpdateChecker.ReleaseInfo) = manuallyChecked.value == identity(value)
    fun consumeManualCheck(value: UpdateChecker.ReleaseInfo): Boolean {
        val pending = manuallyChecked.value
        return pending == identity(value) && manuallyChecked.compareAndSet(pending, null)
    }
    private const val KEY = "required_release"
    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences("update_prompt", Context.MODE_PRIVATE)

    fun identity(release: UpdateChecker.ReleaseInfo) =
        "${release.tagName}:${release.assetSha256 ?: release.assetName}:${release.mandatory}"

    private fun eligible(context: Context, release: UpdateChecker.ReleaseInfo) =
        release.channel == UpdateChecker.Channel.STABLE && UpdateChecker.compatibilityError(release) == null &&
            UpdateChecker.hasUpdate(context, release)

    private fun read(context: Context): UpdateChecker.ReleaseInfo? = runCatching {
        val obj = JSONObject(prefs(context).getString(KEY, null) ?: return null)
        val candidate = UpdateChecker.ReleaseInfo(obj.getString("tag"), obj.getString("version"),
            obj.getString("notes"), obj.getString("apkName"), obj.getString("url"),
            obj.getString("apkSha256"), obj.getLong("apkSize"))
        require(UpdateChecker.isReleaseAssetUrl(candidate.assetUrl, candidate.tagName))
        UpdateChecker.withManifest(candidate, obj.toString())?.takeIf { it.mandatory && eligible(context, it) }
    }.getOrNull()

    private fun persist(context: Context, value: UpdateChecker.ReleaseInfo?) {
        val editor = prefs(context).edit()
        if (value?.mandatory == true) {
            val obj = JSONObject().put("schema", if (value.distribution == "compat") 2 else 1)
                .put("distribution", value.distribution).put("maxSdk", value.maxSdk)
                .put("tag", value.tagName).put("version", value.version)
                .put("notes", value.notes).put("url", value.assetUrl).put("apkName", value.assetName)
                .put("apkSize", value.assetSize).put("apkSha256", value.assetSha256)
                .put("minSdk", value.minSdk).put("versionCode", value.versionCode)
                .put("channel", "stable").put("mandatory", true)
            editor.putString(KEY, obj.toString())
        } else editor.remove(KEY)
        // Write before publishing UI state: process death must not bypass a confirmed requirement.
        if (!editor.commit()) android.util.Log.w("UpdatePromptStore", "Update policy could not be persisted")
    }

    suspend fun restore(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val saved = read(context)
            mutableRelease.value = saved ?: mutableRelease.value?.takeIf { eligible(context, it) }
            if (saved == null) prefs(context).edit().remove(KEY).commit()
        }
    }

    suspend fun accept(context: Context, releases: UpdateChecker.AvailableReleases,
        manualCheckCycle: Long? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val previous = (mutableRelease.value ?: read(context))?.takeIf { eligible(context, it) }
            val candidate = releases.stable?.takeIf { eligible(context, it) }
            val next = if (!releases.metadataTrusted) {
                // Unsigned mirror metadata must neither create a lock nor replace its confirmed
                // target with an invented newer build that the user can never install.
                if (previous?.mandatory == true) previous else candidate?.copy(mandatory = false)
            } else if (previous?.mandatory == true && !releases.historyComplete) {
                // Partial/failed history cannot silently unlock the app. A newer valid
                // target may still replace the cached target without losing the requirement.
                if (candidate != null && UpdateChecker.compareReleases(candidate, previous) >= 0)
                    candidate.copy(mandatory = true) else previous
            } else candidate
            persist(context, next)
            // Publish this before the release flow: the update page already presents these results.
            synchronized(manualMonitor) {
                if (manualCheckCycle != null && manualCheckCycle == foregroundCycle)
                    manuallyChecked.value = next?.let(::identity)
            }
            mutableRelease.value = next
        }
    }
}
