package com.zsz.studyassistant.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic data in the independent integration APK; never opens the personal database. */
@RunWith(AndroidJUnit4::class)
class BackupLargeLibraryIntegrationTest {
    private val context=ApplicationProvider.getApplicationContext<Context>().also {
        check(it.packageName=="com.zsz.studyassistant.integration")
    }
    private fun database():AppDatabase=Room.databaseBuilder(context,AppDatabase::class.java,
        "large-backup-fixture-${UUID.randomUUID()}.db").build().also { it.tagUndoStore=SqliteTagUndoStore(context) }
    private fun closeFixture(database:AppDatabase) {
        val name=database.openHelper.writableDatabase.path?.let { File(it).name }
        (database.tagUndoStore as? SqliteTagUndoStore)?.close()
        database.close()
        if(name!=null) context.deleteDatabase(name)
    }

    @Test fun ninetySixMiBLibraryStreamsThroughCompletePipeline() = runBlocking {
        val source=database(); val target=database()
        val work=File(context.cacheDir,"large-backup-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val sampling=AtomicBoolean(true); val peak=AtomicLong(0)
        val sampler=Thread({
            while(sampling.get()) {
                val runtime=Runtime.getRuntime(); val used=runtime.totalMemory()-runtime.freeMemory()
                peak.accumulateAndGet(used) { previous,current -> maxOf(previous,current) }
                try { Thread.sleep(20) } catch(_:InterruptedException) { break }
            }
        },"synthetic-backup-heap-sampler")
        val start=System.nanoTime(); val captureStart=AtomicLong(0); val captureDuration=AtomicLong(0)
        try {
            val text="s".repeat(1024*1024) // One reused 1 MiB body; no library-sized list.
            val expectedTextHash=MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            val image=ByteArray(4096) { (it%251).toByte() }
            val imageKey=Base64.getEncoder().encodeToString(image)
            fun at(index:Int)=1700000010000L+index
            fun id(index:Int)="00000000-0000-4000-8000-${index.toString(16).padStart(12,'0')}-${at(index)}"
            fun conversation(index:Int)="""[{"id":$index,"role":"question","content":"synthetic","images":["$imageKey"],"thinking":"synthetic thinking $index"}]"""
            sampler.start()
            for(index in 0 until 96) {
                source.questionDao().insert(Question(id(index),text,"synthetic answer $index",at(index),image,conversation(index),deleted=index%2==0))
                source.questionDao().insertImages(listOf(QuestionImage(questionId=id(index),position=0,bytes=image,source="synthetic",createdAt=at(index)+1)))
                source.questionDao().insertSolveSession(SolveSession(id=id(index),createdAt=at(index),updatedAt=at(index)+2,source="text",text="synthetic history $index",answer="synthetic answer $index",conversationJson=conversation(index),imageBytes=image,savedQuestionId=id(index)))
            }
            val archive=BackupRepository(context,source).export(File(work,"export"),progress={ phase,_ ->
                if(phase=="data.backupSnapshot") captureStart.compareAndSet(0,System.nanoTime())
                else if(captureStart.get()!=0L) captureDuration.compareAndSet(0,System.nanoTime()-captureStart.get())
            })
            val importer=BackupRepository(context,target)
            val prepared=importer.prepareImportFiles(archive.files,File(work,"import"))
            assertEquals(96,prepared.stats.questions); assertEquals(96,prepared.stats.sessions)
            val result=importer.merge(prepared)
            assertEquals(96,result.questions); assertEquals(96,result.sessions)
            for(index in 0 until 96) {
                val question=target.questionDao().heavyOnce(id(index))!!
                assertEquals(id(index),question.id); assertEquals(at(index),question.createdAt)
                assertArrayEquals(expectedTextHash,MessageDigest.getInstance("SHA-256").digest(question.text.toByteArray()))
                assertEquals("synthetic answer $index",question.answer); assertEquals(index%2==0,question.deleted)
                assertArrayEquals(image,question.imageBytes)
                val original=target.questionDao().imagesForQuestion(id(index)).single()
                assertEquals(0,original.position); assertEquals("synthetic",original.source)
                assertEquals(at(index)+1,original.createdAt); assertArrayEquals(image,original.bytes)
                val session=target.questionDao().solveSessionOnce(id(index))!!
                assertEquals(id(index),session.id); assertEquals(id(index),session.savedQuestionId)
                assertEquals(at(index),session.createdAt); assertEquals(at(index)+2,session.updatedAt)
                assertEquals("synthetic history $index",session.text); assertArrayEquals(image,session.imageBytes)
                val expected=Json.parseToJsonElement(conversation(index))
                assertEquals(expected,Json.parseToJsonElement(question.conversationJson!!))
                assertEquals(expected,Json.parseToJsonElement(session.conversationJson!!))
            }
            assertEquals(1,File(work,"export/tree/resources").listFiles()!!.size)
        } finally {
            sampling.set(false); sampler.interrupt(); if(sampler.isAlive) sampler.join(2000)
            android.util.Log.i("BackupSyntheticStress","rows=96 textBytes=100663296 captureMs=${captureDuration.get()/1000000} totalMs=${(System.nanoTime()-start)/1000000} javaObservedPeakBytes=${peak.get()} sampleIntervalMs=20")
            closeFixture(source); closeFixture(target); work.deleteRecursively()
        }
    }
}
