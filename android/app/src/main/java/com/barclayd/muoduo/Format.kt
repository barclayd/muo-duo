package com.barclayd.muoduo

import android.icu.text.ListFormatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlinx.coroutines.delay

// The words and numbers iOS's formatters give, so the two apps read the same.

/** The time now, updated every [periodMillis], like a SwiftUI TimelineView. */
@Composable
fun rememberNow(periodMillis: Long): State<Instant> = produceState(Instant.now(), periodMillis) {
    while (true) {
        delay(periodMillis - System.currentTimeMillis() % periodMillis)
        value = Instant.now()
    }
}

/** The last value that wasn't null, so content can stay on screen while it animates away. */
@Composable
fun <T : Any> rememberLast(value: T?): T? {
    val last = remember { arrayOfNulls<Any>(1) }
    if (value != null) last[0] = value
    @Suppress("UNCHECKED_CAST")
    return last[0] as T?
}

/** "5 minutes ago", "in 2 hours", "yesterday": iOS's relative date, named. */
fun relative(date: Instant, now: Instant = Instant.now()): String {
    val seconds = Duration.between(date, now).seconds
    val ago = seconds >= 0
    val (count, unit) = abs(seconds).let {
        when {
            it < 1 -> return "now"
            it < 60 -> it to "second"
            it < 3_600 -> it / 60 to "minute"
            it < 86_400 -> it / 3_600 to "hour"
            it < 7 * 86_400 -> it / 86_400 to "day"
            it < 30 * 86_400 -> it / (7 * 86_400) to "week"
            it < 365 * 86_400 -> it / (30 * 86_400) to "month"
            else -> it / (365 * 86_400) to "year"
        }
    }
    if (count == 1L) {
        when (unit) {
            "day" -> return if (ago) "yesterday" else "tomorrow"
            "week", "month", "year" -> return if (ago) "last $unit" else "next $unit"
        }
    }
    val units = if (count == 1L) "$count $unit" else "$count ${unit}s"
    return if (ago) "$units ago" else "in $units"
}

/** "1 hour, 5 minutes", or "1 hr, 5 min" when [short]: hours and minutes, leaving out a zero. */
fun hoursAndMinutes(duration: Duration, short: Boolean = false): String {
    val hours = duration.toHours()
    val minutes = duration.toMinutesPart()
    fun unit(count: Long, wide: String, abbreviated: String) =
        if (short) "$count $abbreviated" else if (count == 1L) "$count $wide" else "$count ${wide}s"
    return listOfNotNull(
        unit(hours, "hour", "hr").takeIf { hours > 0 },
        unit(minutes.toLong(), "minute", "min").takeIf { minutes > 0 || hours == 0L },
    ).joinToString(", ")
}

/** A stopwatch counting up from [since]: "4:05", or "1:02:03" past an hour. */
fun timer(since: Instant, now: Instant): String {
    val seconds = Duration.between(since, now).seconds.coerceAtLeast(0)
    val (h, m, s) = Triple(seconds / 3_600, seconds / 60 % 60, seconds % 60)
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "A, B and C". */
fun List<String>.joinedAsList(): String = ListFormatter.getInstance().format(this)

private val zone get() = ZoneId.systemDefault()

/** "06:05", in the phone's own clock style. */
fun Instant.time(): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(atZone(zone))

/** "27 Oct 2026, 06:05". */
fun Instant.dateAndTime(): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(atZone(zone))

fun Instant.day(): LocalDate = atZone(zone).toLocalDate()

/** "Today", "Yesterday" or "Monday 27 October". */
fun LocalDate.title(): String = when (this) {
    LocalDate.now() -> "Today"
    LocalDate.now().minusDays(1) -> "Yesterday"
    else -> DateTimeFormatter.ofPattern("EEEE d MMMM").format(this)
}

fun <T> List<T>.uniqued(): List<T> = distinct()
