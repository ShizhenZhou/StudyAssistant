package com.zsz.studyassistant.data

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupImporterTest {
    private class Store {
        val questions=linkedMapOf<String,Question>()
        val sessions=linkedMapOf<String,SolveSession>()
        val categories=mutableListOf<Category>()
        val tags=mutableListOf<Tag>()
        val images=mutableListOf<QuestionImage>()
        val links=mutableListOf<QuestionTag>()
        val reviews=mutableListOf<Review>()
        val dao=Proxy.newProxyInstance(QuestionDao::class.java.classLoader,arrayOf(QuestionDao::class.java)) { _, method, args ->
            val a=args.orEmpty()
            when(method.name) {
                "allCategoriesOnce" -> categories.toList()
                "getAllTagsOnce" -> tags.toList()
                "insertCategory" -> { val id=categories.size+100L;categories+=(a[0] as Category).copy(id=id);id }
                "insertTag" -> { val id=tags.size+200L;tags+=(a[0] as Tag).copy(id=id);id }
                "questionExists" -> questions.containsKey(a[0])
                "insert" -> { val q=a[0] as Question;check(q.id !in questions);questions[q.id]=q;questions.size.toLong() }
                "insertImages" -> { @Suppress("UNCHECKED_CAST") val rows=a[0] as List<QuestionImage>;images+=rows;rows.indices.map { it.toLong() } }
                "insertQuestionTags" -> { @Suppress("UNCHECKED_CAST") val rows=a[0] as List<QuestionTag>;links+=rows;Unit }
                "insertReviews" -> { @Suppress("UNCHECKED_CAST") val rows=a[0] as List<Review>;reviews+=rows;Unit }
                "solveSessionOnce" -> sessions[a[0]]
                "insertSolveSession" -> { val s=a[0] as SolveSession;check(s.id !in sessions);sessions[s.id]=s;sessions.size.toLong() }
                "linkExistingSession" -> { val id=a[0] as String;if(id in questions) sessions[id]?.let { sessions[id]=it.copy(savedQuestionId=id) };Unit }
                "toString" -> "IsolatedBackupStore"
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as QuestionDao
    }
    private val id=QuestionIdentity.create(10000)
    private fun backup()=BackupFile(
        categories=listOf(BackupCategory(1,"Math")),tags=listOf(BackupTag(2,"Tag")),
        questions=listOf(BackupQuestion(id,"same text","original",10000,"AQI=",categoryId=1,images=listOf("AQI="))),
        questionTags=listOf(BackupQuestionTag(id,2)),reviews=listOf(BackupReview(id,3,30000,20000,7)),
        solveSessions=listOf(BackupSolveSession(id,10000,40000,text="same text",savedQuestionId=id)))

    @Test fun repeatedImportIsIdempotentAndReferencesStayLinked() = runBlocking {
        val s=Store();val b=backup()
        val first=BackupImporter.merge(s.dao,b)
        val second=BackupImporter.merge(s.dao,b)
        assertEquals(1,first.questions);assertEquals(1,first.sessions)
        assertEquals(0,second.questions);assertEquals(0,second.sessions);assertEquals(1,second.skipped)
        assertEquals(1,s.questions.size);assertEquals(1,s.sessions.size)
        assertEquals(id,s.sessions.getValue(id).savedQuestionId)
        assertEquals(id,s.links.single().questionId)
        assertEquals(id,s.reviews.single().questionId)
        assertEquals(7,s.reviews.single().reviewCount)
        assertArrayEquals(byteArrayOf(1,2),s.images.single().bytes)
        assertEquals(10000L,s.sessions.getValue(id).createdAt)
    }
    @Test fun sameTextDifferentIdentityIsNotMerged() = runBlocking {
        val s=Store();val b=backup();BackupImporter.merge(s.dao,b)
        val q=b.questions.single().copy(id=QuestionIdentity.create(10000))
        BackupImporter.merge(s.dao,b.copy(questions=listOf(q),questionTags=emptyList(),reviews=emptyList(),solveSessions=emptyList()))
        assertEquals(2,s.questions.size)
    }
    @Test fun existingNotebookWinsButImportedHistoryLinksToIt() = runBlocking {
        val s=Store();s.questions[id]=Question(id,"local","new answer",10000)
        BackupImporter.merge(s.dao,backup())
        assertEquals("new answer",s.questions.getValue(id).answer)
        assertEquals(id,s.sessions.getValue(id).savedQuestionId)
        assertTrue(s.reviews.isEmpty())
    }
    @Test fun existingHistoryGainsRestoredNotebookLinkWithoutBeingOverwritten() = runBlocking {
        val s=Store();s.sessions[id]=SolveSession(id,createdAt=10000,updatedAt=99999,text="local history")
        BackupImporter.merge(s.dao,backup())
        assertEquals(id,s.sessions.getValue(id).savedQuestionId)
        assertEquals("local history",s.sessions.getValue(id).text)
        assertEquals(10000L,s.sessions.getValue(id).createdAt)
    }
    @Test fun invalidBackupFailsBeforeAnyWrite() = runBlocking {
        val s=Store();val b=backup();val bad=b.copy(questions=listOf(b.questions.single().copy(images=listOf("!"),imageBase64="!")))
        try { BackupImporter.merge(s.dao,bad);fail() } catch (_:BackupValidationException) { }
        assertTrue(s.questions.isEmpty() && s.categories.isEmpty() && s.tags.isEmpty() && s.sessions.isEmpty())
    }
}
