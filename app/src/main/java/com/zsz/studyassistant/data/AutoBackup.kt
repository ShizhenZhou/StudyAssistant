package com.zsz.studyassistant.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自动备份（B8）：**每天第一次打开应用**时把整库导出成 JSON，写进用户自己选的文件夹。
 *
 * 设计取舍：
 *  - **必须由用户选一次文件夹**（SAF `ACTION_OPEN_DOCUMENT_TREE` + 持久化授权），备份才生效。
 *    不提供"默认写到 App 私有目录"的兜底 —— 那种备份在**卸载/清除数据时就一起没了**，
 *    恰好是最需要备份的场景，留着只会给人虚假的安全感。
 *  - **只保留最近 [KEEP] 份**：写完新备份后按文件名（时间戳，定宽 → 字典序即时间序）倒序裁剪。
 *  - **只碰自己写出来的文件**：所有读写/删除都限定 `study-assistant-auto-*.json` 前缀，
 *    用户选中的文件夹里可能有他自己的文件，绝不能动。
 *  - 不联网、不上传，只往那个文件夹里写文件。
 *  - 失败**静默**：自动备份不该在启动时弹任何东西（手动点「立即备份」才提示结果）。
 */
object AutoBackup {

    private const val TAG = "AutoBackup"
    const val KEEP = 3
    /** 文件名前缀：裁剪/统计只认它，避免误删用户自己的文件 */
    const val PREFIX = "study-assistant-auto-"
    private const val SUFFIX = ".json"
    private const val MIME = "application/json"

    private const val PREFS = "settings"
    private const val KEY_TREE = "auto_backup_tree"
    private const val KEY_ENABLED = "auto_backup_enabled"
    private const val KEY_LAST_DAY = "auto_backup_last_day"
    private const val KEY_LAST_AT = "auto_backup_last_at"

    // ── 纯逻辑（单元测试覆盖，见 AutoBackupTest）─────────────────────────────

    /** 本地日期键 `yyyy-MM-dd`：判断"今天是否已经备份过" */
    fun dayKey(at: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(at))

