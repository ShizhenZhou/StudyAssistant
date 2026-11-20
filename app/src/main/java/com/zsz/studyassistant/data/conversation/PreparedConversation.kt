package com.zsz.studyassistant

import com.zsz.studyassistant.data.*

/** Fully read inputs for a single non-suspending visible-session replacement. */
internal data class PreparedConversation(
    val id: QuestionId,
    val recordCreatedAt: Long,
    val source: String?,
    val historyAllowed: Boolean,
    val text: String,
    val messages: List<ChatItem>,
    val imageKeys: List<String>,
    val directImages: List<ByteArray>,
    val primaryImage: ByteArray?,
    val photo: Boolean,
    val ask: Boolean = false,
    val grade: Boolean = false,
    val questionFromPhoto: Boolean = false,
    val notebook: Question? = null,
    val notebookBaseline: SavePayload? = null,
    val createdAt: Long = recordCreatedAt,
    val tagIds: List<Long> = emptyList(),
    val suggestedCategory: String? = null,
    val suggestedTags: List<String> = emptyList(),
    val legacyImages: Boolean = false
)
