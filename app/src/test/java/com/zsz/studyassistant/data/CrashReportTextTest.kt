package com.zsz.studyassistant.data

import org.junit.Assert.*
import org.junit.Test

class CrashReportTextTest {
    @Test fun legacyReportOnlyKeepsHeaderMetadata() {
        val report = CrashReportText.extract("""
            时间：2026-11-16 12:30:00
            应用版本：0.7.3.7
            线程：a private question
            异常：java.lang.IllegalStateException: sk-secret https://example.com/private
            java.lang.IllegalStateException: secret question
                at com.zsz.studyassistant.Example.run(Example.kt:42)
            Caused by: java.io.IOException: /data/user/0/private/photo.jpg
                at java.lang.Thread.run(Thread.java:12)
                at sample.Foo.run(/data/private/Secret.kt:14)
        """.trimIndent())
        assertTrue(report.contains("Crash app version: 0.7.3.7"))
        assertFalse(report.contains("java.io.IOException"))
        assertFalse(report.contains("Example.kt:42"))
        listOf("sk-secret", "https://", "question", "/data/", "Secret.kt").forEach { assertFalse(report.contains(it)) }
    }

    @Test fun structuredReportIgnoresMessagesThatImpersonateStackFrames() {
        val error = IllegalStateException("secret\nprivate.student.answer\nat private.student.answer(Secret.kt:42)",
            java.io.IOException("sk-secret"))
        error.stackTrace = arrayOf(StackTraceElement("com.example.Example", "run", "/data/private/Example.kt", 42))
        val report = CrashReportText.fromThrowable(error)
        assertTrue(report.contains("IllegalStateException"))
        assertTrue(report.contains("IOException"))
        assertTrue(report.contains("Example.kt:42"))
        listOf("secret", "private.student", "Secret.kt", "/data/").forEach { assertFalse(report.contains(it)) }
    }

    @Test fun ignoresUnknownTextAndBoundsLargeLogs() {
        assertEquals("", CrashReportText.extract("token=secret\nprivate contents\n应用版本：secret"))
        val report = CrashReportText.extract("at com.example.A.run(A.kt:1)\n".repeat(100_000))
        assertTrue(report.length < 17 * 1024)
    }
}
