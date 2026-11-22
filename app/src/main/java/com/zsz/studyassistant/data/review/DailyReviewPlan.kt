package com.zsz.studyassistant.data

import android.content.Context
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
    val completed = planned.count { it in rated }

    fun reconcile(rows: List<DailyReviewRow>, day: CalendarDay): DailyReviewPlan {
        val plannedIds = planned.toMutableSet()
        val outsideIds = outsidePlan.toMutableSet()
        val ratedIds = rated.toMutableSet()
        val start = day.start
        val end = day.end
        for (row in rows) {
            val reviewedToday = row.lastReviewedAt != null && row.lastReviewedAt in start..end
            if (reviewedToday && !row.deleted) ratedIds.add(row.id)
            if (row.id in planned || row.id in outsidePlan) continue
            // Classify each permanent notebook ID once, including old, future and hidden rows.
            // createdAt belongs to the original solve and may predate saving to the notebook.
            if (!row.deleted &&
                (reviewedToday || (row.nextReviewAt != null && row.nextReviewAt <= end))) {
                plannedIds.add(row.id)
            } else {
                outsideIds.add(row.id)
            }
        }
        return copy(planned = plannedIds, outsidePlan = outsideIds, rated = ratedIds)
    }
}

/** The baseline is local to this installation. Room remains authoritative for a committed rating. */
internal class DailyReviewStore(context: Context) {
    private val prefs = context.getSharedPreferences("daily_review_v1", Context.MODE_PRIVATE)
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

    suspend fun refresh(dao: QuestionDao, onlyIfStarted: Boolean = false) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val day = CalendarDay.now()
                val previous = read(day)
                if (previous == null && onlyIfStarted) return@withLock null
                val plan = (previous ?: DailyReviewPlan(day.date.toString())).reconcile(dao.dailyReviewRows(), day)
                save(previous, plan)
                plan
            }
        }

    suspend fun rate(dao: QuestionDao, id: QuestionId,
        commit: suspend (CalendarDay, Boolean) -> Unit) = withContext(Dispatchers.IO) {
        lock.withLock {
            val day = CalendarDay.now()
            val previous = read(day)
            val plan = if (previous == null || (id !in previous.planned && id !in previous.outsidePlan)) {
                (previous ?: DailyReviewPlan(day.date.toString())).reconcile(dao.dailyReviewRows(), day)
                    .also { save(previous, it) }
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
