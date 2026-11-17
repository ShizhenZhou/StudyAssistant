package com.zsz.studyassistant.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

/** Conservative display hints alongside the unchanged source value, never a formula evaluator. */
internal object XlsxNumberDisplay {
    fun builtIn(id: Int): String? = when (id) {
        0 -> "General"
        1 -> "0"
        2 -> "0.00"
        3 -> "#,##0"
        4 -> "#,##0.00"
        9 -> "0%"
        10 -> "0.00%"
        11 -> "0.00E+00"
        14 -> "mm-dd-yy"
        15 -> "d-mmm-yy"
        16 -> "d-mmm"
        17 -> "mmm-yy"
        18 -> "h:mm AM/PM"
        19 -> "h:mm:ss AM/PM"
        20 -> "h:mm"
        21 -> "h:mm:ss"
        22 -> "m/d/yy h:mm"
        45 -> "mm:ss"
        46 -> "[h]:mm:ss"
        47 -> "mmss.0"
        49 -> "@"
        else -> null
    }

    fun describe(raw: String, format: String?, date1904: Boolean): String? {
        if (format == null || format == "General") return null
        // Unknown/conditional/fraction/accounting formats retain their original format as a hint.
        val safeFormat = format.replace('\n', ' ').replace('\r', ' ').take(256)
        val fallback = " [number format: $safeFormat; raw value retained]"
        if (raw.length > 128 || format.length > 256) return fallback
        val number = raw.toBigDecimalOrNull() ?: return fallback
        if (number.scale() !in -100..100 || number.precision() - number.scale() > 40) return fallback
        // Conditions select sections by their predicates, not the ordinary positive/negative order.
        // Do not guess another section's meaning even when the chosen section has no condition.
        if (Regex("\\[[<>=]").containsMatchIn(format)) return fallback
        val sections = format.split(';')
        if (sections.size > 4) return fallback
        val section = when {
            number.signum() < 0 && sections.size > 1 -> sections[1]
            number.signum() == 0 && sections.size > 2 -> sections[2]
            else -> sections[0]
        }.replace(Regex("\\[(Black|Blue|Cyan|Green|Magenta|Red|White|Yellow)]", RegexOption.IGNORE_CASE), "")
        val tokens = section.replace(Regex("\"[^\"]*\"|\\\\."), "").lowercase(Locale.ROOT)
        if ('[' in tokens || ']' in tokens) return fallback
        val date = 'y' in tokens || 'd' in tokens || ('m' in tokens && 'h' !in tokens && 's' !in tokens)
        val time = 'h' in tokens || 's' in tokens
        val displayed = if (date || time) {
            // Reject arbitrary unquoted letters so an unsupported numeric format is not guessed as a date.
            if (tokens.replace("am/pm", "").any { it.isLetter() && it !in "ymdhs" }) return fallback
            dateDisplay(number, date1904, date, time)
        } else {
            numericDisplay(number, section, number.signum() < 0 && sections.size > 1)
        } ?: return fallback
        return " [display: $displayed; format: $safeFormat]"
    }

    private fun numericDisplay(number: BigDecimal, section: String, separateNegative: Boolean): String? {
        // Excel's common fixed, grouped, percentage and scientific patterns share this subset.
        if (!section.matches(Regex("[-+($€£¥ ]?[#0,]+(?:\\.[#0]+)?(?:E[+]?[0]+)?%?[)$€£¥ ]?"))) return null
        return runCatching {
            DecimalFormat(section.replace("E+", "E"), DecimalFormatSymbols(Locale.ROOT)).apply {
                roundingMode = RoundingMode.HALF_UP
                maximumIntegerDigits = 80
            }.format(if (separateNegative) number.abs() else number)
        }.getOrNull()
    }

    private fun dateDisplay(number: BigDecimal, date1904: Boolean, date: Boolean, time: Boolean): String? {
        val serial = number.toDouble()
        if (!serial.isFinite() || serial < 0 || serial >= 2_958_466) return null
        var day = floor(serial).toLong()
        var seconds = ((serial - day) * 86400).roundToLong()
        if (seconds == 86400L) { if (time) day++; seconds = 0 }
        val dateText = if (!date1904 && day == 60L) "1900-02-29 (Excel leap-day convention)" else {
            val actual = if (date1904) LocalDate.of(1904, 1, 1).plusDays(day)
            else LocalDate.of(1899, 12, 31).plusDays(day - if (day > 60) 1 else 0)
            if (actual.year > 9999) return null
            actual.toString()
        }
        val timeText = String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        return when {
            date && time -> "$dateText $timeText"
            date -> dateText
            else -> timeText
        }
    }
}
