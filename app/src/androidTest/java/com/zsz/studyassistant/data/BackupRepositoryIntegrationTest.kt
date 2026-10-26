package com.zsz.studyassistant.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Synthetic databases only; never opens the installed application's database. */
@RunWith(AndroidJUnit4::class)
class BackupRepositoryIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>().also { check(it.packageName == "com.zsz.studyassistant.integration") }
    private fun database(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java,
        "backup-fixture-${UUID.randomUUID()}.db").build().also { it.tagUndoStore = SqliteTagUndoStore(context) }
    private fun closeFixture(database:AppDatabase) {
        val name=database.openHelper.writableDatabase.path?.let { File(it).name }
        (database.tagUndoStore as? SqliteTagUndoStore)?.close()
        database.close()
        if(name!=null) context.deleteDatabase(name)
    }
    private fun workspace() = File(context.cacheDir, "backup-fixture-${UUID.randomUUID()}").apply { mkdirs() }

    @Test fun archiveRoundTripPreservesFieldsAndGlobalImageDedup() = runBlocking {
        val source=database(); val target=database(); val work=workspace()
        try {
            val id=QuestionIdentity.create(1700000000000); val bytes=byteArrayOf(1,2,3,4)
            val encoded=java.util.Base64.getEncoder().encodeToString(bytes)
            val conversation="""[{"id":7,"role":"question","content":"内容","images":["$encoded"],"thinking":"思考"}]"""
            val dao=source.questionDao()
            val category=dao.insertCategory(Category(name="synthetic category",createdAt=123))
            dao.insert(Question(id,"large "+"甲".repeat(900000),"answer",1700000000000,bytes,conversation,true,category))
            dao.insertImages(listOf(QuestionImage(questionId=id,position=0,bytes=bytes,source="fixture",createdAt=456)))
            dao.insertSolveSession(SolveSession(id,1700000000000,1700000000007,"ask","history","answer",conversation,bytes,null,id))
            val archive=BackupRepository(context,source).export(File(work,"export"),volumeBytes=65536)
            val tree=File(work,"tree")
            BackupArchive.readParts(archive.files.reversed(),tree)
            assertEquals(1, File(tree,"resources").walkTopDown().count { it.isFile })
            val repo=BackupRepository(context,target)
            val prepared=repo.prepareImportFiles(archive.files.reversed(),File(work,"import"))
            assertEquals(1,prepared.stats.questions)
            val result=repo.merge(prepared)
            assertEquals(1,result.questions); assertEquals(1,result.sessions)
            val restored=target.questionDao().heavyOnce(id)!!
            assertEquals("large "+"甲".repeat(900000),restored.text)
            assertTrue(restored.deleted); assertArrayEquals(bytes,restored.imageBytes)
            assertEquals(1700000000000,restored.createdAt)
            assertEquals("fixture",target.questionDao().imagesForQuestion(id).single().source)
            assertEquals(456,target.questionDao().imagesForQuestion(id).single().createdAt)
            assertEquals(id,target.questionDao().solveSessionOnce(id)!!.savedQuestionId)
            assertEquals(1700000000007,target.questionDao().solveSessionOnce(id)!!.updatedAt)
            assertTrue(restored.conversationJson!!.contains("思考"))
            assertEquals(0,repo.merge(prepared).questions)
        } finally { closeFixture(source); closeFixture(target); work.deleteRecursively() }
    }

    @Test fun duplicateIdSkipsAllContentAndDoesNotIntroduceUnusedDefinitions() = runBlocking {
        val source=database(); val target=database(); val work=workspace()
        try {
            val id=QuestionIdentity.create(1700000000001)
            val category=source.questionDao().insertCategory(Category(name="skip category"))
            val tag=source.questionDao().insertTag(Tag(name="skip tag"))
            source.questionDao().insert(Question(id,"remote","remote",1700000000001,categoryId=category))
            source.questionDao().insertQuestionTagRaw(QuestionTag(id,tag))
            target.questionDao().insert(Question(id,"local","local",1700000000001))
            val archive=BackupRepository(context,source).export(File(work,"export"))
            val repo=BackupRepository(context,target)
            val result=repo.merge(repo.prepareImportFiles(archive.files,File(work,"import")))
            assertEquals(1,result.skipped); assertEquals("local",target.questionDao().heavyOnce(id)!!.text)
            assertTrue(target.questionDao().allCategoriesOnce().isEmpty())
            assertTrue(target.questionDao().getAllTagsOnce().isEmpty())
        } finally { closeFixture(source); closeFixture(target); work.deleteRecursively() }
    }

    @Test fun cancellationRollsBackWholeMerge() = runBlocking {
        val source=database(); val target=database(); val work=workspace()
        try {
            repeat(3) { val at=1700000000010+it.toLong(); source.questionDao().insert(Question(QuestionIdentity.create(at),"fixture","a",at)) }
            val archive=BackupRepository(context,source).export(File(work,"export"))
            val repo=BackupRepository(context,target)
            val prepared=repo.prepareImportFiles(archive.files,File(work,"import"))
            try { repo.merge(prepared) {
                val inserted=target.openHelper.writableDatabase.query("SELECT count(*) FROM questions").use { it.moveToFirst(); it.getInt(0) }
                if(inserted>0) throw kotlinx.coroutines.CancellationException("fixture after insertion")
            }; fail("Expected cancellation") }
            catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals(0,target.questionDao().countQuestions())
        } finally { closeFixture(source); closeFixture(target); work.deleteRecursively() }
    }
    @Test fun legacyV3IsStagedAndDuplicateIdsAreRejectedBeforeMerge() = runBlocking {
        val target=database(); val work=workspace()
        try {
            val id=QuestionIdentity.create(1700000000050)
            val q=BackupQuestion(id,"legacy","answer",1700000000050)
            val input=File(work,"legacy.json").apply { writeText(Json { encodeDefaults=true }.encodeToString(BackupFile(questions=listOf(q))).dropLast(1)+",\"unknownPrimitive\":true}") }
            val repo=BackupRepository(context,target)
            val prepared=repo.prepareImportFiles(listOf(input),File(work,"import"))
            assertEquals(1,repo.merge(prepared).questions)
            assertEquals("legacy",target.questionDao().heavyOnce(id)!!.text)
            val duplicate=File(work,"duplicate.json").apply { writeText(Json { encodeDefaults=true }.encodeToString(BackupFile(questions=listOf(q,q)))) }
            try { repo.prepareImportFiles(listOf(duplicate),File(work,"duplicate")); fail("Duplicate permanent IDs must be rejected") }
            catch(_:BackupValidationException) { }
            assertEquals(1,target.questionDao().countQuestions())
        } finally { closeFixture(target); work.deleteRecursively() }
    }

    @Test fun previewCannotBeReplacedWithIncompleteOrModifiedStaging() = runBlocking {
        val source=database(); val target=database(); val work=workspace()
        try {
            val id=QuestionIdentity.create(1700000000080)
            source.questionDao().insert(Question(id,"fixture","a",1700000000080))
            val archive=BackupRepository(context,source).export(File(work,"export"))
            val repo=BackupRepository(context,target)
            val prepared=repo.prepareImportFiles(archive.files,File(work,"import"))
            File(prepared.directory,"validated.db").appendBytes(byteArrayOf(1))
            try { repo.merge(prepared); fail("Changed staging must be rejected") }
            catch(_:BackupValidationException) { }
            assertEquals(0,target.questionDao().countQuestions())
        } finally { closeFixture(source); closeFixture(target); work.deleteRecursively() }
    }
    @Test fun api35ReadOnlySnapshotAllowsWriterAndKeepsOneConsistentMoment() = runBlocking {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT>=35)
        val source=database(); val target=database(); val work=workspace()
        try {
            val first=QuestionIdentity.create(1700000000100); val second=QuestionIdentity.create(1700000000101)
            source.questionDao().insert(Question(first,"old","a",1700000000100))
            source.questionDao().insert(Question(second,"old","a",1700000000101))
            var changed=false
            val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val archive=BackupRepository(context,source).export(File(work,"export"),progress={ phase,count ->
                if(phase=="data.backupSnapshot" && count==1L && !changed) {
                    changed=true
                    val writer=Thread {
                        try { source.runInTransaction { source.openHelper.writableDatabase.execSQL("UPDATE questions SET text='new'") } }
                        catch(error:Throwable) { failure.set(error) }
                    }
                    writer.start(); writer.join(5000)
                    assertFalse("API35 read-only source capture must not block the WAL writer",writer.isAlive)
                }
            })
            failure.get()?.let { throw AssertionError("Concurrent fixture writer failed",it) }
            assertTrue(changed)
            val repo=BackupRepository(context,target)
            repo.merge(repo.prepareImportFiles(archive.files,File(work,"import")))
            assertEquals("old",target.questionDao().heavyOnce(first)!!.text)
            assertEquals("old",target.questionDao().heavyOnce(second)!!.text)
            assertEquals("new",source.questionDao().heavyOnce(first)!!.text)
        } finally { closeFixture(source); closeFixture(target); work.deleteRecursively() }
    }
    @Test fun exportResumesCompletedStagesWithoutMixingLaterSourceChanges() = runBlocking {
        val source=database(); val target=database(); val work=workspace()
        try {
            val id=QuestionIdentity.create(1700000000200)
            source.questionDao().insert(Question(id,"original snapshot","a",1700000000200))
            val repo=BackupRepository(context,source); val export=File(work,"export")
            try { repo.export(export,progress={ phase,_ -> if(phase=="data.backupNormalize") throw kotlinx.coroutines.CancellationException("capture complete") }); fail("Expected capture checkpoint interruption") }
            catch(_:kotlinx.coroutines.CancellationException) { }
            assertTrue(File(export,"capture.ready").isFile)
            val at=File(export,"capture.ready").readLines().first().toLong()
            source.openHelper.writableDatabase.execSQL("UPDATE questions SET text='later change'")
            try { repo.export(export,progress={ phase,_ -> if(phase=="data.backupValidate") throw kotlinx.coroutines.CancellationException("normalize complete") }); fail("Expected normalization checkpoint interruption") }
            catch(_:kotlinx.coroutines.CancellationException) { }
            assertTrue(File(export,"normalize.ready").isFile)
            assertFalse(File(export,"capture").exists())
            source.openHelper.writableDatabase.execSQL("UPDATE questions SET text='even later'")
            val archive=repo.export(export)
            assertEquals(at,archive.snapshotAt)
            val importer=BackupRepository(context,target)
            importer.merge(importer.prepareImportFiles(archive.files,File(work,"import")))
            assertEquals("original snapshot",target.questionDao().heavyOnce(id)!!.text)
        } finally { closeFixture(source); closeFixture(target); work.deleteRecursively() }
    }

    @Test fun danglingSavedHistoryLinkIsNormalizedFromSameSnapshot() = runBlocking {
        val source=database(); val target=database(); val work=workspace()
        try {
            val id=QuestionIdentity.create(1700000000250)
            source.questionDao().insertSolveSession(SolveSession(id=id,createdAt=1700000000250,savedQuestionId=id))
            val archive=BackupRepository(context,source).export(File(work,"export"))
            val repo=BackupRepository(context,target)
            repo.merge(repo.prepareImportFiles(archive.files,File(work,"import")))
            assertNull(target.questionDao().solveSessionOnce(id)!!.savedQuestionId)
            assertEquals(id,source.questionDao().solveSessionOnce(id)!!.savedQuestionId)
        } finally { closeFixture(source); closeFixture(target); work.deleteRecursively() }
    }
    @Test fun tamperedNormalizedCheckpointCannotProduceAnUnrestorableSuccess() = runBlocking {
        val source=database(); val work=workspace()
        try {
            val id=QuestionIdentity.create(1700000000300); val bytes=byteArrayOf(1,2,3)
            source.questionDao().insert(Question(id,"source intact","a",1700000000300,imageBytes=bytes))
            val repo=BackupRepository(context,source)
            for(kind in listOf("row","resource")) {
                val target=database()
                try {
                    val export=File(work,kind)
                    try { repo.export(export,progress={ phase,_ -> if(phase=="data.backupCompress") throw kotlinx.coroutines.CancellationException("validated checkpoint") }); fail("Expected compression interruption") }
                    catch(_:kotlinx.coroutines.CancellationException) { }
                    assertTrue(File(export,"validation.ready").isFile)
                    if(kind=="row") File(export,"tree/questions").listFiles()!!.single().appendBytes(byteArrayOf(9))
                    else File(export,"tree/resources").listFiles()!!.single().writeBytes(byteArrayOf(9,9,9))
                    val archive=repo.export(export)
                    val importer=BackupRepository(context,target)
                    importer.merge(importer.prepareImportFiles(archive.files,File(work,"import-$kind")))
                    assertEquals("source intact",target.questionDao().heavyOnce(id)!!.text)
                    assertArrayEquals(bytes,target.questionDao().heavyOnce(id)!!.imageBytes)
                } finally { closeFixture(target) }
            }
        } finally { closeFixture(source); work.deleteRecursively() }
    }
    @Test fun twoSourceTagsWithSameNameMapToOneLocalLink() = runBlocking {
        val source=database(); val target=database(); val work=workspace()
        try {
            val id=QuestionIdentity.create(1700000000400)
            source.questionDao().insert(Question(id,"fixture","a",1700000000400))
            val first=source.questionDao().insertTag(Tag(name="same name",createdAt=1))
            val second=source.questionDao().insertTag(Tag(name="same name",createdAt=2))
            source.questionDao().insertQuestionTagRaw(QuestionTag(id,first))
            source.questionDao().insertQuestionTagRaw(QuestionTag(id,second))
            val local=target.questionDao().insertTag(Tag(name="same name",createdAt=3))
            val archive=BackupRepository(context,source).export(File(work,"export"))
            val repo=BackupRepository(context,target)
            val result=repo.merge(repo.prepareImportFiles(archive.files,File(work,"import")))
            assertEquals(1,result.questions); assertEquals(0,result.tags)
            assertEquals(listOf(local),target.questionDao().tagIdsForQuestion(id))
            assertEquals(1,target.questionDao().getAllTagsOnce().size)
        } finally { closeFixture(source); closeFixture(target); work.deleteRecursively() }
    }
    @Test fun legacyV1AndV2AreExplicitlyRejectedRegardlessOfHeaderOrder() = runBlocking {
        val target=database(); val work=workspace()
        try {
            val repo=BackupRepository(context,target)
            for(version in listOf(1,2)) for(headerFirst in listOf(true,false)) {
                val header="\"format\":\"study-assistant-backup\",\"version\":$version"
                // Deliberately uses the numeric identity that v3's DTO cannot decode.
                val payload="\"questions\":[{\"id\":42,\"text\":\"old fixture\",\"answer\":\"a\",\"createdAt\":1}]"
                val input=File(work,"v$version-$headerFirst.json").apply { writeText(if(headerFirst) "{$header,$payload}" else "{$payload,$header}") }
                try { repo.prepareImportFiles(listOf(input),File(work,"import-$version-$headerFirst")); fail("Old format accepted") }
                catch(error:BackupValidationException) { assertEquals("data.backupVersionUnsupported",error.messageKey) }
            }
            assertEquals(0,target.questionDao().countQuestions())
        } finally { closeFixture(target); work.deleteRecursively() }
    }
    @Test fun malformedLegacyUtf8IsRejectedRatherThanReplaced() = runBlocking {
        val target=database(); val work=workspace()
        try {
            val input=File(work,"malformed.json")
            input.outputStream().use { out ->
                out.write("{\"format\":\"study-assistant-backup\",\"version\":3,\"opaque\":\"".toByteArray())
                out.write(byteArrayOf(0xc3.toByte(),0x28))
                out.write("\"}".toByteArray())
            }
            try { BackupRepository(context,target).prepareImportFiles(listOf(input),File(work,"import")); fail("Malformed UTF8 silently replaced") }
            catch(error:BackupValidationException) { assertEquals("data.backupInvalid",error.messageKey) }
            assertEquals(0,target.questionDao().countQuestions())
        } finally { closeFixture(target); work.deleteRecursively() }
    }

    @Test fun oversizedLegacyPrimitiveIsRejectedBeforeBigDecimalAllocation() = runBlocking {
        val target=database(); val work=workspace()
        try {
            val input=File(work,"oversized-number.json")
            input.outputStream().use { out ->
                out.write("{\"format\":\"study-assistant-backup\",\"version\":3,\"opaque\":".toByteArray())
                val chunk=ByteArray(8192) { '1'.code.toByte() }
                var left=BackupTree.residentLimit/2+1
                while(left>0) { val count=minOf(left,chunk.size); out.write(chunk,0,count); left-=count }
                out.write('}'.code)
            }
            try { BackupRepository(context,target).prepareImportFiles(listOf(input),File(work,"import")); fail("Oversized primitive accepted") }
            catch(error:BackupValidationException) { assertEquals("data.backupFieldTooLarge",error.messageKey) }
            assertEquals(0,target.questionDao().countQuestions())
        } finally { closeFixture(target); work.deleteRecursively() }
    }
    @Test fun emptyDatabaseRoundTripsWithoutEmptyZipDirectoryEntries() = runBlocking {
        val source=database(); val target=database(); val work=workspace()
        try {
            val archive=BackupRepository(context,source).export(File(work,"export"))
            val repo=BackupRepository(context,target)
            val prepared=repo.prepareImportFiles(archive.files,File(work,"import"))
            assertEquals(BackupStats(),prepared.stats)
            assertEquals(ImportResult(0,0,0,0,0,0),repo.merge(prepared))
        } finally { closeFixture(source); closeFixture(target); work.deleteRecursively() }
    }
}
