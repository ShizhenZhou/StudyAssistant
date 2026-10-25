package com.zsz.studyassistant.data

import java.time.*

data class CalendarDay(val date: LocalDate, val zone: ZoneId) {
    val start: Long get() = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val end: Long get() = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    val weekEnd: Long get() = date.plusDays((7 - date.dayOfWeek.value).toLong()).plusDays(1)
        .atStartOfDay(zone).toInstant().toEpochMilli() - 1
    fun after(days: Int): Long = date.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
    fun dateOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    companion object { fun now() = ZoneId.systemDefault().let { CalendarDay(LocalDate.now(it), it) } }
}

object ReviewPlanning {
    private val intervals = intArrayOf(0,1,2,4,7,15,30)
    fun schedule(level: Int, current: Int): Pair<Int,Int> {
        val step = when(level) { 2 -> current + 1; 1 -> current; else -> 0 }.coerceIn(0,intervals.lastIndex)
        return step to intervals[step].coerceAtLeast(1)
    }
    fun ordered(candidates: List<QuestionId>, selected: Int): List<QuestionId> {
        val ids=candidates.distinct(); if(ids.isEmpty()) return ids
        val first=selected.coerceIn(0,ids.lastIndex)
        return ids.drop(first)+ids.take(first)
    }
    /** An estimate from surviving last-review dates, never presented as full activity history. */
    fun recordedStreak(times: List<Long>, day: CalendarDay): Int {
        val dates=times.filter { it>0 }.map(day::dateOf).toSet()
        var cursor=if(day.date in dates) day.date else day.date.minusDays(1)
        var result=0
        while(cursor in dates) { result++; cursor=cursor.minusDays(1) }
        return result
    }
}
