package com.zsz.studyassistant.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

class BackupNamesTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val at = GregorianCalendar(utc).apply { clear(); set(2026, Calendar.OCTOBER, 26, 14, 5, 9) }.timeInMillis
    @Test fun singleFileNamesDistinguishAutomaticAndManualWithoutPartSuffix() {
        assertEquals("SABak-20261026140509.zip", BackupNames.fileName(at, false, timeZone = utc))
        assertEquals("SABak_auto-20261026140509.zip", BackupNames.fileName(at, true, timeZone = utc))
    }
    @Test fun volumeNumbersUseOneBasedOfAndNoPadding() {
        assertEquals("SABak-20261026140509-1of3.zip", BackupNames.fileName(at, false, 0, 3, utc))
        assertEquals("SABak_auto-20261026140509-2of3.zip", BackupNames.fileName(at, true, 1, 3, utc))
        assertEquals("SABak_auto-20261026140509-12of12.zip", BackupNames.fileName(at, true, 11, 12, utc))
    }
    @Test fun timeUsesProvidedZoneAndOmitsMilliseconds() {
        assertEquals("SABak-20261026220509.zip", BackupNames.fileName(at + 987, false, timeZone = TimeZone.getTimeZone("GMT+08:00")))
        assertEquals("SABak-19700101000000.zip", BackupNames.fileName(0, false, timeZone = utc))
    }
    @Test fun invalidVolumeCoordinatesCannotCreateMisleadingNames() {
        for ((index, total) in listOf(-1 to 3, 3 to 3, 0 to 0, 1 to 1, 0 to -2)) {
            try { BackupNames.fileName(at, false, index, total, utc); fail("Accepted $index / $total") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
