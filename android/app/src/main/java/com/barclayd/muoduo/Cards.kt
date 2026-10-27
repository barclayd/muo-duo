package com.barclayd.muoduo

import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SignalWifiBad
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import kotlin.math.roundToInt
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.snapshotter.MapSnapshotter

// MARK: - Health

private data class NoticeContent(val title: String, val detail: String, val icon: ImageVector, val color: Color)

/** Only shown when the checker can't be trusted, so the home screen stays calm when it can. */
@Composable
fun HealthBanner(status: Status, errorMessage: String?) {
    val state = status.state
    Notice(
        when {
            errorMessage != null -> NoticeContent("Can't reach the checker", errorMessage, Icons.Filled.SignalWifiBad, ink.orange)
            state is LiveState.Failing -> NoticeContent(
                "Apple isn't answering",
                state.error ?: "The last ${status.health.consecutiveFailures} checks failed.",
                Icons.Filled.Warning,
                ink.orange,
            )
            state == LiveState.Stale -> NoticeContent(
                "Results may be stale",
                "Apple is answering, but even iPhone 17 shows no stock nearby.",
                Icons.AutoMirrored.Filled.Help,
                ink.yellow,
            )
            else -> null
        },
    )
}

/**
 * Apple pushed back, so checks are slower for a couple of minutes. Worth knowing but not urgent, so it
 * sits at the bottom of the home screen.
 */
@Composable
fun SlowedBanner(status: Status) {
    val state = status.state
    Notice(
        (state as? LiveState.Slowed)?.let {
            NoticeContent(
                "Slowed to every ${status.pace.intervalSeconds}s",
                "${it.reason}. Speeds up again after 2 minutes without pushback.",
                Icons.Filled.HourglassTop,
                ink.yellow,
            )
        },
    )
}

@Composable
private fun Notice(content: NoticeContent?) {
    val shown = rememberLast(content)
    AnimatedVisibility(
        visible = content != null,
        enter = fadeIn(smooth()) + expandVertically(smooth()),
        exit = fadeOut(smooth()) + shrinkVertically(smooth()),
    ) {
        if (shown == null) return@AnimatedVisibility
        val pulse = rememberInfiniteTransition(label = "pulse")
        val alpha by pulse.animateFloat(1f, 0.35f, infiniteRepeatable(tween(1_000, easing = EaseInOut), RepeatMode.Reverse), label = "alpha")
        Row(Modifier.blurReplace(this).card(shown.color), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Symbol(shown.icon, Type.title2, Modifier.graphicsLayer { this.alpha = alpha }, tint = shown.color)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(shown.title, style = Type.headline)
                Text(shown.detail, style = Type.subheadline, color = ink.secondary)
            }
        }
    }
}

// MARK: - In stock

/**
 * Straight under the colour picker the moment anything is in stock: which size and colour, at which
 * stores, and one tap to buy it.
 */
@Composable
fun InStockCard(model: StatusModel, status: Status, finish: Finish) {
    val shown = rememberLast(status.takeIf { it.sightings.isNotEmpty() })
    AnimatedVisibility(
        visible = status.sightings.isNotEmpty(),
        enter = fadeIn(smooth()) + scaleIn(smooth(), initialScale = 0.96f) + expandVertically(smooth()),
        exit = fadeOut(smooth()) + scaleOut(smooth(), targetScale = 0.96f) + shrinkVertically(smooth()),
    ) {
        val status = shown ?: return@AnimatedVisibility
        val now by rememberNow(1_000)
        Column(
            Modifier
                .blurReplace(this)
                .fillMaxWidth()
                .glass(RoundedCornerShape(32.dp), ink.green.copy(alpha = 0.3f))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveDot(ink.green, model.pulses, 9.dp)
                Text("IN STOCK NOW", color = ink.green, style = Type.footnote.weight(FontWeight.Bold).copy(letterSpacing = 0.8.sp))
                Spacer(Modifier.weight(1f))
                val stores = status.sightings.map { it.storeNumber }.toSet().size
                Text(if (stores == 1) "1 store" else "$stores stores", color = ink.green, style = Type.footnote.weight(FontWeight.SemiBold))
            }
            for ((index, group) in groups(status, finish).withIndex()) {
                if (index > 0) Separator()
                val phone = status.model(group.key) ?: continue
                InStockModel(model, status, phone, group.value, now)
            }
        }
    }
}

