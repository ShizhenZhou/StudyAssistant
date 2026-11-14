package com.zsz.studyassistant.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class AiFilesTest {
    @Test fun ocrPreservesRawPagesAndDoesNotHideTruncationOrErrors() {
        val body = """
            {"id":"ocr-example","purpose":"text_recognition","page_count":2,
            "pages":[{"index":1,"text":"partial","finish_reason":"length"},
            {"index":0,"text":"<｜ref｜>formula<｜/ref｜>","finish_reason":"stop"}],
            "usage":{"input_tokens":10,"output_tokens":2,"total_tokens":12}}
        """
        val result = decodeOcrResult(body.toResponseBody())
        assertFalse(result.complete)
        assertEquals("<｜ref｜>formula<｜/ref｜>", result.selectedPages(setOf(0)).single().text)
        assertEquals(12L, result.usage!!.totalTokens)
        assertThrows(IOException::class.java) { decodeOcrResult("""{"error":{"message":"private text"}}""".toResponseBody()) }
        assertThrows(IOException::class.java) { decodeOcrResult(body.replace("\"index\":1", "\"index\":0").toResponseBody()) }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            decodeOcrResult(body.toResponseBody()) { throw kotlinx.coroutines.CancellationException() }
        }
    }

    @Test fun referencesCannotCrossAccountsOrOutliveTheirExpiry() {
        val reference = RemoteAiFile(DeepSeekFile("file-api-example", 2000), aiAccountScope("first"))
        val messages = listOf(DeepSeekMessage("user", JsonPrimitive("question")))
        val attached = attachDocuments(messages, listOf(reference), "first", 1000)
        assertEquals(JsonPrimitive("file-api-example"), (attached.last().content as JsonArray).last().jsonObject["file_id"])
        assertEquals(JsonPrimitive("question"), messages.last().content)
        assertThrows(IllegalArgumentException::class.java) { attachDocuments(messages, listOf(reference), "second", 1000) }
        assertThrows(IllegalArgumentException::class.java) { attachDocuments(messages, listOf(reference), "first", 1800) }
        assertThrows(IllegalArgumentException::class.java) { attachDocuments(messages, List(21) { reference }, "first", 1000) }
    }

    @Test fun uploadIsSingleShotWithExpiryAndOcrHasOnlyItsTwoParts(): Unit = runBlocking {
        val original = File.createTempFile("synthetic-document", ".pdf")
        original.writeText("synthetic test data; no real document")
        try {
            var uploaded = 0
            val files = object : DeepSeekFileService {
                override suspend fun upload(authorization: String, purpose: RequestBody, anchor: RequestBody,
                    seconds: RequestBody, file: MultipartBody.Part): DeepSeekFile {
                    uploaded++
                    assertEquals("Bearer synthetic", authorization)
                    assertEquals("rich_layout_recognition", purpose.text())
                    assertEquals("created_at", anchor.text()); assertEquals("86400", seconds.text())
                    assertTrue(file.body.isOneShot()); assertEquals(original.length(), file.body.contentLength())
                    assertEquals(original.readText(), file.body.text())
                    throw IOException("uncertain upload")
                }
                override suspend fun retrieve(authorization: String, id: String) = error("unused")
                override suspend fun list(authorization: String, after: String?, limit: Int, order: String, purpose: String?) = error("unused")
                override suspend fun delete(authorization: String, id: String) = error("unused")
            }
            var recognized = 0
            val ocr = object : DeepSeekOcrService {
                override suspend fun recognize(authorization: String, purpose: RequestBody, file: MultipartBody.Part): ResponseBody {
                    recognized++
                    assertEquals("Bearer synthetic", authorization)
                    assertEquals("text_recognition", purpose.text())
                    return """{"id":"ocr-one","purpose":"text_recognition","page_count":1,"pages":[{"index":0,"text":"result","finish_reason":"stop"}]}""".toResponseBody()
                }
            }
            val session = AiFileSession(files, ocr, "synthetic", { 1000 })
            try { session.uploadDocument(original); fail("Expected uncertain upload") } catch (_: IOException) { }
            assertEquals(1, uploaded)
            assertTrue(session.recognize(original).complete); assertEquals(1, recognized)
            assertTrue(original.isFile)
            val prepared = documentPart(original)
            original.appendText("changed")
            assertThrows(IOException::class.java) { prepared.body.writeTo(okio.Buffer()) }
        } finally { original.delete() }
    }

    @Test fun cancellingOcrClosesAnAlreadyStreamingBody(): Unit = runBlocking {
        val original = File.createTempFile("synthetic-ocr", ".pdf").apply { writeText("synthetic") }
        val started = CompletableDeferred<Unit>()
        val released = java.util.concurrent.CountDownLatch(1)
        val source = object : okio.Source {
            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                started.complete(Unit)
                if (!released.await(3, java.util.concurrent.TimeUnit.SECONDS)) throw IOException("Read was not cancelled")
                return -1
            }
            override fun timeout() = okio.Timeout.NONE
            override fun close() { released.countDown() }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): okhttp3.MediaType? = null
            override fun contentLength() = -1L
            override fun source() = source
        }
        val files = object : DeepSeekFileService {
            override suspend fun upload(authorization: String, purpose: RequestBody, anchor: RequestBody, seconds: RequestBody, file: MultipartBody.Part) = error("unused")
            override suspend fun retrieve(authorization: String, id: String) = error("unused")
            override suspend fun list(authorization: String, after: String?, limit: Int, order: String, purpose: String?) = error("unused")
            override suspend fun delete(authorization: String, id: String) = error("unused")
        }
        val ocr = object : DeepSeekOcrService {
            override suspend fun recognize(authorization: String, purpose: RequestBody, file: MultipartBody.Part) = body
        }
        try {
            withTimeout(2000) {
                val job = launch { AiFileSession(files, ocr, "synthetic").recognize(original) }
                started.await()
                job.cancelAndJoin()
                assertEquals(0L, released.count)
            }
        } finally { body.close(); original.delete() }
    }

    private fun RequestBody.text() = okio.Buffer().also { writeTo(it) }.readUtf8()
}
