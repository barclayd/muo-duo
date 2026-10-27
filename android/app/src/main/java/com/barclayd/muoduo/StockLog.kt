package com.barclayd.muoduo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Each time a watched size came into stock at a store and went again, and when the checker stopped and
 * started, so a night's stock can be read back in the morning.
 */
@Composable
fun StockLogCard(status: Status, showAll: () -> Unit) {
    val arrivals = rememberArrivals()
    Column(Modifier.card(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CardHeader("Stock log", Icons.AutoMirrored.Filled.Assignment, lastDay(status))
        if (status.log.isEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Nothing yet", style = Type.headline)
                Text(
                    "Each time a size you watch comes into stock at one of the ${status.storeCount} stores, or goes again, it's logged here.",
                    color = ink.secondary,
                    style = Type.subheadline,
                )
                status.loggingSince?.let {
                    Text("Logging since ${it.dateAndTime()}.", color = ink.secondary, style = Type.caption)
                }
            }
        } else {
            for (day in status.log.take(SHOWN).byDay()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(day.title, color = ink.secondary, style = Type.caption.weight(FontWeight.SemiBold))
                    for (entry in day.entries) arrivals.Row(entry.id) { LogRow(status, entry) }
                }
            }
            if (status.log.size > SHOWN) {
                GlassButton(onClick = showAll, modifier = Modifier.fillMaxWidth()) {
                    Text("Show All ${status.log.size}", style = Type.subheadline.weight(FontWeight.SemiBold))
                }
            }
        }
    }
}

private const val SHOWN = 8

private fun lastDay(status: Status): String {
    val dayAgo = Instant.now().minusSeconds(86_400)
    return when (val sightings = status.log.count { it.kind == Status.LogEntry.Kind.InStock && it.at > dayAgo }) {
        0 -> "None in 24h"
        1 -> "1 sighting in 24h"
        else -> "$sightings sightings in 24h"
    }
}

/** The whole log, a card per day. */
@Composable
fun StockLogPage(model: StatusModel, finish: Finish, back: () -> Unit) {
    val arrivals = rememberArrivals()
    Page(finish, title = "Stock log", leading = { BarButton(Icons.Rounded.ChevronLeft, "Back", back) }) {
        val status = model.status ?: return@Page
        for (day in status.log.byDay()) {
            Column(Modifier.card(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                CardHeader(day.title, Icons.Filled.CalendarToday, sightings(day))
                for (entry in day.entries) arrivals.Row(entry.id) { LogRow(status, entry) }
            }
        }
        status.loggingSince?.let {
            Text(
                "Logging since ${it.dateAndTime()}. The latest 200 events are kept.",
                Modifier.fillMaxWidth().padding(top = 4.dp),
                color = ink.secondary,
                style = Type.caption,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun sightings(day: LogDay): String? = when (val count = day.entries.count { it.kind == Status.LogEntry.Kind.InStock }) {
    0 -> null
    1 -> "1 sighting"
    else -> "$count sightings"
}

class LogDay(val day: LocalDate, /** Most recent first. */ val entries: List<Status.LogEntry>) {
    val title get() = day.title()
}

/** Entries most recent first, split by the day they happened on. */
fun List<Status.LogEntry>.byDay(): List<LogDay> = groupBy { it.at.day() }.map { (day, entries) -> LogDay(day, entries) }

/** One line of the log. Stock coming and going stands out; the checker's own comings and goings are quieter. */
@Composable
private fun LogRow(status: Status, entry: Status.LogEntry) {
    val stock = entry.kind == Status.LogEntry.Kind.InStock || entry.kind == Status.LogEntry.Kind.Gone
    val foreground = if (stock) ink.primary else ink.secondary
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            Symbol(symbol(entry.kind), Type.subheadline, tint = color(entry.kind))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title(status, entry), color = foreground, style = if (stock) Type.subheadline.weight(FontWeight.SemiBold) else Type.subheadline)
            detail(status, entry)?.let { Text(it, color = ink.secondary, style = Type.caption) }
        }
        Spacer(Modifier.widthIn(min = 8.dp))
        Text(entry.at.time(), color = ink.secondary, style = Type.caption.weight(FontWeight.Medium).monospacedDigit())
    }
}

private fun name(status: Status, entry: Status.LogEntry) = entry.part?.let { status.model(it)?.name ?: it } ?: "iPhone Duo"

private fun title(status: Status, entry: Status.LogEntry) = when (entry.kind) {
    Status.LogEntry.Kind.InStock -> "${name(status, entry)} in stock"
    Status.LogEntry.Kind.Gone -> "${name(status, entry)} gone"
    Status.LogEntry.Kind.Failing -> "Apple stopped answering"
    Status.LogEntry.Kind.Recovered -> "Apple answering again"
    Status.LogEntry.Kind.Stale -> "Results may be stale"
    Status.LogEntry.Kind.Normal -> "Results look right again"
    Status.LogEntry.Kind.Paused -> "Checking paused"
    Status.LogEntry.Kind.Resumed -> "Checking resumed"
}

private fun detail(status: Status, entry: Status.LogEntry): String? = when (entry.kind) {
    Status.LogEntry.Kind.InStock -> listOfNotNull(place(status, entry), entry.quote).joinToString(" · ")
    Status.LogEntry.Kind.Gone -> listOfNotNull(place(status, entry), entry.since?.let { lasted(entry.at, it) }).joinToString(" · ")
    Status.LogEntry.Kind.Failing -> entry.detail
    Status.LogEntry.Kind.Stale -> entry.detail?.let { "iPhone 17 showed no stock near $it" }
    else -> null
}?.takeIf { it.isNotEmpty() }

private fun symbol(kind: Status.LogEntry.Kind) = when (kind) {
    Status.LogEntry.Kind.InStock -> Icons.Filled.ShoppingBag
    Status.LogEntry.Kind.Gone -> Icons.Outlined.ShoppingBag
    Status.LogEntry.Kind.Failing -> Icons.Filled.Warning
    Status.LogEntry.Kind.Recovered, Status.LogEntry.Kind.Normal -> Icons.Outlined.CheckCircle
    Status.LogEntry.Kind.Stale -> Icons.AutoMirrored.Filled.Help
    Status.LogEntry.Kind.Paused -> Icons.Outlined.PauseCircle
    Status.LogEntry.Kind.Resumed -> Icons.Outlined.PlayCircle
}

@Composable
private fun color(kind: Status.LogEntry.Kind): Color = when (kind) {
    Status.LogEntry.Kind.InStock -> ink.green
    Status.LogEntry.Kind.Failing -> ink.orange
    Status.LogEntry.Kind.Stale -> ink.yellow
    else -> ink.secondary
}

/** "Apple Battersea, London", or just the store when the area is named after it. */
private fun place(status: Status, entry: Status.LogEntry): String? {
    val store = entry.storeName ?: return null
    val area = entry.storeNumber?.let(status::area)
    return if (area == null || area.name == store) "Apple $store" else "Apple $store, ${area.name}"
}

private fun lasted(at: Instant, since: Instant): String {
    val duration = Duration.between(since, at)
    return if (duration.seconds < 60) "lasted under a minute" else "lasted ${hoursAndMinutes(duration, short = true)}"
}
