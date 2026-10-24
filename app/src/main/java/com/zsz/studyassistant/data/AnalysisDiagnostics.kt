package com.zsz.studyassistant.data

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable enum class AnalysisMode { THINKING, FAST }
@Serializable enum class AnalysisOutcome { SUCCESS, FAILURE, CANCELLED }
@Serializable enum class AnalysisFinish {
    STOP, LENGTH, FILTERED, OTHER, MISSING;
    companion object {
        fun from(raw: String?): AnalysisFinish = when (raw) {
            "stop" -> STOP; "length" -> LENGTH; "content_filter" -> FILTERED; null -> MISSING; else -> OTHER
        }
    }
}
@Serializable enum class AnalysisFailure {
    NONE, PARAMETERS, AUTH, BALANCE, RATE_LIMIT, SERVER, HTTP, TIMEOUT, NETWORK, DECODE,
    TRUNCATED, EMPTY, FORMAT, NO_USABLE, BLOCKED, INCOMPLETE, CHANGED, UNKNOWN;
    companion object {
        fun from(key: String): AnalysisFailure = when (key) {
            "tagMerge.analysisRequestRejected" -> PARAMETERS; "tagMerge.analysisAuth" -> AUTH
            "tagMerge.analysisBalance" -> BALANCE; "tagMerge.analysisBusy" -> RATE_LIMIT
            "tagMerge.analysisServer" -> SERVER; "tagMerge.analysisHttp" -> HTTP
            "tagMerge.analysisTimeout" -> TIMEOUT; "tagMerge.analysisNetwork" -> NETWORK
            "tagMerge.analysisDecode" -> DECODE; "tagMerge.aiTruncated" -> TRUNCATED
            "tagMerge.aiEmpty" -> EMPTY; "tagMerge.aiFormat" -> FORMAT
            "tagMerge.aiNoUsable" -> NO_USABLE; "tagMerge.aiBlocked" -> BLOCKED
            "tagMerge.aiIncomplete" -> INCOMPLETE; "tagMerge.changed" -> CHANGED
            else -> UNKNOWN
        }
    }
}

/** No free-form strings except the locally generated UUID. Never accepts request/response/exception text. */
@Serializable
data class AnalysisDiagnostic(val id: String, val startedAt: Long, val elapsedMs: Long,
    val mode: AnalysisMode, val tagCount: Int, val outcome: AnalysisOutcome,
    val finish: AnalysisFinish, val failure: AnalysisFailure, val httpCode: Int? = null,
    val accepted: Int? = null, val rejected: Int? = null)
@Serializable
data class AnalysisDiagnosticDocument(val version: Int = 1, val records: List<AnalysisDiagnostic>)

object AnalysisDiagnosticCodec {
    const val KEEP = 20
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }
    fun encode(value: AnalysisDiagnosticDocument): String { validate(value); return json.encodeToString(value) }
    fun decode(text: String): AnalysisDiagnosticDocument = json.decodeFromString<AnalysisDiagnosticDocument>(text).also(::validate)
    private fun validate(value: AnalysisDiagnosticDocument) {
        require(value.version == 1 && value.records.size <= KEEP)
        value.records.forEach {
            require(it.id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
            require(it.startedAt > 0 && it.elapsedMs >= 0 && it.tagCount >= 0)
            require(it.httpCode == null || it.httpCode in 100..599)
            require(it.accepted == null || it.accepted >= 0)
            require(it.rejected == null || it.rejected >= 0)
        }
    }
    fun append(value: AnalysisDiagnosticDocument, record: AnalysisDiagnostic): AnalysisDiagnosticDocument =
        AnalysisDiagnosticDocument(records = (value.records + record).takeLast(KEEP)).also(::validate)
}

internal class AnalysisDiagnosticsStore(context: Context) {
    companion object { private val lock = Any() }
    private val file = PrivateJsonFile(File(context.noBackupFilesDir, "tag-analysis-diagnostics-v1.json"), 128_000)
    fun read(): AnalysisDiagnosticDocument = synchronized(lock) {
        file.read()?.let(AnalysisDiagnosticCodec::decode) ?: AnalysisDiagnosticDocument(records = emptyList())
    }
    fun append(record: AnalysisDiagnostic): AnalysisDiagnosticDocument = synchronized(lock) {
        AnalysisDiagnosticCodec.append(read(), record).also { file.write(AnalysisDiagnosticCodec.encode(it)) }
    }
    fun export(): String = synchronized(lock) { AnalysisDiagnosticCodec.encode(read()) }
    fun clear() = synchronized(lock) { file.clear() }
}
