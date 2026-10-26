package com.zsz.studyassistant.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Only documents returned with a previously absent ID become owned by this writer. */
object BackupDocuments {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private const val LEDGER = "backup_job_sets"
    private const val PARTS = "backup_job_parts_"
    private data class Child(val id: String, val name: String, val size: Long)
    private fun children(c: Context, tree: Uri): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val cursor = c.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE), null, null, null)
            ?: throw BackupWriteException("data.autoBackup.folderMissing")
        return cursor.use { buildList { while (it.moveToNext()) add(Child(it.getString(0), it.getString(1) ?: "", if (it.isNull(2)) -1 else it.getLong(2))) } }
    }
    private fun doc(tree: Uri, id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    suspend fun writeSingle(context: Context, source: File, uri: Uri, check: () -> Unit = {}) = withContext(Dispatchers.IO) {
        check()
        // An unknown size is not proof of emptiness. Read before opening any truncating stream.
        val existing = context.contentResolver.openInputStream(uri) ?: throw BackupWriteException("data.autoBackup.writeFailed")
        existing.use { if (it.read() != -1) throw BackupWriteException("data.autoBackup.createFailed") }
        check()
        BackupStreamCopy.writeAndVerify({ source.inputStream() }, { context.contentResolver.openOutputStream(uri, "wt") }, { context.contentResolver.openInputStream(uri) }, check)
    }

    suspend fun writeSet(context: Context, set: BackupArchive.ArchiveSet, tree: Uri, automatic: Boolean, check: () -> Unit = {}): AutoBackup.Result = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val checkpointKey = PARTS + set.setId
        val checkpoint = runCatching { JSONObject(prefs(context).getString(checkpointKey, "{}")!!) }.getOrDefault(JSONObject())
        val sameTree = checkpoint.optString("tree") == tree.toString()
        val parts = if (sameTree) checkpoint.optJSONArray("parts") ?: JSONArray() else JSONArray()
        val owned = mutableListOf<Uri>()
        var completed = false
        try {
            check()
            val before = children(context, tree).map { it.id }.toMutableSet()
            val parent = doc(tree, DocumentsContract.getTreeDocumentId(tree))
            val finalParts = JSONArray()
            for ((index, file) in set.files.withIndex()) {
                check()
                val previous = parts.optJSONObject(index)
                var uri: Uri? = null
                if (previous != null && previous.optString("file") == file.name) {
                    val candidate = doc(tree, previous.getString("id"))
                    val valid = runCatching {
                        val input = resolver.openInputStream(candidate) ?: throw IOException()
                        input.use { MessageDigest.isEqual(BackupStreamCopy.digest(file.inputStream(), check), BackupStreamCopy.digest(it, check)) }
                    }.getOrDefault(false)
                    check()
                    if (valid) uri = candidate
                }
                if (uri == null) {
                    val prefix = if (automatic) AutoBackup.PREFIX else "study-assistant-"
                    val name = "$prefix${set.setId}-part${(index + 1).toString().padStart(4, '0')}.sab"
                    val made = DocumentsContract.createDocument(resolver, parent, "application/zip", name)
                        ?: throw BackupWriteException("data.autoBackup.createFailed")
                    val id = DocumentsContract.getDocumentId(made)
                    if (id in before) throw BackupWriteException("data.autoBackup.createFailed")
                    before.add(id)
                    owned.add(made)
                    uri = made
                    BackupStreamCopy.writeAndVerify({ file.inputStream() }, { resolver.openOutputStream(made, "wt") }, { resolver.openInputStream(made) }, check)
                }
                val entry = JSONObject().put("id", DocumentsContract.getDocumentId(uri)).put("file", file.name)
                    .put("length", file.length()).put("sha", hex(BackupStreamCopy.digest(file.inputStream(), check)))
                finalParts.put(entry)
                // Commit after every closed and verified volume. Unfinished volume is never reused.
                check()
                checkpoint.put("tree", tree.toString()).put("parts", finalParts)
                if (!prefs(context).edit().putString(checkpointKey, checkpoint.toString()).commit()) throw IOException("checkpoint")
            }
            check()
            val sets = ledger(context)
            val recorded = (0 until sets.length()).any { sets.getJSONObject(it).optString("id") == set.setId }
            if (automatic && !recorded) sets.put(JSONObject().put("id", set.setId).put("at", set.snapshotAt).put("tree", tree.toString())
                .put("automatic", automatic).put("complete", true).put("parts", finalParts))
            if (!prefs(context).edit().putString(LEDGER, sets.toString()).commit()) throw IOException("ledger")
            completed = true
            // Completion is durable before pruning. Manual sets and unknown/legacy files are never deletion candidates.
            val eligible = (0 until sets.length()).map { sets.getJSONObject(it) }.filter { it.optString("tree") == tree.toString() }
            val prune = if (automatic) BackupTaskPolicy.pruneSetIds(eligible.map { BackupSetStamp(it.getString("id"), it.optLong("at"), it.optBoolean("complete"), it.optBoolean("automatic")) }, set.setId, AutoBackup.KEEP) else emptyList()
            var pruned = 0
            val removed = mutableSetOf<String>()
            for (old in eligible.filter { it.getString("id") in prune }) {
                check()
                val oldParts = old.getJSONArray("parts")
                // A stored ID alone is not ownership: providers may recycle IDs and users may replace contents.
                if (!verifiedOwnedSet(context, tree, oldParts, check)) continue
                var allRemoved = true
                for (i in 0 until oldParts.length()) {
                    check()
                    val oldUri = doc(tree, oldParts.getJSONObject(i).getString("id"))
                    val deleted = synchronized(BackupJobs) {
                        check()
                        runCatching { DocumentsContract.deleteDocument(resolver, oldUri) }.getOrDefault(false)
                    }
                    if (!deleted) allRemoved = false
                }
                if (allRemoved) { removed.add(old.getString("id")); pruned++ }
            }
            check()
            val kept = JSONArray()
            for (i in 0 until sets.length()) if (sets.getJSONObject(i).getString("id") !in removed) kept.put(sets.getJSONObject(i))
            prefs(context).edit().putString(LEDGER, kept.toString()).remove(checkpointKey).commit()
            AutoBackup.Result(true, set.files.firstOrNull()?.name, pruned)
        } finally {
            // Preserve completed verified volumes for resumable execution. Remove only newly created,
            // not-yet-checkpointed volumes. Never remove a returned pre-existing document ID.
            if (!completed) {
                val persisted = runCatching { JSONObject(prefs(context).getString(checkpointKey, "{}")!!).optJSONArray("parts") }.getOrNull()
                val saved = (0 until (persisted?.length() ?: 0)).map { persisted!!.getJSONObject(it).getString("id") }.toSet()
                for (uri in owned) if (DocumentsContract.getDocumentId(uri) !in saved) runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            }
        }
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun verifiedOwnedSet(context: Context, tree: Uri, parts: JSONArray, check: () -> Unit): Boolean {
        return try {
            check()
            if (parts.length() == 0) return false
            val listed = children(context, tree).groupBy { it.id }
            val ids = HashSet<String>()
            for (i in 0 until parts.length()) {
                check()
                val part = parts.getJSONObject(i)
                val id = part.getString("id")
                val length = part.getLong("length")
                val sha = part.getString("sha")
                if (!ids.add(id) || length < 0 || !sha.matches(Regex("[0-9a-f]{64}"))) return false
                val current = listed[id]?.singleOrNull() ?: return false
                if (current.size != length) return false
                val input = context.contentResolver.openInputStream(doc(tree, id)) ?: return false
                if (hex(BackupStreamCopy.digest(input, check)) != sha) return false
            }
            true
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
          catch (_: Exception) { false }
    }

    internal fun completedPartCount(context: Context, setId: String): Int = runCatching {
        JSONObject(prefs(context).getString(PARTS + setId, "{}")!!).optJSONArray("parts")?.length() ?: 0
    }.getOrDefault(0)

    private fun ledger(context: Context): JSONArray {
        val text = prefs(context).getString(LEDGER, "[]")!!
        // A malformed ledger is never interpreted as ownership of any external file.
        return runCatching { JSONArray(text) }.getOrDefault(JSONArray())
    }
    fun knownSetNames(context: Context, tree: Uri?): List<String> {
        if (tree == null) return emptyList()
        return runCatching {
            val sets = ledger(context)
            (0 until sets.length()).map { sets.getJSONObject(it) }.filter { it.optBoolean("automatic") && it.optBoolean("complete") && it.optString("tree") == tree.toString() }
                .sortedByDescending { it.optLong("at") }.map { "study-assistant-auto-${it.getString("id")}.sab" }
        }.getOrDefault(emptyList())
    }
}
