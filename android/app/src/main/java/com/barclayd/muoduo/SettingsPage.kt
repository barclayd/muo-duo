package com.barclayd.muoduo

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOn
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PhonelinkRing
import androidx.compose.material.icons.outlined.PinDrop
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

/**
 * Pushed from the home screen, in the same glass over the same sky. Alerts come first: they're what
 * matters most, and turning one off is the easiest way to miss stock. Alerts, the alarm, areas and mute
 * are this phone's own; pausing is for everyone, so only the owner's key can do it.
 */
@Composable
fun SettingsPage(model: StatusModel, finish: Finish, back: () -> Unit) {
    Page(finish, title = "Settings", leading = { BarButton(Icons.Rounded.ChevronLeft, "Back", back) }) {
        val status = model.status ?: return@Page
        val device = status.device
        if (device != null) {
            AlertsCard(model, status, device)
            AlarmCard(model, device)
        } else {
            UnregisteredCard(model)
        }
        CheckingCard(model, status)
        NotificationsCard(model, status)
        AreasCard(model, status, finish)
        Text(
            "Product images are Apple's, loaded from apple.com.",
            Modifier.padding(top = 4.dp).fillMaxWidth(),
            color = ink.secondary,
            style = Type.caption,
            textAlign = TextAlign.Center,
        )
    }
}

// Alerts

/** Every size of both colours. Turning one on is a tap; turning one off asks first. */
@Composable
private fun AlertsCard(model: StatusModel, status: Status, device: Status.Device) {
    val watching = status.models.count { it.part in device.parts }
    val warning = when {
        device.muted -> "This phone is muted, so it won't hear about any stock."
        device.parts.isEmpty() -> "No alerts are on, so you won't hear about any stock."
        device.areas.isEmpty() -> "No areas are on, so you won't hear about any stock."
        else -> null
    }
    val areas = when (device.areas.size) {
        1 -> "your area"
        status.areas.size -> "all ${status.areas.size} areas"
        else -> "your ${device.areas.size} areas"
    }
    Column(Modifier.animateContentSize(smooth()).card(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        CardHeader("Alerts", Icons.Outlined.NotificationsActive, "$watching on")
        for (finish in Finish.entries) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Swatch(finish, 16)
                    Text(finish.title, style = Type.subheadline.weight(FontWeight.SemiBold))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (phone in status.models(finish)) {
                        key(phone.part) { AlertChip(model, status, device, phone, finish, enabled = !model.isWorking) }
                    }
                }
            }
        }
        if (warning != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol(Icons.Filled.NotificationsOff, Type.caption, tint = ink.orange)
                Text(warning, color = ink.orange, style = Type.caption.weight(FontWeight.Medium))
            }
        } else {
            Text(
                "Every size you watch is checked in $areas, every ${status.pace.intervalSeconds}s.",
                color = ink.secondary,
                style = Type.caption,
            )
        }
    }
}

@Composable
private fun RowScope.AlertChip(model: StatusModel, status: Status, device: Status.Device, phone: Status.Model, finish: Finish, enabled: Boolean) {
    val watching = phone.part in device.parts
    val inStock = status.sightings.any { it.part == phone.part }
    var confirmingOff by remember { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    HapticOnChange(watching) { _, on -> if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff }

    Box(
        Modifier
            .weight(1f)
            .glass(RoundedCornerShape(18.dp), if (watching) finish.accent.copy(alpha = 0.35f) else null, interactions)
            .clickable(interactions, indication = null, enabled = enabled, role = Role.Button) {
                if (watching) confirmingOff = true else model.launch { setWatching(phone.part, true) }
            }
            .semantics(mergeDescendants = true) {
                contentDescription = "${phone.name}, ${if (watching) "alerts on" else "alerts off"}"
            },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp).alpha(if (enabled) 1f else 0.5f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedContent(
                watching,
                transitionSpec = {
                    (fadeIn(snappy()) + scaleIn(snappy(), initialScale = 0.5f)) togetherWith
                        (fadeOut(snappy()) + scaleOut(snappy(), targetScale = 0.5f))
                },
                contentAlignment = Alignment.Center,
                label = "tick",
            ) { on ->
                if (on) {
                    // A white tick on the accent, which would otherwise sink into glass tinted the same colour.
                    Box(contentAlignment = Alignment.Center) {
                        Box(Modifier.size(12.dp).background(Color.White, CircleShape))
                        Symbol(Icons.Filled.CheckCircle, Type.body, tint = finish.accent)
                    }
                } else {
                    Symbol(Icons.Outlined.RadioButtonUnchecked, Type.body, tint = ink.secondary)
                }
            }
            Text(phone.capacity, color = if (watching) ink.primary else ink.secondary, style = Type.subheadline.weight(FontWeight.SemiBold))
        }
        if (inStock) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(8.dp).background(ink.green, CircleShape))
    }

    if (confirmingOff) {
        Confirm(
            title = "Turn off alerts for ${phone.name}?",
            message = "This phone won't hear when it's in stock at any of its ${status.storeCount(device.areas)} stores.",
            action = "Turn Off Alerts",
            dismiss = { confirmingOff = false },
        ) { model.launch { setWatching(phone.part, false) } }
    }
}

