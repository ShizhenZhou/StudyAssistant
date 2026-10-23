package com.zsz.studyassistant.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BackupValidationTest {
    private val id=QuestionIdentity.create(10000)
    private fun good()=BackupFile(
        categories=listOf(BackupCategory(1,"Math")),tags=listOf(BackupTag(2,"Tag")),
        questions=listOf(BackupQuestion(id,"problem","answer",10000,"AQI=",categoryId=1,images=listOf("AQI="))),
        questionTags=listOf(BackupQuestionTag(id,2)),reviews=listOf(BackupReview(id,2,30000,20000,5)),
        solveSessions=listOf(BackupSolveSession(id,10000,40000,text="problem",savedQuestionId=id)))
    private fun rejects(file: BackupFile) {
        try { BackupValidation.validate(file); fail("Invalid backup accepted") }
        catch (_: BackupValidationException) { }
    }
    @Test fun roundTripPreservesIdentityImagesProgressAndLink() {
        val source=good()
        val restored=BackupValidation.decode(Json { encodeDefaults=true }.encodeToString(source))
        assertEquals(source,restored)
        assertEquals(restored.questions.single().id,restored.solveSessions.single().savedQuestionId)
    }
    @Test fun oldVersionsAreRejectedBeforeDecodingNumericIds() {
        for (version in listOf(1,2,4)) {
            try { BackupValidation.decode("""{"format":"study-assistant-backup","version":$version,"questions":[{"id":123}]}""");fail() }
            catch(e:BackupValidationException){assertEquals("data.backupVersionUnsupported",e.messageKey)}
        }
    }
    @Test fun duplicateIdsAreRejected() { val b=good();rejects(b.copy(questions=b.questions+b.questions)) }
    @Test fun sameTextWithDifferentIdentitiesIsAllowed() {
        val b=good(); val q=b.questions.single()
        BackupValidation.validate(b.copy(questions=b.questions+q.copy(id=QuestionIdentity.create(q.createdAt))))
    }
    @Test fun mismatchingOriginalTimeIsRejected() {
        val b=good();rejects(b.copy(questions=listOf(b.questions.single().copy(createdAt=999))))
    }
    @Test fun invalidImagesAbortInsteadOfBecomingEmpty() {
        val b=good();rejects(b.copy(questions=listOf(b.questions.single().copy(images=listOf("bad!"),imageBase64="bad!"))))
    }
    @Test fun orphanTagAndReviewReferencesAreRejected() {
        val b=good();rejects(b.copy(questionTags=listOf(BackupQuestionTag(id,999))))
        rejects(b.copy(reviews=listOf(BackupReview(QuestionIdentity.create(),0,0,0,0))))
    }
    @Test fun historyCannotLinkToAnotherProblem() {
        val b=good();rejects(b.copy(solveSessions=listOf(b.solveSessions.single().copy(savedQuestionId=QuestionIdentity.create()))))
    }
    @Test fun duplicateHistoriesAreRejected() { val b=good();rejects(b.copy(solveSessions=b.solveSessions+b.solveSessions)) }
    @Test fun malformedConversationIsRejectedBeforeWrites() {
        val b=good();rejects(b.copy(questions=listOf(b.questions.single().copy(conversationJson="[broken"))))
    }
}
