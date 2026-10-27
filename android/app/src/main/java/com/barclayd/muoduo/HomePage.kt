package com.barclayd.muoduo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalWifiBad
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** The app's first screen: the phone, whether it's in stock, and every store being watched. */
@Composable
fun HomePage(
    model: StatusModel,
    finish: Finish,
    select: (Finish) -> Unit,
    openSettings: () -> Unit,
    openLog: () -> Unit,
    openMap: (String?) -> Unit,
    openActivity: () -> Unit,
) {
    val status = model.status ?: return Unavailable(model, finish)
    HapticOnChange(status.sightings.size) { old, new -> if (new > old) HapticFeedbackType.Confirm else null }
    Page(
        finish,
        leading = { LiveBadge(model, status) },
        trailing = {
            BarGroup {
                BarIcon(Icons.Outlined.Map, "Store map") { openMap(null) }
                BarIcon(Icons.Outlined.Settings, "Settings", openSettings)
            }
        },
        onRefresh = { model.refresh() },
        bottomBar = { Ticker(model, status, finish, openActivity) },
    ) {
        Box(Modifier.padding(bottom = 8.dp)) { Hero(model, status, finish, select) }
        // Straight under the colour picker, so a sighting is the first thing on screen.
        InStockCard(model, status, finish)
        HealthBanner(status, model.errorMessage)
        LastSeenCard(status, finish)
        StockLogCard(status, openLog)
        StoresCard(model, status, finish, openMap)
        SlowedBanner(status)
    }
}

/** Before the first status arrives: a spinner, or why it hasn't, as iOS's ContentUnavailableView. */
@Composable
private fun Unavailable(model: StatusModel, finish: Finish) {
    val haze = rememberHazeState()
    val error = model.errorMessage
    CompositionLocalProvider(LocalHaze provides haze) {
        Box(Modifier.fillMaxSize().background(ink.background), contentAlignment = Alignment.Center) {
            Backdrop(finish, Modifier.matchParentSize().hazeSource(haze))
            if (error == null) {
                CircularProgressIndicator(Modifier.size(24.dp), color = ink.secondary, strokeWidth = 2.5.dp)
                return@Box
            }
            Column(
                Modifier.padding(horizontal = 40.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Symbol(Icons.Filled.SignalWifiBad, Type.body.copy(fontSize = 44.sp), Modifier.padding(bottom = 8.dp), tint = ink.secondary)
                Text("Can't reach the checker", style = Type.title2.weight(FontWeight.Bold), textAlign = TextAlign.Center)
                Text(error, color = ink.secondary, style = Type.subheadline, textAlign = TextAlign.Center)
                GlassButton(onClick = { model.launch { refresh() } }, modifier = Modifier.padding(top = 8.dp)) { Text("Try again") }
            }
        }
    }
}
