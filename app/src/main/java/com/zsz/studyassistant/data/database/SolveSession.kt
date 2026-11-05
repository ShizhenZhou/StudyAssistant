package com.zsz.studyassistant.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Original search record: its primary key is the same permanent ID used by the
 * notebook. Insert only on the initial photo/text submission; follow-ups update
 * that row and never change createdAt. Notebook/review opens do not create rows.
 * Keep this entity separate from Room Database/Migration classes for JVM tests.
 */
@Entity(tableName = "solve_sessions")
data class SolveSession(
    @PrimaryKey val id: QuestionId,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** 来源：camera（拍照）/ ask（图文提问）/ text（文字）/ grade（批改） */
    val source: String = "camera",
    /** 题干（空则回退"（图片题）"，与 saveToNotebook 同规则） */
    val text: String = "",
    val answer: String = "",
    /** 与 questions.conversationJson 同一格式（ChatItem[] 的 JSON） */
    val conversationJson: String? = null,
    /** 单图（拍照）；纯文字题为 null */
    val imageBytes: ByteArray? = null,
    /** 多图（base64 数组的 JSON）；顺带修"多图拍题存错题本丢原图" */
    val imagesJson: String? = null,
    /** 已存进错题本时指向 questions.id */
    val savedQuestionId: QuestionId? = null
)
