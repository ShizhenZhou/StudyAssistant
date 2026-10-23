package com.zsz.studyassistant.data

import com.zsz.studyassistant.ChatItem
import java.util.Base64
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.InputStream

class BackupValidationException(val messageKey: String) : IllegalArgumentException(messageKey)

/** All checks run before any database writes. No best-effort image decoding. */
object BackupValidation {
    private val json = Json { ignoreUnknownKeys = true }
    @Serializable
    private data class Header(val format: String? = null, val version: Int? = null)

    private fun header(value: Header) {
        if (value.format != BackupFile.FORMAT || value.version == null) invalid()
        if (value.version != BackupFile.VERSION) throw BackupValidationException("data.backupVersionUnsupported")
    }

    fun decode(text: String): BackupFile {
        try {
            header(json.decodeFromString<Header>(text))
            return json.decodeFromString<BackupFile>(text).also(::validate)
        } catch (e: BackupValidationException) {
            throw e
        } catch (_: Exception) {
            invalid()
        }
    }

    /** Reopen twice to inspect the header without retaining an image-heavy JSON tree or raw text. */
    @OptIn(ExperimentalSerializationApi::class)
    fun decodeStream(open: () -> InputStream?): BackupFile {
        try {
            val metadata = (open() ?: throw java.io.IOException("Cannot open backup")).use { json.decodeFromStream<Header>(it) }
            header(metadata)
            return (open() ?: throw java.io.IOException("Cannot reopen backup")).use { json.decodeFromStream<BackupFile>(it) }.also(::validate)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            throw e
        } catch (e: SecurityException) {
            throw e
        } catch (e: BackupValidationException) {
            throw e
        } catch (_: Exception) {
            invalid()
        }
    }

    private fun image(key: String): ByteArray =
        try { Base64.getDecoder().decode(key).also { bytes -> if (bytes.isEmpty()) invalid() } }
        catch (_: IllegalArgumentException) { invalid() }

    fun images(keys: List<String>): List<ByteArray> = keys.map(::image)

    private fun conversation(text: String?) {
        if (text == null) return
        try {
            json.decodeFromString<List<ChatItem>>(text).forEach { item -> item.images.orEmpty().forEach { image(it) } }
        } catch (_: Exception) { invalid() }
    }

    fun validate(file: BackupFile) {
        fun ensure(ok: Boolean) { if (!ok) invalid() }
        ensure(file.format == BackupFile.FORMAT && file.version == BackupFile.VERSION)
        val questions = file.questions.associateBy { it.id }
        val categories = file.categories.map { it.id }.toSet()
        val tags = file.tags.map { it.id }.toSet()
        ensure(questions.size == file.questions.size)
        ensure(categories.size == file.categories.size && categories.all { it > 0 })
        ensure(tags.size == file.tags.size && tags.all { it > 0 })
        ensure(file.solveSessions.map { it.id }.distinct().size == file.solveSessions.size)
        file.questions.forEach { q ->
            ensure(QuestionIdentity.valid(q.id) && QuestionIdentity.time(q.id) == q.createdAt)
            ensure(q.categoryId == null || q.categoryId in categories)
            ensure(q.images.size <= MAX_QUESTION_IMAGES)
            q.images.forEach { image(it) }
            ensure(q.imageBase64 == q.images.firstOrNull())
            conversation(q.conversationJson)
        }
        ensure(file.questionTags.distinct().size == file.questionTags.size)
        file.questionTags.forEach { ensure(it.questionId in questions && it.tagId in tags) }
        ensure(file.reviews.map { it.questionId }.distinct().size == file.reviews.size)
        file.reviews.forEach {
            ensure(it.questionId in questions && it.intervalStep in 0..6 && it.reviewCount >= 0)
        }
        file.solveSessions.forEach { s ->
            ensure(QuestionIdentity.valid(s.id) && QuestionIdentity.time(s.id) == s.createdAt)
            ensure(s.savedQuestionId == null || (s.savedQuestionId == s.id && s.id in questions))
            ensure(s.images.size <= MAX_QUESTION_IMAGES && s.imageBase64 == s.images.firstOrNull())
            s.images.forEach { image(it) }
            conversation(s.conversationJson)
        }
    }

    private fun invalid(): Nothing = throw BackupValidationException("data.backupInvalid")
}