// Alarm

private const val TEST_DELAY = 10

/** iOS's AlarmKit authorisation, from what Android asks for: notifications, and taking over the screen. */
private enum class AlarmPermission { NotAsked, Denied, Allowed }

/**
 * Stock found in the alarm hours rings like an alarm clock, through silent mode and Do Not Disturb. Tapping
 * the hours opens wheels to choose them, saved once they've stopped turning. The test is sent after a
 * delay, so the phone can be locked to see it as it would be.
 */
@Composable
private fun AlarmCard(model: StatusModel, device: Status.Device) {
    val context = LocalContext.current
    val alarm = device.alarm
    val fullScreen = rememberOnResume(Permissions::fullScreen)
    val permission = when (model.notificationsAllowed) {
        null -> AlarmPermission.NotAsked
        false -> AlarmPermission.Denied
        true -> if (fullScreen) AlarmPermission.Allowed else AlarmPermission.Denied
    }
    var choosingHours by remember { mutableStateOf(false) }
    // The hours on the wheels, from when they were opened. Kept after they close, so a change made just
    // before closing still saves.
    var draft by remember { mutableStateOf<Status.Alarm?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var sendingTest by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.notificationsAllowed = granted
        if (granted && !Permissions.fullScreen(context)) Permissions.openFullScreenSettings(context)
    }

    // Each turn of a wheel restarts this, so only where they stop is saved.
    LaunchedEffect(draft) {
        val hours = draft ?: return@LaunchedEffect
        val saved = model.status?.device?.alarm ?: return@LaunchedEffect
        if (hours.from == saved.from && hours.to == saved.to) return@LaunchedEffect
        delay(600)
        model.launch { setAlarmHours(hours.from, hours.to) }
    }

    Column(Modifier.animateContentSize(smooth()).card(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardHeader("Alarm", Icons.Filled.Alarm, if (alarm.enabled) alarm.hours else "Off")
        Column {
            Toggle(
                "Ring for stock",
                alarm.enabled,
                { on -> model.launch { setAlarm(on) } },
                Modifier.padding(vertical = 7.dp),
                enabled = !model.isWorking,
            )
            Separator()
            val shown = if (choosingHours) draft ?: alarm else alarm
            SettingRow(
                "Hours",
                Modifier
                    .plainButton {
                        if (!choosingHours) draft = alarm
                        choosingHours = !choosingHours
                    }
                    .semantics { stateDescription = if (choosingHours) "Showing the hours" else "Hours hidden" },
            ) {
                Text(if (shown.allDay) shown.hours else "${shown.hours} UK", color = if (choosingHours) ink.blue else ink.secondary)
            }
            AnimatedVisibility(
                choosingHours,
                enter = fadeIn(smooth()) + expandVertically(smooth(), expandFrom = Alignment.Top),
                exit = fadeOut(smooth()) + shrinkVertically(smooth(), shrinkTowards = Alignment.Top),
            ) {
                HourWheels(draft ?: alarm) { draft = it }
            }
            Separator()
            SettingRow("Alarms on this phone") {
                when (permission) {
                    AlarmPermission.Allowed -> TrailingIcon("Allowed", Icons.Filled.CheckCircle, ink.green)
                    AlarmPermission.Denied -> TrailingIcon("Off", Icons.Filled.Warning, ink.orange)
                    AlarmPermission.NotAsked -> Text("Not asked yet")
                }
            }
        }
        when (permission) {
            AlarmPermission.NotAsked -> ProminentButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        model.notificationsAllowed = Permissions.notifications(context)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                large = true,
            ) {
                Symbol(Icons.Filled.Alarm)
                Text("Allow Alarms")
            }
            AlarmPermission.Denied -> ProminentButton(
                onClick = {
                    if (model.notificationsAllowed == false) {
                        Permissions.openNotificationSettings(context)
                    } else {
                        Permissions.openFullScreenSettings(context)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                color = ink.orange,
                large = true,
            ) {
                Symbol(Icons.Filled.AlarmOn)
                Text("Alarms are off. Turn them on")
            }
            AlarmPermission.Allowed -> Unit
        }
        GlassButton(
            onClick = {
                sendingTest = true
                model.launch {
                    testResult = sendTestAlarm(TEST_DELAY)
                    sendingTest = false
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            enabled = !sendingTest && permission == AlarmPermission.Allowed,
            large = true,
        ) {
            if (sendingTest) Spinner() else Symbol(Icons.Filled.Alarm)
            Text("Test Alarm in ${TEST_DELAY}s")
        }
        AnimatedContent(
            testResult ?: "Stock found in these hours rings an alarm, even on silent. At other times it's a notification.",
            transitionSpec = { fadeIn(smooth()) togetherWith fadeOut(smooth()) },
            label = "result",
        ) { text ->
            Text(text, color = ink.secondary, style = Type.caption)
        }
    }
}

/** From and to, in whole UK hours. Moving one past the other carries the other along, keeping the length. */
@Composable
private fun HourWheels(hours: Status.Alarm, change: (Status.Alarm) -> Unit) {
    val length = hours.to - hours.from
    Row(Modifier.padding(bottom = 6.dp)) {
        HourWheel("From", 0..23, hours.from, Modifier.weight(1f)) { from ->
            change(hours.copy(from = from, to = if (hours.to <= from) minOf(24, from + length) else hours.to))
        }
        HourWheel("To", 1..24, hours.to, Modifier.weight(1f)) { to ->
            change(hours.copy(to = to, from = if (hours.from >= to) maxOf(0, to - length) else hours.from))
        }
    }
}

private val WheelRow = 32.dp
private val WheelHeight = 130.dp

/** iOS's wheel picker: a drum of hours that comes to rest on one, ticking as each passes the middle. */
@Composable
private fun HourWheel(title: String, hours: IntRange, selection: Int, modifier: Modifier, select: (Int) -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selection - hours.first)
    val haptics = LocalHapticFeedback.current
    val rowPx = with(LocalDensity.current) { WheelRow.toPx() }
    val currentSelection by rememberUpdatedState(selection)
    val currentSelect by rememberUpdatedState(select)
    val centred by remember(state) { derivedStateOf { state.centred(rowPx, hours.count()) } }

    LaunchedEffect(state) {
        snapshotFlow { centred }.drop(1).collect {
            if (state.isScrollInProgress) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }
    // Chosen where the wheel comes to rest.
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.drop(1).filter { !it }.collect {
            val hour = hours.first + state.centred(rowPx, hours.count())
            if (hour != currentSelection) currentSelect(hour)
        }
    }
    // Turned by the other wheel, carrying this one along.
    LaunchedEffect(selection) {
        if (!state.isScrollInProgress && hours.first + state.centred(rowPx, hours.count()) != selection) {
            state.animateScrollToItem(selection - hours.first)
        }
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = ink.secondary, style = Type.caption.weight(FontWeight.SemiBold))
        Box(
            Modifier
                .fillMaxWidth()
                .height(WheelHeight)
                .clipToBounds()
                .semantics { contentDescription = title; stateDescription = Status.Alarm.clock(selection) },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(WheelRow).background(ink.primary.copy(alpha = 0.08f), RoundedCornerShape(8.dp)))
            LazyColumn(
                Modifier.fillMaxSize(),
                state = state,
                contentPadding = PaddingValues(vertical = (WheelHeight - WheelRow) / 2),
                flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(hours.count()) { index ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(WheelRow)
                            .graphicsLayer {
                                // Rows turn away and fade as they leave the middle, as on a drum.
                                val layout = state.layoutInfo
                                val item = layout.visibleItemsInfo.firstOrNull { it.index == index } ?: return@graphicsLayer
                                val middle = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
                                val reach = (layout.viewportEndOffset - layout.viewportStartOffset) / 2f
                                val distance = ((item.offset + item.size / 2f - middle) / reach).coerceIn(-1f, 1f)
                                rotationX = -distance * 55f
                                alpha = 1f - 0.7f * abs(distance)
                                cameraDistance = 6f * density
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            Status.Alarm.clock(hours.first + index),
                            color = if (index == centred) ink.primary else ink.secondary,
                            style = Type.title3.monospacedDigit(),
                        )
                    }
                }
            }
        }
    }
}

