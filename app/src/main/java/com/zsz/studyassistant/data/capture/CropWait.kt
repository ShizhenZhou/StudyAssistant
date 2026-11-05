package com.zsz.studyassistant.data

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

internal object CropWait {
    const val UNLIMITED = 0L
    const val FINITE_END = .80f
    const val DETENT = .89f

    fun normalize(ms: Long) = if (ms == UNLIMITED) ms else ms.coerceIn(100L, 5000L)
    fun position(ms: Long) = if (ms == UNLIMITED) 1f else FINITE_END * ln(normalize(ms) / 100f) / ln(50f)

    fun fromPosition(t: Float, previous: Long): Long {
        if (t >= .96f || (previous == UNLIMITED && t >= DETENT)) return UNLIMITED
        return (100f * 50f.pow((t / FINITE_END).coerceIn(0f, 1f))).roundToLong()
    }

    suspend fun <T : Any> await(ms: Long, request: suspend () -> T): T? =
        if (ms == UNLIMITED) request() else kotlinx.coroutines.withTimeoutOrNull(ms) { request() }
}
