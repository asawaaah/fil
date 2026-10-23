package com.asawaaah.fil.data.feeds

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.SignStyle
import java.time.temporal.ChronoField
import java.util.Locale

/** Parses the many date formats found in RSS and Atom feeds. Returns epoch millis or null. */
object DateParser {
    private val zoneNames = mapOf(
        "UT" to "+0000", "UTC" to "+0000", "GMT" to "+0000", "Z" to "+0000",
        "EST" to "-0500", "EDT" to "-0400", "CST" to "-0600", "CDT" to "-0500",
        "MST" to "-0700", "MDT" to "-0600", "PST" to "-0800", "PDT" to "-0700",
        "CET" to "+0100", "CEST" to "+0200", "MET" to "+0100", "MEST" to "+0200",
        "BST" to "+0100", "EET" to "+0200", "EEST" to "+0300", "WET" to "+0000",
        "WEST" to "+0100", "JST" to "+0900", "KST" to "+0900", "AEST" to "+1000",
        "AEDT" to "+1100",
    )

    private val trailingZone = Regex("""\s([A-Za-z]{1,5})$""")
    private val colonOffset = Regex("""\s([+-]\d{2}):(\d{2})$""")
    private val compactOffset = Regex("""\s([+-]\d{4})$""")

    // "[EEE, ]d MMM yyyy HH:mm[:ss] +HHMM" with a 2- or 4-digit year.
    private val rfcLenient: DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .parseLenient()
        .optionalStart().appendPattern("EEE").optionalStart().appendLiteral(',').optionalEnd()
        .appendLiteral(' ').optionalEnd()
        .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NOT_NEGATIVE)
        .appendLiteral(' ')
        .appendPattern("MMM")
        .appendLiteral(' ')
        .appendValueReduced(ChronoField.YEAR, 2, 4, 1970)
        .appendLiteral(' ')
        .appendPattern("H:mm")
        .optionalStart().appendPattern(":ss").optionalEnd()
        .appendLiteral(' ')
        .appendOffset("+HHMM", "+0000")
        .toFormatter(Locale.ENGLISH)

    private val isoSpace: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss][.SSS][XXX][XX]")

    fun parse(raw: String?): Long? {
        val s = raw?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() } ?: return null
        parseIso(s)?.let { return it }
        parseRfc(s)?.let { return it }
        return null
    }

    private fun parseIso(s: String): Long? {
        tryParse { OffsetDateTime.parse(s).toInstant().toEpochMilli() }?.let { return it }
        tryParse { Instant.parse(s).toEpochMilli() }?.let { return it }
        tryParse { ZonedDateTime.parse(s).toInstant().toEpochMilli() }?.let { return it }
        // Offsets without colon: 2024-03-01T10:00:00+0200
        val fixedOffset = Regex("""([+-]\d{2})(\d{2})$""").replace(s) { "${it.groupValues[1]}:${it.groupValues[2]}" }
        if (fixedOffset != s) {
            tryParse { OffsetDateTime.parse(fixedOffset).toInstant().toEpochMilli() }?.let { return it }
        }
        tryParse { LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli() }?.let { return it }
        tryParse {
            val t = java.time.temporal.TemporalQueries.offset()
            val parsed = isoSpace.parse(s)
            val offset = parsed.query(t) ?: ZoneOffset.UTC
            LocalDateTime.from(parsed).toInstant(offset).toEpochMilli()
        }?.let { return it }
        tryParse { LocalDate.parse(s.take(10)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
            ?.takeIf { s.length == 10 }?.let { return it }
        return null
    }

    private fun parseRfc(input: String): Long? {
        var s = input
        // Drop a "(PST)" style comment.
        s = s.replace(Regex("""\s*\(.*\)$"""), "")
        // Named zone -> numeric offset.
        trailingZone.find(s)?.let { m ->
            val off = zoneNames[m.groupValues[1].uppercase()]
            s = if (off != null) s.substring(0, m.range.first) + " " + off else s
        }
        // "+02:00" -> "+0200"
        colonOffset.find(s)?.let { m -> s = s.substring(0, m.range.first) + " " + m.groupValues[1] + m.groupValues[2] }
        // No zone at all -> assume UTC.
        if (!compactOffset.containsMatchIn(s)) s = "$s +0000"
        // "Tues, 03 ..." and similar long day names: keep the first three letters.
        s = s.replace(Regex("""^([A-Za-z]{3})[A-Za-z]*,?\s"""), "$1, ")
        // Month names longer than three letters ("June", "Sept."), and two-digit years ("25" -> "2025").
        s = s.replace(Regex("""(\d{1,2}) ([A-Za-z]{3})[A-Za-z]*\.? (\d{2,4})\b""")) {
            val year = it.groupValues[3].let { y -> if (y.length == 2) (if (y.toInt() < 70) "20$y" else "19$y") else y }
            "${it.groupValues[1]} ${it.groupValues[2]} $year"
        }
        tryParse { ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            ?.let { return it }
        tryParse { OffsetDateTime.parse(s, rfcLenient).toInstant().toEpochMilli() }?.let { return it }
        return null
    }

    private inline fun tryParse(block: () -> Long): Long? = try {
        block()
    } catch (_: Exception) {
        null
    }
}