/** The row in the middle of a wheel, whose content padding puts row 0 there at rest. */
private fun LazyListState.centred(rowPx: Float, count: Int): Int =
    (firstVisibleItemIndex + if (firstVisibleItemScrollOffset > rowPx / 2) 1 else 0).coerceIn(0, count - 1)

// Checking

@Composable
private fun CheckingCard(model: StatusModel, status: Status) {
    var confirmingPause by remember { mutableStateOf(false) }
    Column(Modifier.animateContentSize(smooth()).card(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardHeader("Checking", Icons.Outlined.Sensors)
        Column {
            if (status.isOwner) {
                Toggle(
                    "Check Apple for stock",
                    !status.config.paused,
                    { checking -> if (checking) model.launch { setPaused(false) } else confirmingPause = true },
                    Modifier.padding(vertical = 7.dp),
                    enabled = !model.isWorking,
                )
            } else {
                SettingRow("Checking Apple for stock") { Text(if (status.config.paused) "Paused" else "On") }
            }
            Separator()
            SettingRow("Each size, each area") { Text("every ${status.pace.intervalSeconds}s") }
            status.health.lastSuccessAt?.let { date ->
                Separator()
                val now by rememberNow(1_000)
                SettingRow("Last good check") { Text(relative(date, now)) }
            }
            status.health.lastError?.let { error ->
                Separator()
                SettingRow("Last error") { Text(error) }
            }
        }
        status.pace.reason?.let { reason ->
            Text("Slowed down: $reason.", color = ink.secondary, style = Type.caption)
        }
    }

    if (confirmingPause) {
        Confirm(
            title = "Pause checking?",
            message = "No phone will get alerts until you turn it back on.",
            action = "Pause",
            dismiss = { confirmingPause = false },
        ) { model.launch { setPaused(true) } }
    }
}

// Notifications

@Composable
private fun NotificationsCard(model: StatusModel, status: Status) {
    val context = LocalContext.current
    var testResult by remember { mutableStateOf<String?>(null) }
    var sendingTest by remember { mutableStateOf(false) }
    var confirmingMute by remember { mutableStateOf(false) }
    Column(Modifier.animateContentSize(smooth()).card(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardHeader("Notifications", Icons.Outlined.PhonelinkRing)
        Column {
            SettingRow("This phone") {
                when (val registration = model.registration) {
                    StatusModel.Registration.Pending -> Text("Registering…")
                    StatusModel.Registration.Registered -> TrailingIcon("Registered", Icons.Filled.CheckCircle, ink.green)
                    is StatusModel.Registration.Failed -> TrailingIcon("Failed: ${registration.message}", Icons.Filled.Warning, ink.orange)
                }
            }
            status.device?.let { device ->
                Separator()
                Toggle(
                    "Mute this phone",
                    device.muted,
                    { muted -> if (muted) confirmingMute = true else model.launch { setMuted(false) } },
                    Modifier.padding(vertical = 7.dp),
                    enabled = !model.isWorking,
                )
            }
            Separator()
            SettingRow("Devices registered") { Text("${status.devices}") }
        }
        if (model.notificationsAllowed == false) {
            ProminentButton(
                onClick = { Permissions.openNotificationSettings(context) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                color = ink.orange,
                large = true,
            ) {
                Symbol(Icons.Filled.NotificationsOff)
                Text("Notifications are off. Turn them on")
            }
        }
        GlassButton(
            onClick = {
                sendingTest = true
                model.launch {
                    testResult = sendTest()
                    sendingTest = false
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            enabled = !sendingTest,
            large = true,
        ) {
            if (sendingTest) Spinner() else Symbol(Icons.AutoMirrored.Filled.Send)
            Text("Send Test Notification")
        }
        testResult?.let { Text(it, color = ink.secondary, style = Type.caption) }
    }

    if (confirmingMute) {
        Confirm(
            title = "Mute this phone?",
            message = "It won't get stock alerts or warnings until you turn this off. Other phones still will.",
            action = "Mute",
            dismiss = { confirmingMute = false },
        ) { model.launch { setMuted(true) } }
    }
}

// Unregistered

/** Alerts and the alarm are this phone's own, so they wait until it has registered. */
@Composable
private fun UnregisteredCard(model: StatusModel) {
    Column(Modifier.animateContentSize(smooth()).card(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardHeader("Alerts", Icons.Outlined.NotificationsActive)
        when (val registration = model.registration) {
            StatusModel.Registration.Pending, StatusModel.Registration.Registered -> Row(
                Modifier.padding(vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spinner()
                Text("Registering this phone…")
            }
            is StatusModel.Registration.Failed -> Row(
                Modifier.padding(vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol(Icons.Filled.Warning, Type.subheadline, tint = ink.orange)
                Text("Couldn't register: ${registration.message}", color = ink.orange, style = Type.subheadline.weight(FontWeight.Medium))
            }
        }
        Text(
            "Each phone chooses its own models, areas and alarm hours once it's registered for notifications.",
            color = ink.secondary,
            style = Type.caption,
        )
    }
}

// Areas

/**
 * Each search the Worker runs, opening up to the stores it watches. The tick is whether this phone hears
 * about stock there.
 */
@Composable
private fun AreasCard(model: StatusModel, status: Status, finish: Finish) {
    val device = status.device
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    HapticOnChange(expanded) { _, _ -> HapticFeedbackType.SegmentTick }
    Column(Modifier.card(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardHeader("Areas", Icons.Outlined.PinDrop, "${status.storeCount} stores")
        Column {
            status.areas.forEachIndexed { index, area ->
                key(area.id) {
                    if (index > 0) Separator()
                    val open = area.id in expanded
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (device != null) AreaTick(model, area, area.search in device.areas, finish)
                        AreaRow(area, open, Modifier.weight(1f)) { expanded = if (open) expanded - area.id else expanded + area.id }
                    }
                    AnimatedVisibility(
                        open,
                        enter = fadeIn(smooth()) + expandVertically(smooth(), expandFrom = Alignment.Top),
                        exit = fadeOut(smooth()) + shrinkVertically(smooth(), shrinkTowards = Alignment.Top),
                    ) {
                        Column(Modifier.padding(bottom = 8.dp)) {
                            for (store in area.stores) StoreRow(store)
                        }
                    }
                }
            }
        }
        val ticks = if (device == null) "" else "Tick the areas this phone hears about. "
        val changing = if (status.isOwner) " Change them with PUT /config on the Worker." else ""
        Text("${ticks}Each area is one search on Apple.$changing", color = ink.secondary, style = Type.caption)
    }
}

/** Turning an area on is a tap; turning one off asks first, as for a model. */
@Composable
private fun AreaTick(model: StatusModel, area: Status.Area, on: Boolean, finish: Finish) {
    var confirmingOff by remember { mutableStateOf(false) }
    HapticOnChange(on) { _, now -> if (now) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff }
    AnimatedContent(
        on,
        Modifier
            .plainButton(enabled = !model.isWorking) {
                if (on) confirmingOff = true else model.launch { setArea(area.search, true) }
            }
            .alpha(if (model.isWorking) 0.5f else 1f)
            .semantics { contentDescription = "${area.name}, ${if (on) "alerts on" else "alerts off"}" },
        transitionSpec = {
            (fadeIn(snappy()) + scaleIn(snappy(), initialScale = 0.5f)) togetherWith
                (fadeOut(snappy()) + scaleOut(snappy(), targetScale = 0.5f))
        },
        contentAlignment = Alignment.Center,
        label = "area tick",
    ) { ticked ->
        if (ticked) Symbol(Icons.Filled.CheckCircle, Type.title3, tint = finish.accent)
        else Symbol(Icons.Outlined.RadioButtonUnchecked, Type.title3, tint = ink.secondary)
    }

    if (confirmingOff) {
        Confirm(
            title = "Turn off alerts in ${area.name}?",
            message = "This phone won't hear about stock at any of its ${area.stores.size} stores.",
            action = "Turn Off Alerts",
            dismiss = { confirmingOff = false },
        ) { model.launch { setArea(area.search, false) } }
    }
}

@Composable
private fun AreaRow(area: Status.Area, open: Boolean, modifier: Modifier = Modifier, toggle: () -> Unit) {
    val uncovered = area.stores.count { !it.covered }
    val turn by animateFloatAsState(if (open) 90f else 0f, smooth(), label = "chevron")
    Row(
        modifier
            .fillMaxWidth()
            .plainButton(onClick = toggle)
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) { stateDescription = if (open) "Expanded" else "Collapsed" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(area.name)
            Text("Near ${area.search}", color = ink.secondary, style = Type.caption)
        }
        if (uncovered > 0) {
            Symbol(Icons.Filled.Warning, Type.body, Modifier.semantics { contentDescription = "$uncovered not covered" }, tint = ink.yellow)
        }
        Text("${area.stores.size}", color = ink.secondary, style = Type.body.monospacedDigit())
        Symbol(Icons.Rounded.ChevronRight, Type.footnote.weight(FontWeight.SemiBold), Modifier.rotate(turn), tint = ink.tertiary)
    }
}

@Composable
private fun StoreRow(store: Status.Store) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(store.name, Modifier.weight(1f), style = Type.subheadline)
        if (store.covered) {
            Text(store.number, color = ink.tertiary, style = Type.caption.copy(fontFamily = FontFamily.Monospace))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol(Icons.Filled.Warning, Type.caption, tint = ink.yellow)
                Text("Not covered", color = ink.yellow, style = Type.caption.weight(FontWeight.Medium))
            }
        }
    }
}

// Rows

/** A setting and its value, for the rows inside a card. */
@Composable
private fun SettingRow(title: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 11.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title)
        Spacer(Modifier.width(16.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            CompositionLocalProvider(
                LocalContentColor provides ink.secondary,
                LocalTextStyle provides Type.body.copy(textAlign = TextAlign.End),
                content = value,
            )
        }
    }
}

/** Status text with its glyph after it, so the text lines up with the other values. */
@Composable
private fun TrailingIcon(text: String, icon: ImageVector, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f, fill = false))
        Symbol(icon, Type.footnote, tint = color)
    }
}

/** iOS's ProgressView, sized for a button's label. */
@Composable
private fun Spinner() = CircularProgressIndicator(Modifier.size(18.dp), color = LocalContentColor.current, strokeWidth = 2.dp)

/** iOS's confirmation dialog: the choice spelled out, with the destructive action in red. */
@Composable
private fun Confirm(title: String, message: String, action: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title, style = Type.headline) },
        text = { Text(message, color = ink.secondary, style = Type.subheadline) },
        confirmButton = {
            TextButton({
                dismiss()
                confirm()
            }) { Text(action, color = ink.red, style = Type.body.weight(FontWeight.SemiBold)) }
        },
        dismissButton = { TextButton(dismiss) { Text("Cancel", color = ink.blue, style = Type.body) } },
    )
}
