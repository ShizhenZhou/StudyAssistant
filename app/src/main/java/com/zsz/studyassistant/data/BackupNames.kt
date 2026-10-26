package com.zsz.studyassistant.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** User-facing document names; archive identity always comes from the internal header. */
object BackupNames {
    fun fileName(snapshotAt: Long, automatic: Boolean, partIndex: Int = 0, totalParts: Int = 1,
                 timeZone: TimeZone = TimeZone.getDefault()): String {
        require(totalParts > 0 && partIndex in 0 until totalParts)
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply { this.timeZone = timeZone }.format(Date(snapshotAt))
        val prefix = if (automatic) "SABak_auto" else "SABak"
        val part = if (totalParts == 1) "" else "-${partIndex + 1}of$totalParts"
        return "$prefix-$stamp$part.sab"
    }
}
