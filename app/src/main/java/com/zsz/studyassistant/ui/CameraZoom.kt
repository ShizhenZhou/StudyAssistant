package com.zsz.studyassistant.ui

/** Ignore unavailable/invalid camera state; keep valid pinch requests inside hardware limits. */
internal fun nextCameraZoomRatio(current: Float, factor: Float, minimum: Float, maximum: Float): Float? {
    if (!current.isFinite() || current <= 0f || !factor.isFinite() || factor <= 0f ||
        !minimum.isFinite() || minimum <= 0f || !maximum.isFinite() || maximum < minimum
    ) return null
    // Double arithmetic prevents a very large but finite scale factor from overflowing Float.
    return (current.toDouble() * factor.toDouble())
        .coerceIn(minimum.toDouble(), maximum.toDouble()).toFloat()
}