    /** 备份文件名（定宽时间戳 → 字典序倒排就是时间倒排） */
    fun backupFileName(at: Long): String =
        PREFIX + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(at)) + SUFFIX

    /**
     * 该删哪些旧备份：只认 [PREFIX]…[SUFFIX] 的文件，按名字倒序（= 新的在前）保留前 [keep] 个，
     * 其余返回（即待删除清单）。
     */
    fun pruneTargets(names: List<String>, keep: Int = KEEP): List<String> {
        val mine = names.filter { it.startsWith(PREFIX) && it.endsWith(SUFFIX) }
        return mine.sortedDescending().drop(keep)
    }

    /** 是否该在今天跑一次：开关开着 + 已选文件夹 + 今天还没备份过 */
    fun shouldRunToday(enabled: Boolean, hasFolder: Boolean, lastDay: String?, today: String): Boolean =
        enabled && hasFolder && lastDay != today

    // ── 设置与状态（SharedPreferences("settings")，与主题/语言同一个文件）──────

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    fun treeUri(context: Context): Uri? =
        prefs(context).getString(KEY_TREE, null)?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }

    /** 保存文件夹（调用方需先 takePersistableUriPermission；传 null = 清除） */
    fun setTreeUri(context: Context, uri: Uri?) {
        prefs(context).edit().apply {
            if (uri == null) remove(KEY_TREE) else putString(KEY_TREE, uri.toString())
        }.apply()
    }

    /** 上次**成功**备份的时间（epoch ms；0 = 从未备份过） */
    fun lastBackupAt(context: Context): Long = prefs(context).getLong(KEY_LAST_AT, 0L)

    /** 上次成功备份的日期键（null = 从未备份过） */
    fun lastBackupDay(context: Context): String? =
        prefs(context).getString(KEY_LAST_DAY, null)?.takeIf { it.isNotBlank() }

    private fun markBackedUp(context: Context, at: Long) {
        prefs(context).edit()
            .putString(KEY_LAST_DAY, dayKey(at))
            .putLong(KEY_LAST_AT, at)
            .apply()
    }

    // ── 文件夹访问（用框架的 DocumentsContract，不引额外依赖）──────────────────

    private fun treeDocUri(context: Context): Uri? {
        val tree = treeUri(context) ?: return null
        return runCatching {
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        }.getOrNull()
    }

    /** 已选文件夹的显示名（读不到就返回 null，界面退回"已选择"） */
    fun folderName(context: Context): String? {
        val doc = treeDocUri(context) ?: return null
        return runCatching {
            context.contentResolver.query(
                doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    /** 文件夹里的子项（名字 → 文档 Uri） */
    private fun children(context: Context): List<Pair<String, Uri>> {
        val tree = treeUri(context) ?: return emptyList()
        return runCatching {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            val out = mutableListOf<Pair<String, Uri>>()
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    out += name to DocumentsContract.buildDocumentUriUsingTree(tree, id)
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    /** 已有的自动备份文件名（新的在前）；文件夹没设置或读不到 → 空 */
    fun listBackups(context: Context): List<String> =
        children(context)
            .map { it.first }
            .filter { it.startsWith(PREFIX) && it.endsWith(SUFFIX) }
            .sortedDescending()

    /** 该不该现在跑（纯判断，供启动时调用） */
    fun isDue(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        shouldRunToday(isEnabled(context), treeUri(context) != null, lastBackupDay(context), dayKey(now))

    /** 备份结果 */
    data class Result(
        val ok: Boolean,
        val fileName: String?,
        /** 本次裁掉的旧备份份数 */
        val pruned: Int = 0,
        val error: String? = null
    )

    /**
     * 启动时调用：**满足条件才**生成 JSON 并写盘；不满足返回 null（什么都不做，也不打扰用户）。
     * 文件 IO 在 IO 线程；[jsonProvider] 由调用方提供（ViewModel 从数据库导出）。
     */
    suspend fun runIfDue(context: Context, jsonProvider: suspend () -> String): Result? {
        val now = System.currentTimeMillis()
        if (!isDue(context, now)) return null
        val json = runCatching { jsonProvider() }.getOrElse {
            Log.w(TAG, "build backup json failed: ${it.message}")
            return null
        }
        return runBackup(context, json, now)
    }

    /** 真正写盘：新建文件 → 写入 → 裁剪到最近 [KEEP] 份 → 记录时间 */
    suspend fun runBackup(
        context: Context,
        json: String,
        now: Long = System.currentTimeMillis()
    ): Result = withContext(Dispatchers.IO) {
        val parent = treeDocUri(context)
            ?: return@withContext Result(false, null, error = "未选择备份文件夹")
        try {
            val name = backupFileName(now)
            val created = DocumentsContract.createDocument(context.contentResolver, parent, MIME, name)
                ?: return@withContext Result(false, null, error = "无法在该文件夹创建文件")
            context.contentResolver.openOutputStream(created)?.use { out ->
                out.write(json.toByteArray())
                out.flush()
            } ?: return@withContext Result(false, null, error = "无法写入备份文件")

            // 裁剪：只认自己写的文件，保留最近 KEEP 份
            val mine = children(context).filter { it.first.startsWith(PREFIX) && it.first.endsWith(SUFFIX) }
            val toDelete = pruneTargets(mine.map { it.first }).toHashSet()
            var pruned = 0
            for ((n, uri) in mine) {
                if (n in toDelete && runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                        .getOrDefault(false)) pruned++
            }
            markBackedUp(context, now)
            Result(true, name, pruned)
        } catch (e: Exception) {
            Log.w(TAG, "backup failed: ${e.message}")
            Result(false, null, error = e.message ?: e.javaClass.simpleName)
        }
    }

    /** 清空自动备份（只删自己写的文件），返回删除份数 */
    suspend fun clearBackups(context: Context): Int = withContext(Dispatchers.IO) {
        var n = 0
        for ((name, uri) in children(context)) {
            if (name.startsWith(PREFIX) && name.endsWith(SUFFIX) &&
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
            ) n++
        }
        prefs(context).edit().remove(KEY_LAST_DAY).remove(KEY_LAST_AT).apply()
        n
    }
}
