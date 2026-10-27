package com.barclayd.muoduo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant

/**
 * Full screen from the ticker: each area as it's checked, and every request the Worker has just made to
 * Apple, as it happens.
 */
@Composable
fun ActivityPage(model: StatusModel, finish: Finish, close: () -> Unit) {
    val status = model.status
    val arrivals = rememberArrivals()
    Page(
        finish,
        title = "Live activity",
        inlineTitle = true,
        leading = { if (status != null) LiveBadge(model, status) },
        trailing = { BarButton(Icons.Filled.Close, "Close", close) },
    ) {
        if (status == null) return@Page
        val now by rememberNow(1_000)
        Summary(model, status, now)
        Areas(model, status, finish, now)
        Feed(model, status, finish, now, arrivals)
    }
}

@Composable
private fun Summary(model: StatusModel, status: Status, now: Instant) {
    val answered = model.liveChecks.filter { it.ok }
    val average = if (answered.isEmpty()) null else answered.sumOf { it.ms } / answered.size
    Column(Modifier.card(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiveDot(status.state.color, model.pulses, 9.dp)
            Text(headline(status), Modifier.weight(1f), style = Type.headline)
            if (status.config.paused) {
                ProminentButton(onClick = { model.launch { setPaused(false) } }, enabled = !model.isWorking) { Text("Resume") }
            }
        }
        Row {
            Stat("Last check", model.liveChecks.firstOrNull()?.let { ago(it.at, now) } ?: "–")
            Stat("Each size, each area", "every ${status.pace.intervalSeconds}s")
            Stat("Apple answers in", average?.let { "$it ms" } ?: "–")
        }
    }
}

private fun headline(status: Status) = when (status.state) {
    LiveState.Paused -> "Paused · not checking"
    is LiveState.Failing -> "Apple isn't answering"
    LiveState.Stale -> "Checking, but results may be stale"
    LiveState.Live, is LiveState.Slowed -> "Checking ${status.storeCount} stores in ${status.areas.size} areas"
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Stat(title: String, value: String) {
    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = Type.title3.weight(FontWeight.SemiBold).monospacedDigit())
        Text(title, color = ink.secondary, style = Type.caption)
    }
}

/** Each area, rippling as it's asked about. */
@Composable
private fun Areas(model: StatusModel, status: Status, finish: Finish, now: Instant) {
    Column(Modifier.card(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardHeader("Areas", Icons.Filled.PinDrop, "${status.storeCount} stores")
        Column {
            for ((index, area) in status.areas.withIndex()) {
                if (index > 0) Separator(Modifier.padding(start = 34.dp))
                val last = model.liveChecks.firstOrNull { it.area == area.name }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp).semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                        LiveDot(finish.accent, model.areaPulses[area.name] ?: 0, 8.dp)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(area.name, style = Type.subheadline.weight(FontWeight.SemiBold))
                        Text("${area.stores.size} stores near ${area.search}", color = ink.secondary, style = Type.caption)
                    }
                    Text(last?.let { ago(it.at, now) } ?: "–", color = ink.secondary, style = Type.caption.weight(FontWeight.Medium).monospacedDigit())
                }
            }
        }
    }
}

@Composable
private fun Feed(model: StatusModel, status: Status, finish: Finish, now: Instant, arrivals: Arrivals) {
    Column(Modifier.card(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CardHeader("Requests to Apple", Icons.Filled.MonitorHeart, "latest ${model.liveChecks.size}")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (check in model.liveChecks) arrivals.Row(check.id) { CheckRow(check, status, finish, now) }
        }
    }
}

/** One request the Worker made to Apple. */
@Composable
private fun CheckRow(check: Status.Check, status: Status, finish: Finish, now: Instant) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(
            if (check.ok) Icons.Filled.CheckCircle else Icons.Filled.Dangerous,
            Type.body,
            tint = if (check.ok) finish.accent else ink.orange,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(check.area, style = Type.subheadline.weight(FontWeight.SemiBold))
            Text(status.subject(check), color = ink.secondary, style = Type.caption)
        }
        Spacer(Modifier.width(0.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(if (check.ok) "${check.ms} ms" else "failed", style = Type.caption.weight(FontWeight.Medium).monospacedDigit())
            Text(ago(check.at, now), color = ink.secondary, style = Type.caption.monospacedDigit())
        }
    }
}

private fun ago(date: Instant, now: Instant) = "${Duration.between(date, now).seconds.coerceAtLeast(0)}s ago"