/** One group per model: the selected colour first, then in catalogue order. */
private fun groups(status: Status, finish: Finish): List<Map.Entry<String, List<Status.Sighting>>> {
    val order = status.models.map { it.part }
    return status.sightings.groupBy { it.part }.entries.sortedWith(
        compareBy({ if (status.model(it.key)?.finish == finish) 0 else 1 }, { order.indexOf(it.key).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }),
    )
}

/** One size and colour that's in stock: the phone, where it is, and a Buy button. */
@Composable
private fun InStockModel(model: StatusModel, status: Status, phone: Status.Model, sightings: List<Status.Sighting>, now: java.time.Instant) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(phone.capacity, style = TextStyle(fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Swatch(phone.finish ?: Finish.NightSky, 16)
                    Text(phone.colour, style = Type.headline)
                }
            }
            // Apple's shot of the phone in this colour. Like the hero, only the middle, where the phone is, is laid out.
            phone.finish?.let(model::image)?.let { image ->
                Box(Modifier.size(128.dp, 80.dp), contentAlignment = Alignment.Center) {
                    Image(image, null, Modifier.requiredSize(128.dp, (128f * 1112 / 940).dp), contentScale = ContentScale.Fit)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (sighting in sightings) StoreLine(status, sighting, now)
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ProminentButton(
                onClick = { AppleStore.open(context, phone.url) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Buy in Apple Store. Opens ${phone.name} on apple.com" },
                color = ink.green,
                large = true,
            ) {
                Symbol(Icons.Filled.ShoppingBag, Type.headline)
                Text("Buy in Apple Store", style = Type.headline, modifier = Modifier.padding(vertical = 6.dp))
            }
            Text(
                if (sightings.size == 1) "Choose Apple ${sightings[0].storeName} for pickup at checkout." else "Choose one of these stores for pickup at checkout.",
                color = ink.secondary,
                style = Type.caption,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StoreLine(status: Status, sighting: Status.Sighting, now: java.time.Instant) {
    val episode = status.lastSeen.firstOrNull { it.part == sighting.part && it.storeNumber == sighting.storeNumber }
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Symbol(Icons.Filled.LocationOn, Type.title3, Modifier.padding(top = 1.dp), tint = ink.green)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Apple ${sighting.storeName}", style = Type.headline)
            Text(place(status, sighting.storeNumber), color = ink.secondary, style = Type.subheadline)
        }
        Spacer(Modifier.width(0.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(sighting.quote, color = ink.green, style = Type.subheadline.weight(FontWeight.SemiBold))
            if (episode != null) {
                Text("for ${timer(episode.from, now)}", color = ink.secondary, style = Type.caption.monospacedDigit())
            }
        }
    }
}

/** "Bristol · South West": the store's city and area, unless they're the same. */
fun place(status: Status, storeNumber: String): String {
    val area = status.area(storeNumber)
    val city = area?.stores?.firstOrNull { it.number == storeNumber }?.city
    return listOfNotNull(city, area?.name).uniqued().joinToString(" · ")
}

// MARK: - Last seen

/** When an iPhone Duo in the selected colour was last in stock. */
@Composable
fun LastSeenCard(status: Status, finish: Finish) {
    val now by rememberNow(30_000)
    AnimatedContent(
        targetState = finish,
        modifier = Modifier.card(),
        transitionSpec = { fadeIn(finishChange()) togetherWith fadeOut(finishChange()) },
        label = "last seen",
    ) { finish ->
        val latest = status.lastSeen.firstOrNull { status.model(it.part)?.finish == finish }
        Column(Modifier.fillMaxWidth().blurReplace(this), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardHeader("Last seen in ${finish.title}", Icons.Outlined.Visibility)
            val big = Type.largeTitle.weight(FontWeight.Bold)
            if (latest != null) {
                Text(relative(latest.to, now), style = big)
                Text(
                    "${status.model(latest.part)?.capacity ?: latest.part} at ${place(status, latest)} · ${lasted(latest)}",
                    color = ink.secondary,
                    style = Type.subheadline,
                )
            } else {
                Text("Not yet", style = big)
                val since = status.trackingSince
                Text(
                    if (since != null) "No ${finish.title} iPhone Duo at these ${status.storeCount} stores since tracking began ${relative(since, now)}."
                    else "No ${finish.title} iPhone Duo at these ${status.storeCount} stores yet.",
                    color = ink.secondary,
                    style = Type.subheadline,
                )
            }
        }
    }
}

private fun place(status: Status, episode: Status.Episode): String {
    val area = status.area(episode.storeNumber)
    return if (area == null || area.name == episode.storeName) episode.storeName else "${episode.storeName}, ${area.name}"
}

private fun lasted(episode: Status.Episode): String {
    val duration = Duration.between(episode.from, episode.to)
    return if (duration.seconds < 60) "briefly" else "for ${hoursAndMinutes(duration)}"
}

// MARK: - Stores

@Immutable
data class Pin(val store: Status.Store, val area: String, val position: LatLng) {
    val id get() = store.number
}

val Status.pins: List<Pin>
    get() = areas.flatMap { area ->
        area.stores.mapNotNull { store ->
            val latitude = store.latitude ?: return@mapNotNull null
            val longitude = store.longitude ?: return@mapNotNull null
            Pin(store, area.name, LatLng(latitude, longitude))
        }
    }

/** OpenFreeMap's muted styles, light or dark to match the finish. They need no key. */
val Finish.mapStyle get() = if (dark) "https://tiles.openfreemap.org/styles/dark" else "https://tiles.openfreemap.org/styles/positron"

/** A store on the map. The pins in an area ripple each time the Worker asks Apple about it. */
@Composable
fun StorePin(pin: Pin, status: Status, color: Color, pulse: Int, modifier: Modifier = Modifier, selected: Boolean = false) {
    val tint = when {
        !pin.store.covered -> ink.gray
        status.inStock(pin.store.number).isEmpty() -> color
        else -> ink.green
    }
    val scale by animateFloatAsState(if (selected) 1.6f else 1f, bouncy(), label = "pin")
    Box(modifier.size(12.dp).graphicsLayer { scaleX = scale; scaleY = scale }) {
        Ripple(tint, pulse, Modifier.matchParentSize(), reach = 3f, durationMillis = 1_400)
        Box(
            Modifier
                .matchParentSize()
                .dropShadow(CircleShape, Shadow(radius = 2.dp, color = Color.Black.copy(alpha = 0.3f), offset = DpOffset(0.dp, 1.dp)))
                .background(tint, CircleShape)
                .border(2.dp, Color.White, CircleShape),
        )
    }
}

@Composable
fun StoresCard(model: StatusModel, status: Status, finish: Finish, open: (String?) -> Unit) {
    Column(Modifier.card(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CardHeader("Stores", Icons.Filled.PinDrop, "${status.storeCount}")

        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(18.dp))
                .plainButton { open(null) }
                .semantics { contentDescription = "Open the store map" },
        ) {
            StoresSnapshot(status, finish) { pin ->
                StorePin(pin, status, finish.accent, model.areaPulses[pin.area] ?: 0)
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .glass(RoundedCornerShape(percent = 50))
                    .padding(10.dp),
            ) {
                Symbol(Icons.Filled.OpenInFull, Type.footnote.weight(FontWeight.SemiBold))
            }
        }

        Column {
            for ((index, area) in status.areas.withIndex()) {
                if (index > 0) Separator(Modifier.padding(start = 34.dp))
                AreaRow(model, status, finish, area) { open(area.id) }
            }
        }
    }
}

@Composable
private fun AreaRow(model: StatusModel, status: Status, finish: Finish, area: Status.Area, onClick: () -> Unit) {
    val missing = area.stores.count { !it.covered }
    val inStock = area.stores.count { status.inStock(it.number).isNotEmpty() }
    Row(
        Modifier.fillMaxWidth().plainButton(onClick = onClick).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            LiveDot(if (inStock > 0) ink.green else finish.accent, model.areaPulses[area.name] ?: 0, 8.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(area.name, style = Type.subheadline.weight(FontWeight.SemiBold))
            Text(
                area.stores.map { it.name }.joinedAsList(),
                color = ink.secondary,
                style = Type.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (missing > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Symbol(Icons.Filled.Warning, Type.caption, tint = ink.yellow)
                Text("$missing", color = ink.yellow, style = Type.caption)
            }
        }
        Text(
            if (inStock > 0) "$inStock in stock" else "${area.stores.size}",
            color = if (inStock > 0) ink.green else ink.secondary,
            style = Type.subheadline.weight(if (inStock > 0) FontWeight.SemiBold else FontWeight.Normal).monospacedDigit(),
        )
        Symbol(Icons.Rounded.ChevronRight, Type.caption.weight(FontWeight.SemiBold), tint = ink.tertiary)
    }
}

private class Snapshot(val image: ImageBitmap, val width: Int, val points: Map<String, Offset>)

/**
 * A still map of every store, with live pins over it. The card only ever shows the whole region and
 * isn't interactive, so a picture of the map is all it needs, and far lighter than a live one.
 */
@Composable
private fun StoresSnapshot(status: Status, finish: Finish, pin: @Composable (Pin) -> Unit) {
    val context = LocalContext.current
    val pins = remember(status.areas) { status.pins }
    BoxWithConstraints(Modifier.fillMaxWidth().height(220.dp).background(ink.secondary.copy(alpha = 0.12f))) {
        val density = LocalDensity.current
        val width = maxWidth.value.roundToInt()
        val height = maxHeight.value.roundToInt()
        var snapshot by remember { mutableStateOf<Snapshot?>(null) }
        DisposableEffect(width, height, finish, pins) {
            if (pins.isEmpty() || width == 0 || height == 0) return@DisposableEffect onDispose {}
            MapLibre.getInstance(context)
            val bounds = if (pins.size == 1) {
                LatLngBounds.from(pins[0].position.latitude + 0.02, pins[0].position.longitude + 0.02, pins[0].position.latitude - 0.02, pins[0].position.longitude - 0.02)
            } else {
                LatLngBounds.Builder().includes(pins.map { it.position }).build()
            }
            val inset = (28 * density.density).roundToInt()
            val options = MapSnapshotter.Options(width, height)
                .withStyle(finish.mapStyle)
                .withRegion(bounds)
                .withPadding(inset, inset, inset, inset)
                .withPixelRatio(density.density)
                .withLogo(false)
            val snapshotter = MapSnapshotter(context, options)
            snapshotter.start(
                { result ->
                    val bitmap = result.bitmap
                    snapshot = Snapshot(
                        bitmap.asImageBitmap(),
                        bitmap.width,
                        pins.associate { pin -> pin.id to result.pixelForLatLng(pin.position).let { Offset(it.x, it.y) } },
                    )
                },
                { error -> Log.w("MuoDuo", "Map snapshot failed: $error") },
            )
            onDispose { snapshotter.cancel() }
        }
        Crossfade(snapshot, label = "map") { snapshot ->
            if (snapshot == null) return@Crossfade
            val scale = constraints.maxWidth.toFloat() / snapshot.width
            Box(Modifier.matchParentSize()) {
                Image(snapshot.image, null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                for (p in pins) {
                    val point = snapshot.points[p.id] ?: continue
                    val half = with(density) { 6.dp.toPx() }
                    Box(Modifier.offset { IntOffset((point.x * scale - half).roundToInt(), (point.y * scale - half).roundToInt()) }) { pin(p) }
                }
            }
        }
    }
}

