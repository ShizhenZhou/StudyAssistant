package com.zsz.studyassistant.data

import java.io.File
import kotlin.random.Random
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class QuestionIdentityTest {
    @Test fun sameTimestampStillHasUniqueIds() {
        val ids = List(1000) { QuestionIdentity.create(123456) }
        assertEquals(1000,ids.toSet().size)
        assertTrue(ids.all { QuestionIdentity.valid(it) && QuestionIdentity.time(it)==123456L })
    }
    @Test fun malformedOrOldNumericIdsAreRejected() {
        listOf("123", "", "invalid-123", "00000000-0000-4000-8000-000000000000-0").forEach {
            assertFalse(QuestionIdentity.valid(it))
        }
    }
    @Test fun validTimesArePreserved() {
        val rows=listOf(LegacyQuestionTime(4,8000),LegacyQuestionTime(1,5000))
        assertEquals(mapOf(1L to 5000L,4L to 8000L),inferQuestionTimes(rows,9999))
    }
    @Test fun missingInteriorTimesAreRandomBetweenNeighborsAndOrdered() {
        val rows=(1L..5L).map { LegacyQuestionTime(it,when(it){1L->10000;5L->20000;else->0}) }
        val a=inferQuestionTimes(rows,30000,Random(1))
        val b=inferQuestionTimes(rows,30000,Random(2))
        assertNotEquals(a,b)
        val middle=(2L..4L).map { a.getValue(it) }
        assertTrue(middle.all { it in 10001..19999 })
        assertEquals(middle.sorted(),middle)
    }
    @Test fun missingPrefixAndSuffixUseOneSecondSteps() {
        val rows=(1L..5L).map { LegacyQuestionTime(it,if(it==3L)10000 else 0) }
        assertEquals(listOf(8000L,9000L,10000L,11000L,12000L),
            inferQuestionTimes(rows,99999).toSortedMap().values.toList())
    }
    @Test fun reversedNeighborTimesStillBoundTheInferredTime() {
        val out=inferQuestionTimes(listOf(LegacyQuestionTime(1,20000),LegacyQuestionTime(2,0),LegacyQuestionTime(3,10000)),99999,Random(1))
        assertTrue(out.getValue(2) in 10001..19999)
        assertEquals(20000L,out[1]);assertEquals(10000L,out[3])
    }
    @Test fun fullyUnknownUsesUpgradeTime() {
        assertEquals(mapOf(1L to 9000L,2L to 9000L),inferQuestionTimes(listOf(LegacyQuestionTime(1,0),LegacyQuestionTime(2,-1)),9000))
    }
    @Test fun equalOrAdjacentTimesDoNotCrashOrInventFutureTimes() {
        for (right in listOf(10000L,10001L)) {
            val out=inferQuestionTimes(listOf(LegacyQuestionTime(1,10000),LegacyQuestionTime(2,0),LegacyQuestionTime(3,right)),99999)
            assertTrue(out.getValue(2) in 10000..right)
        }
    }
    @Test fun duplicateLegacyKeysCannotProduceAnAmbiguousMapping() {
        try { inferQuestionTimes(listOf(LegacyQuestionTime(1,1000),LegacyQuestionTime(1,2000)),9999); fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun exportActualMigrationPlanForIsolatedSQLiteVerification() {
        val rows=listOf(LegacyQuestionTime(1,10000),LegacyQuestionTime(2,0),LegacyQuestionTime(3,20000))
        val statements=IdentityMigrationPlan.statements(rows,listOf(99),listOf(LegacyQuestionTime(7,0)),30000)
        val root=generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it,"settings.gradle.kts").exists() }
        val dir=File(root,"app/build/identity-work").apply { mkdirs() }
        File(dir,"migration-plan.json").writeText(Json.encodeToString(statements))
        assertTrue(statements.any { it.contains("CHECK(ok=1)") })
    }
}
