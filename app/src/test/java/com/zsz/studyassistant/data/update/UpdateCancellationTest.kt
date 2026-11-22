package com.zsz.studyassistant.data

import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import okio.source

/** Loopback-only synthetic HTTP; no GitHub, AI, credentials or installed app data. */
class UpdateCancellationTest {
    @Test fun slowDnsYieldsToTheMirrorWithoutWaitingForTheResolver() {
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                release.await(5, TimeUnit.SECONDS)
                throw UnknownHostException("synthetic")
            }
        }).build()
        val call = client.newCall(Request.Builder().url("https://fixture.invalid/").build())
        try {
            assertThrows(SocketTimeoutException::class.java) { call.awaitUpdateFirstByte(150, 0, 2).close() }
            assertTrue(call.isCanceled())
        } finally { release.countDown() }
    }

    @Test fun firstByteDeadlineIncludesTheBodyAndLeavesHealthyTransfersOpen() {
        fun callWithBody(firstByte: CountDownLatch): okhttp3.Call {
            val input = object : InputStream() {
                private var position = 0
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (position == 2) return -1
                    firstByte.await(5, TimeUnit.SECONDS)
                    buffer[offset] = "ab"[position++].code.toByte()
                    return 1
                }
            }
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(input.source().buffer().asResponseBody(contentLength = 2)).build()
            }.build()
            return client.newCall(Request.Builder().url("https://fixture.invalid/").build())
        }
        val gate = CountDownLatch(1)
        try {
            assertThrows(SocketTimeoutException::class.java) { callWithBody(gate).awaitUpdateFirstByte(150, 0, 2).close() }
        } finally { gate.countDown() }
        val healthy = callWithBody(CountDownLatch(0))
        healthy.awaitUpdateFirstByte(500, 0, 2).use { response ->
            Thread.sleep(550)
            assertEquals("ab", response.body.string())
            assertFalse(healthy.isCanceled())
        }
    }

    @Test fun cancellingDownloadClosesBlockedReadAndDoesNotTouchOtherFiles() = runBlocking {
        val server=ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())
        val release=CountDownLatch(1)
        val worker=thread(isDaemon=true) {
            try { server.accept().use { socket ->
                val input=socket.getInputStream().bufferedReader()
                while(true) { if(input.readLine().isNullOrEmpty()) break }
                val output=socket.getOutputStream()
                output.write("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\nConnection: close\r\n\r\nfixture".toByteArray())
                output.flush();release.await(5,TimeUnit.SECONDS)
            } } catch (_:Exception) { }
        }
        val directory=File("build/closeout-fixtures").apply { mkdirs() }
        val target=File.createTempFile("download-",".part",directory)
        val other=File.createTempFile("keep-",".apk",directory).apply { writeText("keep") }
        try {
            val entered=CompletableDeferred<Unit>()
            val task=launch { UpdateChecker.download("http://localhost:${server.localPort}/fixture",target) { entered.complete(Unit) } }
            withTimeout(4000) { entered.await() }
            withTimeout(2000) { task.cancelAndJoin() }
            assertTrue(task.isCancelled)
            assertEquals("keep",other.readText())
            assertTrue(target.length()<1000000)
        } finally { release.countDown();server.close();worker.join(1000);target.delete();other.delete() }
    }
}
