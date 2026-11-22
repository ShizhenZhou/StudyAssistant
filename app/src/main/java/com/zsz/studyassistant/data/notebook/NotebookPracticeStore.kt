package com.zsz.studyassistant.data

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal enum class PracticeRating { FAMILIAR, VAGUE, FORGOT, SKIPPED }

@Serializable
internal data class PracticeItem(val id: QuestionId, val rating: PracticeRating? = null)

@Serializable
internal data class NotebookPracticeRound(
    val roundId: String,
    val items: List<PracticeItem>,
    val version: Int = 1
) {
    companion object {
        fun start(ids: List<QuestionId>) = NotebookPracticeRound(
            UUID.randomUUID().toString(), ids.distinct().map { PracticeItem(it) }
        )
    }

    // The first pending permanent ID is the cursor. Removing a deleted question
    // cannot transfer its progress or rating to the next question.
    val cursor: Int get() = items.indexOfFirst { it.rating == null }

    fun advance(id: QuestionId, rating: PracticeRating?): NotebookPracticeRound {
        val index = cursor
        check(items.getOrNull(index)?.id == id)
        val updated = items.toMutableList()
        if (rating == null) updated.removeAt(index) else updated[index] = PracticeItem(id, rating)
        return copy(items = updated)
    }

    fun retryIds(includeSkipped: Boolean): List<QuestionId> = items.filter {
        it.rating == PracticeRating.VAGUE || it.rating == PracticeRating.FORGOT ||
            (includeSkipped && it.rating == PracticeRating.SKIPPED)
    }.map { it.id }

    fun validate() {
        require(version == 1 && roundId.isNotBlank())
        val ids = HashSet<QuestionId>()
        var pending = false
        items.forEach {
            require(QuestionIdentity.valid(it.id) && ids.add(it.id))
            if (it.rating == null) pending = true else require(!pending)
        }
    }
}

internal sealed interface PracticeFileState {
    data object Loading : PracticeFileState
    data object ReadFailed : PracticeFileState
    class Ready(val round: NotebookPracticeRound?) : PracticeFileState
}

/** A single process-wide owner also serializes late screen callbacks with clear-all. */
internal class NotebookPracticeStore private constructor(context: Context) {
    companion object {
        private var instance: NotebookPracticeStore? = null
        @Synchronized fun get(context: Context): NotebookPracticeStore =
            instance ?: NotebookPracticeStore(context.applicationContext).also { instance = it }
    }

    private val file = PrivateJsonFile(File(context.noBackupFilesDir, "notebook-practice-v1.json"), 32 * 1024 * 1024)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val mutableState = MutableStateFlow<PracticeFileState>(PracticeFileState.Loading)
    val state = mutableState.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        synchronized(this@NotebookPracticeStore) {
            if (mutableState.value !is PracticeFileState.Ready) {
                mutableState.value = try {
                    val round = file.read()?.let { json.decodeFromString<NotebookPracticeRound>(it) }
                    round?.validate()
                    PracticeFileState.Ready(round)
                } catch (_: Exception) { PracticeFileState.ReadFailed }
            }
        }
    }

    suspend fun save(expected: PracticeFileState.Ready, next: NotebookPracticeRound?) =
        withContext(NonCancellable + Dispatchers.IO) {
            synchronized(this@NotebookPracticeStore) {
                if (mutableState.value !== expected) return@synchronized false
                if (next == null) file.clear() else {
                    next.validate()
                    file.write(json.encodeToString(next))
                }
                mutableState.value = PracticeFileState.Ready(next)
                true
            }
        }

    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(this@NotebookPracticeStore) {
            file.clear()
            mutableState.value = PracticeFileState.Ready(null)
        }
    }
}
