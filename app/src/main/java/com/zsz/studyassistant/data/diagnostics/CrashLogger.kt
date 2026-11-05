package com.zsz.studyassistant.data

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志（本地记录，一键导出）。
 *
 * 设计取舍（自用场景）：
 * - **只存本机**：写入 `filesDir/crashes/crash-<时间>.txt`，不做任何上传、不申请新权限；
 *   用户可在「设置 → 数据管理」里导出成 txt 自己发出去（或清空）。
 * - **兜住所有未捕获异常**：在 [install] 里替换默认 `UncaughtExceptionHandler`，
 *   写完日志后**必须转交原 handler**，否则系统不会走正常的崩溃流程（表现为"卡死不退"）。
 * - **只留最近 10 个**：崩溃文件可能包含大量堆栈，自用不必留档太多，写入后按名字（= 时间）倒序裁剪。
 * - 写入本身可能失败（磁盘满/权限），全部 `runCatching` 吞掉——崩溃时绝不能再抛异常。
 */
object CrashLogger {

    private const val TAG = "CrashLogger"
    private const val DIR = "crashes"
    /** 最多保留的崩溃文件个数（超出删最旧的） */
    private const val KEEP = 10

    @Volatile
    private var installed = false

    /** 在 Application.onCreate 里调用一次；重复调用无副作用 */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write(app, t, e) }.onFailure { Log.w(TAG, "写崩溃日志失败: ${it.message}") }
            // 交回原 handler：让系统照常结束进程 / 弹系统崩溃框
            prev?.uncaughtException(t, e)
        }
    }

    /** 崩溃日志目录（不存在则创建） */
    fun dir(context: Context): File = File(context.filesDir, DIR).apply { runCatching { mkdirs() } }

    /** 现有崩溃文件，**新的在前** */
    fun files(context: Context): List<File> =
        dir(context).listFiles()?.filter { it.isFile }?.sortedByDescending { it.name } ?: emptyList()

    fun count(context: Context): Int = files(context).size

    /** 最近一次崩溃的时间戳（毫秒，取自文件名）；没有崩溃返回 null */
    fun lastCrashAt(context: Context): Long? =
        files(context).firstOrNull()?.name?.substringAfter("crash-", "")?.substringBefore(".txt")
            ?.let { runCatching { FILE_TS.parse(it)?.time }.getOrNull() }

    /** 全部崩溃日志拼成一份文本（新的在前，带分隔线）；没有崩溃返回空串 */
    fun dumpText(context: Context): String {
        val list = files(context)
        if (list.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("StudyAssistant 崩溃日志（共 ").append(list.size).append(" 份，新的在前）\n")
        for (f in list) {
            sb.append("\n================ ").append(f.name).append(" ================\n")
            sb.append(runCatching { f.readText() }.getOrDefault("（本文件读取失败）"))
            if (!sb.endsWith("\n")) sb.append('\n')
        }
        return sb.toString()
    }

    /** 清空全部崩溃日志，返回删除的文件个数 */
    fun clear(context: Context): Int {
        var n = 0
        for (f in files(context)) if (runCatching { f.delete() }.getOrDefault(false)) n++
        return n
    }

    /** 导出用的默认文件名（带时间戳，便于区分） */
    fun exportFileName(): String =
        "study-assistant-crash-" + FILE_TS.format(Date()) + ".txt"

    /** 真正的落盘：记录 App 版本、设备、线程、异常与完整堆栈 */
    private fun write(context: Context, thread: Thread, e: Throwable) {
        val ts = FILE_TS.format(Date())
        val f = File(dir(context), "crash-$ts.txt")
        f.writeText(buildString {
            append("时间：").append(READABLE_TS.format(Date())).append('\n')
            append("应用版本：").append(UpdateChecker.installedVersion(context)).append('\n')
            append("Android：").append(Build.VERSION.RELEASE).append("（API ").append(Build.VERSION.SDK_INT).append("）\n")
            append("机型：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("线程：").append(thread.name).append('\n')
            append("异常：").append(e.javaClass.name).append(": ").append(e.message ?: "").append('\n')
            append("\n--- 堆栈 ---\n")
            append(Log.getStackTraceString(e))
        })
        prune(context)
    }

    /** 只保留最近 [KEEP] 份 */
    private fun prune(context: Context) {
        val list = files(context)
        if (list.size <= KEEP) return
        for (f in list.drop(KEEP)) runCatching { f.delete() }
    }

    private val FILE_TS = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val READABLE_TS = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
}
