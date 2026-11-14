package com.zsz.studyassistant.data

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Disposable, private metadata only: never include it in conversation/backup/diagnostic exports. */
internal class DocumentReferenceCache(path: File) {
    private val file = PrivateJsonFile(path, 128 * 1024)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class State(val version: Int = 1, val blockedUntil: Long = 0, val entries: List<Entry> = emptyList())

    @Serializable
    private data class Entry(val scope: String, val sha256: String, val purpose: String,
        val id: String? = null, val expiresAt: Long = 0, val retryAfter: Long = 0) {
        fun matches(scope: String, sha256: String, purpose: String) =
            this.scope == scope && this.sha256 == sha256 && this.purpose == purpose
    }

    private fun read(now: Long): State {
        val state = try {
            file.read()?.let { json.decodeFromString<State>(it) }?.also { value ->
                require(value.version == 1 && value.entries.size <= 32 && value.blockedUntil >= 0)
                require(value.entries.map { Triple(it.scope, it.sha256, it.purpose) }.distinct().size == value.entries.size)
                value.entries.forEach {
                    require(it.scope.matches(Regex("[0-9a-f]{64}")) && it.sha256.matches(Regex("[0-9a-f]{64}")))
                    require(it.purpose in DocumentRecognition.entries.map { purpose -> purpose.wireValue })
                    require(if (it.id == null) it.expiresAt == 0L && it.retryAfter > 0
                        else validAiFileId(it.id) && it.expiresAt > 0 && it.retryAfter == 0L)
                }
            } ?: State()
        } catch (_: Exception) {
            // A damaged cache may have held an in-flight paid upload. Do not immediately replay it.
            write(State(blockedUntil = now + 300))
            throw DocumentProblem("document.uploadUncertain")
        }
        val live = state.entries.filter { if (it.id == null) it.retryAfter > now else it.expiresAt > now + 300 }
        return state.copy(entries = live).also { if (live != state.entries) write(it) }
    }

    private fun write(state: State) {
        try { file.write(json.encodeToString(state)) }
        catch (_: Exception) { throw DocumentProblem("document.cacheFailed") }
    }

    fun find(scope: String, sha256: String, purpose: String, now: Long): RemoteAiFile? {
        val state = read(now)
        if (state.blockedUntil > now) throw DocumentProblem("document.uploadUncertain")
        val entry = state.entries.firstOrNull { it.matches(scope, sha256, purpose) } ?: return null
        if (entry.retryAfter > now) throw DocumentProblem("document.uploadUncertain")
        return RemoteAiFile(DeepSeekFile(requireNotNull(entry.id), entry.expiresAt, purpose = purpose), scope)
    }

    /** Must succeed before networking; a killed process retains the same five-minute cooldown. */
    fun begin(scope: String, sha256: String, purpose: String, now: Long) {
        val state = read(now)
        if (state.blockedUntil > now || state.entries.any { it.matches(scope, sha256, purpose) && it.retryAfter > now })
            throw DocumentProblem("document.uploadUncertain")
        val entries = state.entries.filterNot { it.matches(scope, sha256, purpose) }.toMutableList()
        if (entries.size >= 32) {
            val evict = entries.indexOfFirst { it.id != null }
            if (evict < 0) throw DocumentProblem("document.uploadUncertain")
            entries.removeAt(evict) // Never evict a live uncertain-upload marker.
        }
        entries += Entry(scope, sha256, purpose, retryAfter = now + 300)
        write(state.copy(entries = entries))
    }

    fun complete(scope: String, sha256: String, purpose: String, reference: RemoteAiFile, now: Long) {
        val expiry = reference.metadata.expiresAt ?: throw DocumentProblem("document.requestFailed")
        require(reference.accountScope == scope && expiry > now + 300 && validAiFileId(reference.metadata.id))
        require(reference.metadata.purpose == purpose)
        val state = read(now)
        val entries = state.entries.filterNot { it.matches(scope, sha256, purpose) }
        if (entries.size >= 32) throw DocumentProblem("document.cacheFailed")
        write(state.copy(entries = entries + Entry(scope, sha256, purpose, reference.metadata.id, expiry)))
    }

    fun invalidate(scope: String, hashes: Set<String>, now: Long) {
        val state = read(now)
        write(state.copy(entries = state.entries.filterNot { it.scope == scope && it.sha256 in hashes }))
    }

    /** Only call for explicit local data/cache cleanup; does not delete local or remote originals. */
    fun clear() {
        try { file.clear() } catch (_: Exception) { throw DocumentProblem("document.cacheFailed") }
    }
}
