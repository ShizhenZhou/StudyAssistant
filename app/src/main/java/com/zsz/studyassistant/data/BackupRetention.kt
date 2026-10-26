package com.zsz.studyassistant.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Actual-folder catalog. A name is only a candidate; deletion requires a complete validated set. */
object BackupRetention {
    internal const val LEDGER = "backup_job_sets"
    private const val PROTECTED = "backup_job_protected_documents"
    internal data class Document(val id: String, val name: String, val length: Long)
    internal data class VerifiedDocument(val id: String, val name: String, val length: Long, val sha: String)
    private data class Set(val id: String, val at: Long, val parts: List<VerifiedDocument>, val name: String)
    private data class Probe(val header: BackupArchive.PartInfo, val document: VerifiedDocument)
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    internal fun uri(tree: Uri, id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
    internal fun list(context: Context, tree: Uri): List<Document> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val cursor = context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE), null, null, null)
            ?: throw BackupWriteException("data.autoBackup.folderMissing")
        return cursor.use { buildList { while (it.moveToNext()) {
            val id = it.getString(0) ?: continue
            add(Document(id, it.getString(1) ?: "", if (it.isNull(2)) -1L else it.getLong(2)))
        } } }
    }
    private fun candidate(name: String): Boolean =
        (name.startsWith("SABak_auto-") && Regex("^SABak_auto-[0-9]{14}.*\\.zip(?:$|[ ._-].*)").matches(name)) ||
        (name.startsWith(AutoBackup.PREFIX) && (name.contains(".zip") || name.contains(".json")))
    private fun ledger(context: Context): JSONArray = runCatching { JSONArray(prefs(context).getString(LEDGER, "[]")!!) }.getOrDefault(JSONArray())
    internal fun protectDocuments(context: Context, tree: Uri, ids: kotlin.collections.Set<String>) {
        if (ids.isEmpty()) return
        val saved = runCatching { JSONArray(prefs(context).getString(PROTECTED, "[]")!!) }.getOrDefault(JSONArray())
        val existing = (0 until saved.length()).mapNotNull { saved.optJSONObject(it) }
            .filter { it.optString("tree") == tree.toString() }.map { it.optString("id") }.toSet()
        for (id in ids - existing) saved.put(JSONObject().put("tree", tree.toString()).put("id", id))
        if (!prefs(context).edit().putString(PROTECTED, saved.toString()).commit()) throw IOException("protected documents")
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    suspend fun preTrim(context: Context, tree: Uri, check: () -> Unit = {}): Int = withContext(Dispatchers.IO) {
        val sets = catalog(context, tree, check)
        if (sets.size <= AutoBackup.KEEP) 0 else remove(context, tree, sets.sortedWith(compareByDescending<Set> { it.at }.thenBy { it.id }).drop(2), check)
    }
    suspend fun postTrim(context: Context, tree: Uri, newSetId: String, check: () -> Unit = {}): Int = withContext(Dispatchers.IO) {
        val sets = catalog(context, tree, check)
        if (sets.none { it.id == newSetId }) return@withContext 0
        val targets = sets.filter { it.id != newSetId }.sortedWith(compareByDescending<Set> { it.at }.thenBy { it.id }).drop(AutoBackup.KEEP - 1)
        remove(context, tree, targets, check)
    }
    private data class DisplayCache(val tree: String, val signature: String, val names: List<String>)
    @Volatile private var displayCache: DisplayCache? = null
    /** Blocking IO API for legacy settings callers. Only display counts use this cache. */
    fun listNames(context: Context, tree: Uri, check: () -> Unit = {}): List<String> {
        val signature = list(context, tree).sortedBy { it.id }.joinToString("\n") { "${it.id}:${it.name}:${it.length}" } +
            prefs(context).getString(LEDGER, "[]")
        displayCache?.takeIf { it.tree == tree.toString() && it.signature == signature }?.let { return it.names }
        val names = kotlinx.coroutines.runBlocking(Dispatchers.IO) { catalog(context, tree, check).sortedByDescending { it.at }.map { it.name } }
        displayCache = DisplayCache(tree.toString(), signature, names)
        return names
    }

    private suspend fun catalog(context: Context, tree: Uri, check: () -> Unit): List<Set> {
        check()
        // A directory-wide access/list error is a real failure; individual unreadable files are preserved.
        val listed = list(context, tree)
        val byId = listed.groupBy { it.id }
        val handled = HashSet<String>()
        val protectedIds = runCatching { JSONArray(prefs(context).getString(PROTECTED, "[]")!!) }.getOrDefault(JSONArray())
        for (i in 0 until protectedIds.length()) protectedIds.optJSONObject(i)?.takeIf { it.optString("tree") == tree.toString() }?.optString("id")?.let { handled.add(it) }
        val eligible = linkedMapOf<String, Set>()
        val journal = ledger(context)
        for (i in 0 until journal.length()) {
            check()
            val entry = journal.optJSONObject(i) ?: continue
            if (entry.optString("tree") != tree.toString()) continue
            val saved = entry.optJSONArray("parts") ?: continue
            // Even changed/missing known volumes are protected from a later name-based fallback.
            for (j in 0 until saved.length()) saved.optJSONObject(j)?.optString("id")?.takeIf { it.isNotEmpty() }?.let { handled.add(it) }
            if (!entry.optBoolean("automatic") || !entry.optBoolean("complete")) continue
            try {
                val parts = (0 until saved.length()).map { j ->
                    val p = saved.getJSONObject(j); val id = p.getString("id")
                    VerifiedDocument(id, byId[id]?.singleOrNull()?.name ?: "", p.getLong("length"), p.getString("sha"))
                }
                if (parts.isNotEmpty() && verify(context, tree, parts, byId, check)) {
                    val id = entry.getString("id")
                    eligible[id] = Set(id, entry.getLong("at"), parts, BackupNames.fileName(entry.getLong("at"), true))
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Unknown/corrupt ownership is never enough to delete. */ }
        }
        val root = File(context.noBackupFilesDir, "backup-retention").apply { if (!isDirectory && !mkdirs()) throw IOException("retention workspace") }
        val work = File(root, UUID.randomUUID().toString()).apply { if (!mkdir()) throw IOException("retention workspace") }
        try {
            val groups = linkedMapOf<String, MutableList<Probe>>()
            for (document in listed) {
                check()
                if (document.id in handled || !candidate(document.name) || byId[document.id]?.size != 1) continue
                val probeDir = File(work, UUID.randomUUID().toString()).apply { if (!mkdir()) throw IOException("retention probe") }
                try {
                    val fixed = File(probeDir, "input")
                    val verified = copy(context, tree, document, fixed, check)
                    val zip = fixed.inputStream().use { it.read() == 0x50 && it.read() == 0x4b }
                    if (zip) {
                        val header = BackupArchive.inspectPart(fixed, check)
                        // Known sets never acquire extra unrecorded documents under a reused set UUID.
                        if (header.setId !in eligible) groups.getOrPut(header.setId) { mutableListOf() }.add(Probe(header, verified))
                    } else {
                        // The progressive legacy reader enforces Bak3 and validates all records/resources, without merge.
                        val prepared = BackupRepository(context).prepareImportFiles(listOf(fixed), File(probeDir, "validated"), check)
                        if (prepared.snapshotAt > 0) eligible["legacy:${document.id}"] = Set("legacy:${document.id}", prepared.snapshotAt, listOf(verified), document.name)
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Preserve this individual historical file and continue. */ }
                finally { probeDir.deleteRecursively() }
            }
            for ((id, probes) in groups) {
                check()
                val groupDir = File(work, UUID.randomUUID().toString()).apply { if (!mkdir()) throw IOException("retention set") }
                try {
                    val parts = probes.mapIndexed { i, probe ->
                        val document = byId[probe.document.id]?.singleOrNull() ?: throw IOException("missing part")
                        val fixed = File(groupDir, "part-$i.sab")
                        val actual = copy(context, tree, document, fixed, check)
                        if (actual.length != probe.document.length || actual.sha != probe.document.sha) throw IOException("changed part")
                        fixed
                    }
                    // Includes missing/duplicate/mixed-index/root/content validation; a header probe alone never qualifies a set.
                    val prepared = BackupRepository(context).prepareImportFiles(parts, File(groupDir, "validated"), check)
                    eligible[id] = Set(id, prepared.snapshotAt, probes.map { it.document }, BackupNames.fileName(prepared.snapshotAt, true))
                } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Missing/corrupt sets remain untouched. */ }
                finally { groupDir.deleteRecursively() }
            }
            return eligible.values.toList()
        } finally { work.deleteRecursively() }
    }

    private fun copy(context: Context, tree: Uri, document: Document, file: File, check: () -> Unit): VerifiedDocument {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        val input = context.contentResolver.openInputStream(uri(tree, document.id)) ?: throw IOException("unreadable backup")
        input.use { source -> file.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check()
                val n = source.read(buffer); if (n < 0) break
                if (n > 0) { BackupTree.space(file.parentFile, n.toLong()); output.write(buffer, 0, n); digest.update(buffer, 0, n); count += n }
            }
            output.fd.sync()
        } }
        if (document.length >= 0 && document.length != count) throw IOException("changed length")
        return VerifiedDocument(document.id, document.name, count, hex(digest.digest()))
    }
    private fun verify(context: Context, tree: Uri, parts: List<VerifiedDocument>, listed: Map<String, List<Document>>, check: () -> Unit): Boolean {
        val seen = HashSet<String>()
        for (part in parts) {
            check()
            if (!seen.add(part.id) || part.length < 0 || !part.sha.matches(Regex("[0-9a-f]{64}"))) return false
            val current = listed[part.id]?.singleOrNull() ?: return false
            if (current.length >= 0 && current.length != part.length) return false
            var bytes = 0L
            val hash = MessageDigest.getInstance("SHA-256")
            val input = context.contentResolver.openInputStream(uri(tree, part.id)) ?: return false
            input.use { source -> val buffer = ByteArray(64 * 1024)
                while (true) { check(); val n = source.read(buffer); if (n < 0) break; if (n > 0) { bytes += n; if (bytes > part.length) return false; hash.update(buffer, 0, n) } }
            }
            if (bytes != part.length || hex(hash.digest()) != part.sha) return false
        }
        return true
    }
    private fun remove(context: Context, tree: Uri, sets: List<Set>, check: () -> Unit): Int {
        var count = 0
        val removed = HashSet<String>()
        for (set in sets) {
            check()
            // Re-list outside the per-file failure boundary: revoked folder access must surface.
            val current = list(context, tree).groupBy { it.id }
            val valid = try { verify(context, tree, set.parts, current, check) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            if (!valid) continue
            var complete = true
            for (part in set.parts) {
                val deleted = synchronized(BackupJobs) {
                    check()
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri(tree, part.id)) }.getOrDefault(false)
                }
                if (!deleted) { complete = false; break }
            }
            if (complete) { count++; removed.add(set.id) }
        }
        if (removed.isNotEmpty()) {
            check()
            val saved = ledger(context); val kept = JSONArray()
            for (i in 0 until saved.length()) {
                val entry = saved.optJSONObject(i) ?: continue
                if (entry.optString("tree") != tree.toString() || entry.optString("id") !in removed) kept.put(entry)
            }
            prefs(context).edit().putString(LEDGER, kept.toString()).commit()
        }
        return count
    }
}
