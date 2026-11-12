package com.zsz.studyassistant.data

import androidx.room3.withWriteTransaction
import androidx.room3.support.getSupportWrapper
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Keep the bounded legacy JSON reader without a synchronous Room DAO query. */
internal suspend fun AppDatabase.previewImages(qid: QuestionId): List<ByteArray> = withWriteTransaction {
    val dao = questionDao()
    val stored = dao.imagesForQuestion(qid)
    if (stored.isNotEmpty()) return@withWriteTransaction stored.map { it.bytes }
    val primary = listOfNotNull(dao.questionImageOnce(qid))
    val operation = currentCoroutineContext()
    val legacy = legacyPreviewImages { offset, count ->
        operation.ensureActive()
        getSupportWrapper().query("SELECT substr(conversationJson,?,?) FROM questions WHERE id=?",
            arrayOf<Any>(offset, count, qid)).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }
    primary + additionalQuestionImages(primary, legacy)
}
