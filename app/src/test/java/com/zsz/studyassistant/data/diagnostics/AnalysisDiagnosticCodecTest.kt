package com.zsz.studyassistant.data

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class AnalysisDiagnosticCodecTest {
    private fun record(at: Long = 100) = AnalysisDiagnostic(UUID.randomUUID().toString(), at, 12,
        AnalysisMode.THINKING, 268, AnalysisOutcome.SUCCESS, AnalysisFinish.STOP, AnalysisFailure.NONE,
        200, 67, 0)
    @Test fun retentionKeepsLatestTwentyInOrder() {
        var document = AnalysisDiagnosticDocument(records = emptyList())
        repeat(25) { document = AnalysisDiagnosticCodec.append(document, record(100L + it)) }
        assertEquals(20, document.records.size)
        assertEquals(105L, document.records.first().startedAt)
        assertEquals(document, AnalysisDiagnosticCodec.decode(AnalysisDiagnosticCodec.encode(document)))
    }
    @Test fun arbitraryServerOrExceptionStringsOnlyBecomeFixedEnums() {
        assertEquals(AnalysisFinish.OTHER, AnalysisFinish.from("private response marker"))
        assertEquals(AnalysisFailure.UNKNOWN, AnalysisFailure.from("private exception marker"))
        assertEquals(AnalysisFailure.TRUNCATED, AnalysisFailure.from("tagMerge.aiTruncated"))
    }
    @Test fun unknownRawFieldsAreNeverIncludedInExport() {
        val document = AnalysisDiagnosticDocument(records = listOf(record()))
        val encoded = AnalysisDiagnosticCodec.encode(document)
        val extra = encoded.dropLast(1) + ",\"rawResponse\":\"private marker\"}"
        val exported = AnalysisDiagnosticCodec.encode(AnalysisDiagnosticCodec.decode(extra))
        assertFalse(exported.contains("private marker")); assertFalse(exported.contains("rawResponse"))
    }
    @Test fun freeFormIdAndUnknownVersionsAreRejected() {
        for (bad in listOf(AnalysisDiagnosticDocument(2, listOf(record())),
            AnalysisDiagnosticDocument(records = listOf(record().copy(id = "private content"))),
            AnalysisDiagnosticDocument(records = listOf(record().copy(httpCode = 999))))) {
            try { AnalysisDiagnosticCodec.encode(bad); fail("Expected invalid diagnostic") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
