package com.zsz.studyassistant.data

import android.content.Context
import androidx.room3.withReadTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DailyReviewRow(val id: QuestionId, val deleted: Boolean, val nextReviewAt: Long?, val lastReviewedAt: Long?)

internal data class DailyReviewPlan(
    val date: String,
    val planned: Set<QuestionId> = emptySet(),
    val outsidePlan: Set<QuestionId> = emptySet(),
    val rated: Set<QuestionId> = emptySet()
) {
    val completed = rated.count { it in planned }

    fun hasClassified(id: QuestionId) = id in planned || id in outsidePlan

    fun reconcile(
        rows: List<DailyReviewRow>, day: CalendarDay, ratedToday: List<QuestionId> = emptyList()
    ): DailyReviewPlan {
        val newPlanned = mutableSetOf<QuestionId>()
        val newOutside = mutableSetOf<QuestionId>()
        val newRated = ratedToday.filterNotTo(mutableSetOf()) { it in rated }
        val start = day.start
        val end = day.end
        for (row in rows) {
            val reviewedToday = row.lastReviewedAt != null && row.lastReviewedAt in start..end
            if (reviewedToday && !row.deleted && row.id !in rated) newRated.add(row.id)
            if (hasClassified(row.id)) continue
            // Classify each permanent notebook ID once, including old, future and hidden rows.
            // createdAt belongs to the original solve and may predate saving to the notebook.
            if (!row.deleted &&
                (reviewedToday || (row.nextReviewAt != null && row.nextReviewAt <= end))) {
                newPlanned.add(row.id)
            } else {
                newOutside.add(row.id)
            }
        }
        if (newPlanned.isEmpty() && newOutside.isEmpty() && newRated.isEmpty()) return this
        return copy(planned = planned.include(newPlanned), outsidePlan = outsidePlan.include(newOutside),
            rated = rated.include(newRated))
    }

    private fun Set<QuestionId>.include(additions: Set<QuestionId>): Set<QuestionId> = when {
        additions.isEmpty() -> this
        isEmpty() -> additions
        else -> this + additions
    }
}

/** The baseline is local to this installation. Room remains authoritative for a committed rating. */
internal class DailyReviewStore(context: Context) {
    private val prefs = context.getSharedPreferences("daily_review_v1", Context.MODE_PRIVATE)
    private val database = AppDatabase.get(context)
    private val dao = database.questionDao()
    private val lock = Mutex()
    val state = MutableStateFlow<DailyReviewPlan?>(null)

    private fun read(day: CalendarDay): DailyReviewPlan? {
        val date = day.date.toString()
        state.value?.takeIf { it.date == date }?.let { return it }
        if (prefs.getString("date", null) != date) return null
        return DailyReviewPlan(date, prefs.getStringSet("planned", null).orEmpty(),
            prefs.getStringSet("outside", null).orEmpty(), prefs.getStringSet("rated", null).orEmpty())
    }

    private fun save(previous: DailyReviewPlan?, plan: DailyReviewPlan) {
        if (previous != plan) {
            val edit = prefs.edit().putString("date", plan.date)
            if (previous?.planned != plan.planned) edit.putStringSet("planned", plan.planned)
            if (previous?.outsidePlan != plan.outsidePlan) edit.putStringSet("outside", plan.outsidePlan)
            if (previous?.rated != plan.rated) edit.putStringSet("rated", plan.rated)
            check(edit.commit()) { "Could not save daily review progress" }
        }
        state.value = plan
    }

    private suspend fun reconcile(previous: DailyReviewPlan?, day: CalendarDay): DailyReviewPlan {
        if (previous == null) return DailyReviewPlan(day.date.toString()).reconcile(dao.dailyReviewRows(), day)
        return database.withReadTransaction {
            // The primary-key index includes hidden rows and restored IDs from older backups.
            // Read schedule/deletion state only for IDs the current plan has yet to classify.
            val newIds = dao.dailyReviewQuestionIds().filterNot(previous::hasClassified)
            val newRows = buildList {
                for (ids in newIds.chunked(500)) addAll(dao.dailyReviewRowsByIds(ids))
            }
            previous.reconcile(newRows, day, dao.dailyReviewRatedIds(day.start, day.end))
        }
    }

    suspend fun refresh(onlyIfStarted: Boolean = false) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val day = CalendarDay.now()
                val previous = read(day)
                if (previous == null && onlyIfStarted) return@withLock null
                val plan = reconcile(previous, day)
                save(previous, plan)
                plan
            }
        }

    suspend fun rate(id: QuestionId,
        commit: suspend (CalendarDay, Boolean) -> Unit) = withContext(Dispatchers.IO) {
        lock.withLock {
            val day = CalendarDay.now()
            val previous = read(day)
            val plan = if (previous == null || !previous.hasClassified(id)) {
                reconcile(previous, day).also { save(previous, it) }
            } else previous
            commit(day, id in plan.rated)
            save(plan, if (id in plan.rated) plan else plan.copy(rated = plan.rated + id))
        }
    }

    suspend fun clear(clearNotebook: suspend () -> Unit) = withContext(Dispatchers.IO) {
        lock.withLock {
            clearNotebook()
            check(prefs.edit().clear().commit()) { "Could not clear daily review progress" }
            state.value = null
        }
    }
}
