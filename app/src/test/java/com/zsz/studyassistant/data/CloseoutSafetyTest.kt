package com.zsz.studyassistant.data

import com.zsz.studyassistant.ChatItem
import java.time.*
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class CloseoutSafetyTest {
    @Test fun verdictNegationsAndMachineTagsDoNotInvertReviewGrades() {
        assertEquals(2,GradeVerdict.suggestion("正确，未发现错误"))
        assertEquals(0,GradeVerdict.suggestion("Incorrect. Fix the sign."))
        assertEquals(2,GradeVerdict.suggestion("Correct. No errors."))
        assertEquals(0,GradeVerdict.suggestion("不正确，但第二步正确"))
        assertEquals(2,GradeVerdict.suggestion("正しいです。誤りはありません"))
        assertEquals(2,GradeVerdict.suggestion("정답입니다"))
        assertNull(GradeVerdict.suggestion("推导中会讨论正确和错误的两种情况"))
        assertEquals(1,GradeVerdict.suggestion("文字正确\n<review_verdict>partial</review_verdict>"))
        assertNull(GradeVerdict.suggestion("<review_verdict>correct</review_verdict>\n<review_verdict>incorrect</review_verdict>"))
        assertNull(GradeVerdict.suggestion("<review_verdict>unknown</review_verdict>"))
    }
    @Test fun naturalDaysSurviveDstAndYesterdayIsNotRoundedToToday() {
        val spring=CalendarDay(LocalDate.of(2026,3,8),ZoneId.of("America/New_York"))
        val fall=CalendarDay(LocalDate.of(2026,11,1),spring.zone)
        assertEquals(23*3600000L,spring.end-spring.start+1)
        assertEquals(25*3600000L,fall.end-fall.start+1)
        assertEquals(spring.date.minusDays(1),spring.dateOf(spring.start-12*3600000L))
        assertEquals(2,ReviewPlanning.recordedStreak(listOf(spring.start,spring.start-12*3600000L),spring))
        assertEquals(1,ReviewPlanning.schedule(0,6).second)
        assertEquals(30,ReviewPlanning.schedule(2,6).second)
    }
    @Test fun selectedReviewQueueRetainsScopeAndOrder() {
        val a=QuestionIdentity.create(1000); val b=QuestionIdentity.create(2000); val c=QuestionIdentity.create(3000)
        assertEquals(listOf(b,c,a),ReviewPlanning.ordered(listOf(a,b,c),1))
        assertEquals(listOf(a),ReviewPlanning.ordered(listOf(a,a),50))
    }
    @Test fun streamCancellationBelongsToOneRequest() {
        var first=0;var second=0
        val a=StreamOwner(); val b=StreamOwner()
        a.attach { first++ }; b.attach { second++ }
        a.cancel();a.finish();a.cancel()
        assertEquals(1,first);assertEquals(0,second)
        b.cancel();assertEquals(1,second)
        val early=StreamOwner();early.cancel();early.attach { first++ };assertEquals(2,first)
    }
    @Test fun regenerationKeepsPreviousFollowupsAndOldAnswerUntilCommit() {
        val original=listOf(ChatItem(0,"question","q"),ChatItem(1,"assistant","a"),ChatItem(2,"user","followup"),ChatItem(3,"assistant","old answer"))
        assertEquals(listOf("q","a","followup"),regenerationBase(original).map { it.content })
        assertEquals("old answer",original.last().content)
    }
    @Test fun draftRoundtripPreservesRequestAndPartialWithoutPromotingItToAnswer() {
        val value=ConversationDraft(QuestionIdentity.create(10000),10000,UUID.randomUUID().toString(),"fixture","followup","text","q",false,true,false,true,null,emptyList(),listOf(ChatItem(0,"assistant","old")),emptyList(),raw="partial")
        val restored=ConversationDraftCodec.decode(ConversationDraftCodec.encode(value))
        assertEquals(value,restored);assertFalse(restored.complete);assertNull(restored.completedChats)
        try { ConversationDraftCodec.encode(value.copy(notebookId=QuestionIdentity.create(10000)));fail() } catch (_:IllegalArgumentException) { }
    }
    @Test fun chunkedConversationPreservesUnicodeAndNulls() = runBlocking {
        val text="汉字🔬".repeat(40000)
        assertEquals(text,readChunkedText(text.length) { start,size -> text.substring(start-1,minOf(text.length,start-1+size)) })
        assertNull(readChunkedText(null) { _,_->error("must not read") })
        assertEquals("",readChunkedText(0) { _,_->error("must not read") })
    }
    @Test fun oversizedImportFailsBeforeUnboundedAllocation() {
        BackupLimits.checkEncoded("A汉🔬",8)
        try { BackupLimits.checkEncoded("A汉🔬",7);fail() } catch (_:BackupValidationException) { }

        LimitedBackupInput(ByteArrayInputStream(ByteArray(8)),8).use { assertEquals(8,it.readBytes().size) }
        try { LimitedBackupInput(ByteArrayInputStream(ByteArray(9)),8).use { it.readBytes() };fail() } catch (_:BackupValidationException) { }
        try { BackupLimits.checkEstimate(BackupLimits.maxBytes);fail() } catch (_:BackupValidationException) { }
    }
    @Test fun deletionGroupsAndUndoPreventsEveryDestructiveWrite() = runBlocking {
        val scope=CoroutineScope(coroutineContext+Job());val wake=Channel<Unit>(Channel.UNLIMITED);val commits=mutableListOf<DeleteBatch>();var clock=0L
        try {
            val queue=DeferredDeletion(scope,{commits+=it},{fail("unexpected failure")},now={clock},wait={wake.receive()})
            val a=QuestionIdentity.create(1000);val b=QuestionIdentity.create(2000)
            queue.stage(questions=setOf(a));yield();val stale=queue.state.value.pending!!.token
            queue.stage(questions=setOf(b));yield();val current=queue.state.value.pending!!
            assertEquals(setOf(a,b),current.questions);assertTrue(commits.isEmpty());assertFalse(queue.undo(stale))
            assertTrue(queue.undo(current.token));wake.send(Unit);yield();assertTrue(commits.isEmpty());assertFalse(queue.state.value.active)
        } finally { scope.cancel() }
    }
    @Test fun deletionCommitsOnlyAfterWindowAndRevealsFailures() = runBlocking {
        val scope=CoroutineScope(coroutineContext+Job());val wake=Channel<Unit>(Channel.UNLIMITED);var clock=0L;var failed=0;var attempts=0
        try {
            val queue=DeferredDeletion(scope,{attempts++;error("synthetic disk failure")},{failed++},now={clock},wait={wake.receive()})
            queue.stage(histories=setOf(QuestionIdentity.create(1000)));yield();val token=queue.state.value.pending!!.token
            clock=3001;assertFalse(queue.undo(token));assertEquals(0,attempts)
            wake.send(Unit);repeat(5){yield()}
            assertEquals(1,attempts);assertEquals(1,failed);assertFalse(queue.state.value.active)
        } finally { scope.cancel() }
    }
}
