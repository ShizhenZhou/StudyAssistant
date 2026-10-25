package com.zsz.studyassistant.data

import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Loopback-only synthetic HTTP; no GitHub, AI, credentials or installed app data. */
class UpdateCancellationTest {
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
