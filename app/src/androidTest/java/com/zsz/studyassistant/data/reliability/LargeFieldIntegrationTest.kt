package com.zsz.studyassistant.data

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LargeFieldIntegrationTest {
    @Test fun pagedSearchPreservesUnicodePriorityAndLongFieldMatches()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.zsz.studyassistant.integration")
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).build()
        try {
            val dao=db.questionDao()
            val questions=(0 until 70).map { index ->
                val text=when(index) {
                    0 -> "😀".repeat(2047)+"Ω" // Exactly 2048 SQLite characters, still a complete projection.
                    1 -> "x".repeat(8190)+"😀Ω question" // Match at the chunk boundary, after a surrogate pair.
                    2 -> "Ω short question" // Long answer must not win over the question.
                    3,4 -> "plain question $index"
                    else -> if(index%3==0) "Ω question $index" else "question $index"
                }
                val answer=when(index) {
                    2,4 -> "x".repeat(20000)+"Ω answer"
                    3 -> "x".repeat(2048)+"Ω answer"
                    else -> if(index%2==0) "Ω answer $index" else "answer $index"
                }
                Question(QuestionIdentity.create(10000L+index),text,answer,deleted=index==6)
            }
            questions.forEach { dao.insert(it) }
            val expected=questions.filterNot { it.deleted }.mapNotNull { question ->
                (searchSnippet(question.text,"ω",false) ?: searchSnippet(question.answer,"ω",true))
                    ?.let { question.id to it }
            }.toMap()
            assertEquals(expected,dao.matchingQuestionSnippets("ω"))
            assertTrue(dao.matchingQuestionSnippets("missing phrase").isEmpty())
            assertTrue(dao.matchingQuestionSnippets("  ").isEmpty())
            val first=dao.questionSearchPage("",2049,32)
            val second=dao.questionSearchPage(first.last().id,2049,32)
            assertEquals(32,first.size)
            assertEquals(32,second.size)
            assertTrue(first.all { a -> second.none { it.id==a.id } })
            assertTrue((first+second).all { it.text.codePointCount(0,it.text.length)<=2049 &&
                it.answer.codePointCount(0,it.answer.length)<=2049 })
            dao.softDelete(questions[1].id)
            assertNull(dao.matchingQuestionSnippet(questions[1].id,"ω"))
        } finally { db.close() }
    }

    @Test fun fullContentAndImagesSurviveWhileListsStayBounded()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.zsz.studyassistant.integration")
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).build()
        try {
            val dao=db.questionDao();val id=QuestionIdentity.create(10000)
            val text="汉字🔬".repeat(350000)+"Tail_Ω";val answer="solution".repeat(450000)
            val image=ByteArray(3*1024*1024) { (it%251).toByte() }
            dao.insert(Question(id,text,answer,10000,imageBytes=image))
            dao.insertSolveSession(SolveSession(id,10000,10000,"text",text,answer,imageBytes=image))
            dao.insertImages(listOf(QuestionImage(questionId=id,position=0,bytes=image)))
            dao.insertReview(Review(id,nextReviewAt=0))
            assertTrue(dao.getAll().first().single().text.length<=512)
            assertTrue(dao.getAll().first().single().answer.length<=512)
            assertTrue(dao.dueQuestions(Long.MAX_VALUE).first().single().answer.length<=512)
            assertTrue(dao.solveSessionsLight().first().single().answer.length<=512)
            val full=dao.heavyOnce(id)!!
            assertEquals(text,full.text);assertEquals(answer,full.answer);assertArrayEquals(image,full.imageBytes)
            val session=dao.solveSessionOnce(id)!!
            assertEquals(text,session.text);assertEquals(answer,session.answer);assertArrayEquals(image,session.imageBytes)
            assertArrayEquals(image,dao.imagesForQuestion(id).single().bytes)
            assertArrayEquals(image,dao.mainImagesFor(listOf(id)).single().bytes)
            assertEquals(setOf(id),dao.matchingQuestionIds("tail_ω"))
            assertEquals(setOf(id),dao.matchingQuestionIds("SOLUTION"))
            assertTrue(dao.matchingQuestionIds("missing phrase").isEmpty())
            assertEquals(text,dao.allLightOnce().single().text)
            assertEquals(answer,dao.allQuestionsOnce().single().answer)
        } finally { db.close() }
    }
    @Test fun nullAndEmptyLargeFieldsStayDistinct()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.zsz.studyassistant.integration")
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).build()
        try {
            val dao=db.questionDao();val a=QuestionIdentity.create(10000);val b=QuestionIdentity.create(20000)
            dao.insert(Question(a,"","",10000));dao.insert(Question(b,"","",20000,imageBytes=byteArrayOf(),conversationJson=""))
            assertNull(dao.heavyOnce(a)!!.imageBytes);assertNull(dao.heavyOnce(a)!!.conversationJson)
            assertArrayEquals(byteArrayOf(),dao.heavyOnce(b)!!.imageBytes);assertEquals("",dao.heavyOnce(b)!!.conversationJson)
        } finally { db.close() }
    }
}
