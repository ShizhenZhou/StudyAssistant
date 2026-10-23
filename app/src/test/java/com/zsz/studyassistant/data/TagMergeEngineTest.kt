package com.zsz.studyassistant.data

import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TagMergeEngineTest {
    private val q1 = QuestionIdentity.create(10000)
    private val q2 = QuestionIdentity.create(20000)
    private val q3 = QuestionIdentity.create(30000)
    private fun base() = TagInventory(listOf(Tag(1,"求极限",100), Tag(2,"极限计算",200), Tag(3,"积分",300), Tag(4,"其他",400)),
        listOf(QuestionTag(q1,1), QuestionTag(q1,2), QuestionTag(q1,3), QuestionTag(q2,2))).normalized()
    private fun group(name: String = "极限计算") = TagMergeGroup(listOf(1,2),name,"同义词")
    private class Store(start: TagInventory, val questions: Set<String>) {
        val tags = start.tags.associateBy { it.id }.toMutableMap()
        val links = start.links.toMutableSet()
        var journal: TagMergeUndo? = null
        var sequence = start.tags.maxOf { it.id }
        var writes = 0
        var failOn: String? = null
        fun snapshot() = TagInventory(tags.values.toList(),links.toList()).normalized()
        fun write(method: String) { writes++; if (method == failOn) error("injected storage failure") }
        suspend fun <T> transaction(block: suspend () -> T): T {
            val oldTags=tags.toMap();val oldLinks=links.toSet();val oldJournal=journal;val oldSequence=sequence
            try { return block() } catch(e:Exception) {
                tags.clear();tags.putAll(oldTags);links.clear();links.addAll(oldLinks);journal=oldJournal;sequence=oldSequence;throw e
            }
        }
        val dao = Proxy.newProxyInstance(QuestionDao::class.java.classLoader,arrayOf(QuestionDao::class.java)) { _, method, args ->
            val a=args.orEmpty()
            @Suppress("UNCHECKED_CAST")
            when(method.name) {
                "getAllTagsOnce" -> tags.values.toList()
                "allQuestionTagsOnce" -> links.toList()
                "questionExists" -> (a[0] as String) in questions
                "insertTag" -> { write(method.name);val id=++sequence;tags[id]=(a[0] as Tag).copy(id=id);id }
                "insertTags" -> { write(method.name);val ts=a[0] as List<Tag>;ts.forEach { tags[it.id]=it;sequence=maxOf(sequence,it.id) };ts.map { it.id } }
                "insertQuestionTags" -> { write(method.name);val ls=a[0] as List<QuestionTag>;check(ls.all { it.tagId in tags });links.addAll(ls);Unit }
                "deleteLinksForTags" -> { write(method.name);val ids=(a[0] as List<Long>).toSet();links.removeAll { it.tagId in ids };Unit }
                "deleteTagsByIds" -> { write(method.name);(a[0] as List<Long>).forEach { tags.remove(it) };Unit }
                "putTagMergeUndo" -> { write(method.name);journal=a[0] as TagMergeUndo;Unit }
                "tagMergeUndoOnce" -> journal
                "clearTagMergeUndo" -> { write(method.name);journal=null;Unit }
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as QuestionDao
    }
    private fun store() = Store(base(),setOf(q1,q2,q3))
    private fun rejects(key: String, action: () -> Unit) {
        try { action();fail("Expected rejection") } catch(e:TagMergeException) { assertEquals(key,e.messageKey) }
    }
    @Test fun reuseTargetDeduplicatesLinksAndPreservesUnrelatedTags() = runBlocking {
        val s=store();val p=TagMergeEngine.preview(base(),listOf(group()))
        assertEquals(2,p.affectedQuestions)
        val result=s.transaction { TagMergeEngine.apply(s.dao,p,500) }
        assertEquals(TagMergeResult(1,1,2),result)
        assertEquals(setOf(2L,3L,4L),s.tags.keys)
        assertEquals(setOf(QuestionTag(q1,2),QuestionTag(q2,2),QuestionTag(q1,3)),s.links)
        assertNotNull(s.journal);assertTrue(TagMergeEngine.canUndo(s.snapshot(),s.journal))
    }
    @Test fun undoAfterRestartUsesOnlyPersistedJournal() = runBlocking {
        val s=store();s.transaction { TagMergeEngine.apply(s.dao,TagMergeEngine.preview(base(),listOf(group())),500) }
        val restarted=Store(s.snapshot(),s.questions);restarted.journal=s.journal
        assertEquals(2,restarted.transaction { TagMergeEngine.undo(restarted.dao) })
        assertEquals(base(),restarted.snapshot());assertNull(restarted.journal)
    }
    @Test fun oneBatchCountsDistinctQuestionsAndUndoesAllGroupsTogether() = runBlocking {
        val s=store();val plan=TagMergeEngine.preview(base(),listOf(group(),TagMergeGroup(listOf(3,4),"积分")))
        assertEquals(2,plan.affectedQuestions)
        val result=s.transaction { TagMergeEngine.apply(s.dao,plan,500) }
        assertEquals(TagMergeResult(2,2,2),result);assertEquals(setOf(2L,3L),s.tags.keys)
        s.transaction { TagMergeEngine.undo(s.dao) };assertEquals(base(),s.snapshot())
    }
    @Test fun newTargetIsRemovedOnUndoWithoutLosingOriginalIdsOrTimes() = runBlocking {
        val s=store();s.transaction { TagMergeEngine.apply(s.dao,TagMergeEngine.preview(base(),listOf(group("极限"))),500) }
        assertEquals("极限",s.tags.getValue(5).name)
        s.transaction { TagMergeEngine.undo(s.dao) };assertEquals(base(),s.snapshot())
    }
    @Test fun laterUnrelatedEditsArePreservedDuringUndo() = runBlocking {
        val s=store();s.transaction { TagMergeEngine.apply(s.dao,TagMergeEngine.preview(base(),listOf(group())),500) }
        s.tags[4]=s.tags.getValue(4).copy(name="不相关的新名称");s.links+=QuestionTag(q3,4)
        assertTrue(TagMergeEngine.canUndo(s.snapshot(),s.journal))
        s.transaction { TagMergeEngine.undo(s.dao) }
        assertEquals("不相关的新名称",s.tags.getValue(4).name);assertTrue(QuestionTag(q3,4) in s.links)
        assertTrue(base().links.all { it in s.links })
    }
    @Test fun relatedNewLinksBlockUndoWithoutChangingAnything() = runBlocking {
        val s=store();s.transaction { TagMergeEngine.apply(s.dao,TagMergeEngine.preview(base(),listOf(group())),500) }
        s.links+=QuestionTag(q3,2);val before=s.snapshot();val writes=s.writes
        assertFalse(TagMergeEngine.canUndo(before,s.journal))
        try { s.transaction { TagMergeEngine.undo(s.dao) };fail() }
        catch(e:TagMergeException) { assertEquals("tagMerge.undoChanged",e.messageKey) }
        assertEquals(before,s.snapshot());assertEquals(writes,s.writes);assertNotNull(s.journal)
    }
    @Test fun recreatedSourceNameOutsideTheMergeBlocksUndo() = runBlocking {
        val s=store();s.transaction { TagMergeEngine.apply(s.dao,TagMergeEngine.preview(base(),listOf(group())),500) }
        s.tags[99]=Tag(99,"求极限",600)
        assertFalse(TagMergeEngine.canUndo(s.snapshot(),s.journal))
    }
    @Test fun stalePreviewFailsBeforeFirstWrite() = runBlocking {
        val s=store();val plan=TagMergeEngine.preview(base(),listOf(group()))
        s.tags[1]=s.tags.getValue(1).copy(name="changed")
        try { TagMergeEngine.apply(s.dao,plan);fail() }
        catch(e:TagMergeException) { assertEquals("tagMerge.changed",e.messageKey) }
        assertEquals(0,s.writes);assertNull(s.journal)
    }
    @Test fun failureWritingJournalRollsBackTheWholeCallerTransaction() = runBlocking {
        val s=store();s.failOn="putTagMergeUndo"
        try { s.transaction { TagMergeEngine.apply(s.dao,TagMergeEngine.preview(base(),listOf(group())),500) };fail() }
        catch(_:IllegalStateException) { }
        assertEquals(base(),s.snapshot());assertNull(s.journal)
    }
    @Test fun secondMergeReplacesOnlyTheLatestUndoSlot() = runBlocking {
        val s=store();s.transaction { TagMergeEngine.apply(s.dao,TagMergeEngine.preview(base(),listOf(group())),500) }
        val afterFirst=s.snapshot()
        s.transaction { TagMergeEngine.apply(s.dao,TagMergeEngine.preview(afterFirst,listOf(TagMergeGroup(listOf(2,3),"积分"))),600) }
        s.transaction { TagMergeEngine.undo(s.dao) };assertEquals(afterFirst,s.snapshot());assertNull(s.journal)
    }
    @Test fun malformedUndoRecordIsRejectedBeforeWrites() = runBlocking {
        val s=store();s.journal=TagMergeUndo(createdAt=1,beforeJson="{}",afterJson="{}")
        assertFalse(TagMergeEngine.canUndo(s.snapshot(),s.journal))
        try { TagMergeEngine.undo(s.dao);fail() } catch(e:TagMergeException) { assertEquals("tagMerge.undoInvalid",e.messageKey) }
        assertEquals(0,s.writes)
    }
    @Test fun missingJournalTimestampCannotSilentlyInventARestoredTime() = runBlocking {
        val s=store();s.transaction { TagMergeEngine.apply(s.dao,TagMergeEngine.preview(base(),listOf(group())),500) }
        s.journal=s.journal!!.copy(beforeJson="""{"version":1,"tags":[{"id":1,"name":"求极限"}],"links":[]}""")
        val before=s.snapshot();val writes=s.writes
        assertFalse(TagMergeEngine.canUndo(before,s.journal))
        try { TagMergeEngine.undo(s.dao);fail() } catch(e:TagMergeException) { assertEquals("tagMerge.undoInvalid",e.messageKey) }
        assertEquals(before,s.snapshot());assertEquals(writes,s.writes)
    }
    @Test fun conflictingTargetsAndOverlappingGroupsRequireUserCorrection() {
        rejects("tagMerge.targetConflict") { TagMergeEngine.preview(base(),listOf(group("积分"))) }
        rejects("tagMerge.overlap") { TagMergeEngine.preview(base(),listOf(group(),TagMergeGroup(listOf(2,3),"积分"))) }
        rejects("tagMerge.overlap") { TagMergeEngine.preview(base(),listOf(group("new"),TagMergeGroup(listOf(3,4),"new"))) }
    }
    @Test fun missingDuplicateAndSingletonSourcesAreRejected() {
        for(ids in listOf(listOf(1L),listOf(1L,99L),listOf(1L,1L,2L),listOf(0L,1L))) {
            rejects("tagMerge.invalidGroup") { TagMergeEngine.preview(base(),listOf(TagMergeGroup(ids,"new"))) }
        }
        rejects("tagMerge.noSelection") { TagMergeEngine.preview(base(),emptyList()) }
    }
    @Test fun namesMustBeNonemptyAndHaveNoControlCharacters() {
        for(name in listOf(" ","a\nb","a".repeat(121))) rejects("tagMerge.invalidName") { TagMergeEngine.preview(base(),listOf(group(name))) }
    }
    @Test fun fingerprintDoesNotDependOnDatabaseRowOrder() {
        assertEquals(base().fingerprint(),base().copy(tags=base().tags.reversed(),links=base().links.reversed()).fingerprint())
    }
    @Test fun exportActualV11MigrationForDisposableSqliteVerification() {
        val root=generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.first { File(it,"settings.gradle.kts").exists() }
        File(root,"app/build/identity-work").mkdirs()
        File(root,"app/build/identity-work/tag-merge-schema.sql").writeText(TagMergeSchema.CREATE)
        assertTrue(TagMergeSchema.CREATE.startsWith("CREATE TABLE IF NOT EXISTS tag_merge_undo"))
    }
}
