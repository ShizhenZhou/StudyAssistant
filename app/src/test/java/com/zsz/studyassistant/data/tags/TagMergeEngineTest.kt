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
    private class Store(start: TagInventory, val questions: Set<String>) : TagUndoStore {
        val tags = start.tags.associateBy { it.id }.toMutableMap()
        val links = start.links.toMutableSet()
        var journal: TagMergeUndo? = null
        var pending: TagMergeUndo? = null
        var sequence = start.tags.maxOf { it.id }
        var writes = 0
        var failOn: String? = null
        fun snapshot() = TagInventory(tags.values.toList(),links.toList()).normalized()
        fun write(method: String) { writes++; if (method == failOn) error("injected storage failure") }
        override fun read(slot: Int) = if (slot == 1) journal else pending
        override fun stage(record: TagMergeUndo) { write("stage"); pending = record }
        override fun resolve(promote: Boolean) {
            write("resolve")
            if (promote) journal = pending
            pending = null
        }
        override fun clear() { write("clear"); journal = null; pending = null }
        suspend fun apply(plan: TagMergePreview, now: Long = 500): TagMergeResult {
            TagUndoJournal.recover(this, snapshot())
            val result = transaction { TagMergeEngine.apply(dao, plan, now, ::stage) }
            TagUndoJournal.recover(this, snapshot())
            return result
        }
        suspend fun undo(): Int {
            val record = TagUndoJournal.recover(this, snapshot())
            val result = transaction { TagMergeEngine.undo(dao, record) }
            TagUndoJournal.recover(this, snapshot())
            return result
        }
        suspend fun <T> transaction(block: suspend () -> T): T {
            val oldTags=tags.toMap();val oldLinks=links.toSet();val oldSequence=sequence
            try { return block() } catch(e:Exception) {
                tags.clear();tags.putAll(oldTags);links.clear();links.addAll(oldLinks);sequence=oldSequence;throw e
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
        val result=s.apply(p)
        assertEquals(TagMergeResult(1,1,2),result)
        assertEquals(setOf(2L,3L,4L),s.tags.keys)
        assertEquals(setOf(QuestionTag(q1,2),QuestionTag(q2,2),QuestionTag(q1,3)),s.links)
        assertNotNull(s.journal);assertTrue(TagMergeEngine.canUndo(s.snapshot(),s.journal))
    }
    @Test fun undoAfterRestartUsesOnlyPersistedJournal() = runBlocking {
        val s=store();s.apply(TagMergeEngine.preview(base(),listOf(group())))
        val restarted=Store(s.snapshot(),s.questions);restarted.journal=s.journal
        assertEquals(2,restarted.undo())
        assertEquals(base(),restarted.snapshot());assertNull(restarted.journal)
    }
    @Test fun oneBatchCountsDistinctQuestionsAndUndoesAllGroupsTogether() = runBlocking {
        val s=store();val plan=TagMergeEngine.preview(base(),listOf(group(),TagMergeGroup(listOf(3,4),"积分")))
        assertEquals(2,plan.affectedQuestions)
        val result=s.apply(plan)
        assertEquals(TagMergeResult(2,2,2),result);assertEquals(setOf(2L,3L),s.tags.keys)
        s.undo();assertEquals(base(),s.snapshot())
    }
    @Test fun newTargetIsRemovedOnUndoWithoutLosingOriginalIdsOrTimes() = runBlocking {
        val s=store();s.apply(TagMergeEngine.preview(base(),listOf(group("极限"))))
        assertEquals("极限",s.tags.getValue(5).name)
        s.undo();assertEquals(base(),s.snapshot())
    }
    @Test fun laterUnrelatedEditsArePreservedDuringUndo() = runBlocking {
        val s=store();s.apply(TagMergeEngine.preview(base(),listOf(group())))
        s.tags[4]=s.tags.getValue(4).copy(name="不相关的新名称");s.links+=QuestionTag(q3,4)
        assertTrue(TagMergeEngine.canUndo(s.snapshot(),s.journal))
        s.undo()
        assertEquals("不相关的新名称",s.tags.getValue(4).name);assertTrue(QuestionTag(q3,4) in s.links)
        assertTrue(base().links.all { it in s.links })
    }
    @Test fun relatedNewLinksBlockUndoWithoutChangingAnything() = runBlocking {
        val s=store();s.apply(TagMergeEngine.preview(base(),listOf(group())))
        s.links+=QuestionTag(q3,2);val before=s.snapshot();val writes=s.writes
        assertFalse(TagMergeEngine.canUndo(before,s.journal))
        try { s.undo();fail() }
        catch(e:TagMergeException) { assertEquals("tagMerge.undoChanged",e.messageKey) }
        assertEquals(before,s.snapshot());assertEquals(writes,s.writes);assertNotNull(s.journal)
    }
    @Test fun recreatedSourceNameOutsideTheMergeBlocksUndo() = runBlocking {
        val s=store();s.apply(TagMergeEngine.preview(base(),listOf(group())))
        s.tags[99]=Tag(99,"求极限",600)
        assertFalse(TagMergeEngine.canUndo(s.snapshot(),s.journal))
    }
    @Test fun stalePreviewFailsBeforeFirstWrite() = runBlocking {
        val s=store();val plan=TagMergeEngine.preview(base(),listOf(group()))
        s.tags[1]=s.tags.getValue(1).copy(name="changed")
        try { s.apply(plan);fail() }
        catch(e:TagMergeException) { assertEquals("tagMerge.changed",e.messageKey) }
        assertEquals(0,s.writes);assertNull(s.journal)
    }
    @Test fun failureWritingJournalRollsBackTheWholeCallerTransaction() = runBlocking {
        val s=store();s.failOn="stage"
        try { s.apply(TagMergeEngine.preview(base(),listOf(group())));fail() }
        catch(_:IllegalStateException) { }
        assertEquals(base(),s.snapshot());assertNull(s.journal)
    }
    @Test fun secondMergeReplacesOnlyTheLatestUndoSlot() = runBlocking {
        val s=store();s.apply(TagMergeEngine.preview(base(),listOf(group())))
        val afterFirst=s.snapshot()
        s.apply(TagMergeEngine.preview(afterFirst,listOf(TagMergeGroup(listOf(2,3),"积分"))),600)
        s.undo();assertEquals(afterFirst,s.snapshot());assertNull(s.journal)
    }
    @Test fun malformedUndoRecordIsRejectedBeforeWrites() = runBlocking {
        val s=store();s.journal=TagMergeUndo(createdAt=1,beforeJson="{}",afterJson="{}")
        assertFalse(TagMergeEngine.canUndo(s.snapshot(),s.journal))
        try { TagMergeEngine.undo(s.dao,s.journal);fail() } catch(e:TagMergeException) { assertEquals("tagMerge.undoInvalid",e.messageKey) }
        assertEquals(0,s.writes)
    }
    @Test fun missingJournalTimestampCannotSilentlyInventARestoredTime() = runBlocking {
        val s=store();s.apply(TagMergeEngine.preview(base(),listOf(group())))
        s.journal=s.journal!!.copy(beforeJson="""{"version":1,"tags":[{"id":1,"name":"求极限"}],"links":[]}""")
        val before=s.snapshot();val writes=s.writes
        assertFalse(TagMergeEngine.canUndo(before,s.journal))
        try { TagMergeEngine.undo(s.dao,s.journal);fail() } catch(e:TagMergeException) { assertEquals("tagMerge.undoInvalid",e.messageKey) }
        assertEquals(before,s.snapshot());assertEquals(writes,s.writes)
    }
    @Test fun interruptedCommitKeepsPreviousUndoAndDiscardsCandidate() = runBlocking {
        val s=store();s.apply(TagMergeEngine.preview(base(),listOf(group())))
        val old=s.journal;val afterFirst=s.snapshot()
        try {
            s.transaction {
                TagMergeEngine.apply(s.dao,TagMergeEngine.preview(afterFirst,listOf(TagMergeGroup(listOf(2,3),"积分"))),600,s::stage)
                error("main commit failed")
            }
        } catch(_:IllegalStateException) { }
        assertNotNull(s.pending);assertEquals(old,s.journal);assertEquals(afterFirst,s.snapshot())
        assertEquals(old,TagUndoJournal.recover(s,s.snapshot()));assertNull(s.pending)
        s.undo();assertEquals(base(),s.snapshot())
    }
    @Test fun restartAfterMainCommitPromotesCandidate() = runBlocking {
        val s=store()
        s.transaction { TagMergeEngine.apply(s.dao,TagMergeEngine.preview(base(),listOf(group())),500,s::stage) }
        assertNull(s.journal);assertNotNull(s.pending)
        val restarted=Store(s.snapshot(),s.questions);restarted.pending=s.pending
        assertNotNull(TagUndoJournal.recover(restarted,restarted.snapshot()));assertNull(restarted.pending)
        restarted.undo();assertEquals(base(),restarted.snapshot())
    }
    @Test fun failedPromotionCanBeRetriedWithoutReapplyingMerge() = runBlocking {
        val s=store()
        s.transaction { TagMergeEngine.apply(s.dao,TagMergeEngine.preview(base(),listOf(group())),500,s::stage) }
        val merged=s.snapshot();s.failOn="resolve"
        try { TagUndoJournal.recover(s,merged);fail() } catch(_:IllegalStateException) { }
        assertEquals(merged,s.snapshot());assertNotNull(s.pending)
        s.failOn=null;assertNotNull(TagUndoJournal.recover(s,merged));s.undo();assertEquals(base(),s.snapshot())
    }
    @Test fun failedNewJournalWritePreservesPreviousUndo() = runBlocking {
        val s=store();s.apply(TagMergeEngine.preview(base(),listOf(group())))
        val old=s.journal;val merged=s.snapshot();s.failOn="stage"
        try { s.apply(TagMergeEngine.preview(merged,listOf(TagMergeGroup(listOf(2,3),"积分"))));fail() }
        catch(_:IllegalStateException) { }
        assertEquals(old,s.journal);assertEquals(merged,s.snapshot());assertNull(s.pending)
    }
    @Test fun failedUndoCommitStillAllowsUndo() = runBlocking {
        val s=store();s.apply(TagMergeEngine.preview(base(),listOf(group())))
        val merged=s.snapshot();val old=s.journal
        try { s.transaction { TagMergeEngine.undo(s.dao,old);error("undo commit failed") } }
        catch(_:IllegalStateException) { }
        assertEquals(merged,s.snapshot());assertEquals(old,TagUndoJournal.recover(s,s.snapshot()))
        s.undo();assertEquals(base(),s.snapshot())
    }
    @Test fun restartAfterUndoCommitCannotReplayOldUndo() = runBlocking {
        val s=store();s.apply(TagMergeEngine.preview(base(),listOf(group())))
        s.transaction { TagMergeEngine.undo(s.dao,s.journal) }
        assertNotNull(s.journal)
        assertNull(TagUndoJournal.recover(s,s.snapshot()));assertNull(s.journal)
        assertEquals(base(),s.snapshot())
    }
    @Test fun ambiguousPendingAfterOtherEditsDisablesUndoWithoutWritingBusinessData() = runBlocking {
        val s=store()
        s.transaction { TagMergeEngine.apply(s.dao,TagMergeEngine.preview(base(),listOf(group())),500,s::stage) }
        s.links+=QuestionTag(q3,2);val changed=s.snapshot()
        assertNull(TagUndoJournal.recover(s,changed));assertNull(s.pending);assertEquals(changed,s.snapshot())
    }
    @Test fun corruptPendingDoesNotTouchBusinessData() {
        val s=store();s.pending=TagMergeUndo(1,"broken","broken")
        assertNull(TagUndoJournal.recover(s,s.snapshot()));assertEquals(base(),s.snapshot())
    }
    @Test fun missingAuxiliaryFileOnlyRemovesUndoCapability() = runBlocking {
        val s=store();s.apply(TagMergeEngine.preview(base(),listOf(group())))
        val merged=s.snapshot();s.clear()
        assertNull(TagUndoJournal.recover(s,merged));assertEquals(merged,s.snapshot())
        try { s.undo();fail() } catch(e:TagMergeException) { assertEquals("tagMerge.undoMissing",e.messageKey) }
        assertEquals(merged,s.snapshot())
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
    @Test fun exportAuxiliarySchemaForDisposableSqliteVerification() {
        val root=generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.first { File(it,"settings.gradle.kts").exists() }
        File(root,"app/build/identity-work").mkdirs()
        File(root,"app/build/identity-work/tag-merge-schema.sql").writeText(TagMergeSchema.CREATE)
        assertTrue(TagMergeSchema.CREATE.startsWith("CREATE TABLE IF NOT EXISTS tag_merge_undo"))
    }
}
