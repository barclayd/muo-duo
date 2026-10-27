package com.barclayd.muoduo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.max
import kotlin.math.roundToInt
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView

/** Every watched store, full screen. Area chips fly the camera between London, the south and Manchester. */
@Composable
fun StoreMap(model: StatusModel, finish: Finish, initialFocus: String?, close: () -> Unit) {
    val status = model.status ?: return
    val pins = remember(status.areas) { status.pins }
    var focus by rememberSaveable { mutableStateOf(initialFocus) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val haze = rememberHazeState()
    val density = LocalDensity.current
    val top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    var bottomInset by remember { mutableIntStateOf(0) }
    val holder = rememberMapHolder()
    val map = holder.map

    fun region(area: String?) = region(pins.filter { area == null || status.area(it.id)?.id == area }.map { it.position })

    LaunchedEffect(map, finish) { map?.setStyle(finish.mapStyle) }
    LaunchedEffect(map) {
        val map = map ?: return@LaunchedEffect
        map.addOnMapClickListener { selected = null; true }
        holder.view.doOnLayout { region(focus)?.let { map.moveCamera(CameraUpdateFactory.newLatLngBounds(it, 0)) } }
    }
    // MapLibre's own controls sit clear of the bar along the top and the glass along the bottom.
    val legal = ink.secondary.toArgb()
    LaunchedEffect(map, bottomInset, legal) {
        val ui = map?.uiSettings ?: return@LaunchedEffect
        // Grey, like MapKit's Legal link, rather than MapLibre's blue.
        ui.setAttributionTintColor(legal)
        with(density) {
            ui.setCompassMargins(0, (top + BarHeight + 8.dp).roundToPx(), 16.dp.roundToPx(), 0)
            ui.setAttributionMargins(12.dp.roundToPx(), 0, 0, bottomInset + 4.dp.roundToPx())
        }
    }
    HapticOnChange(selected) { _, _ -> HapticFeedbackType.SegmentTick }

    CompositionLocalProvider(LocalHaze provides haze) {
        Box(Modifier.fillMaxSize().background(ink.background)) {
            AndroidView({ holder.view }, Modifier.fillMaxSize().hazeSource(haze, zIndex = 0f))

            if (map != null) {
                Box(Modifier.fillMaxSize().hazeSource(haze, zIndex = 1f)) {
                    val half = with(density) { 18.dp.roundToPx() }
                    for (pin in pins) key(pin.id) {
                        val isSelected = selected == pin.id
                        Box(
                            Modifier
                                .offset {
                                    holder.camera // Placed again each time the camera moves.
                                    val point = map.projection.toScreenLocation(pin.position)
                                    IntOffset(point.x.roundToInt() - half, point.y.roundToInt() - half)
                                }
                                .zIndex(if (isSelected) 1f else 0f)
                                .size(36.dp)
                                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { selected = pin.id }
                                .semantics {
                                    contentDescription = "Apple ${pin.store.name}"
                                    this.selected = isSelected
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            StorePin(pin, status, finish.accent, model.areaPulses[pin.area] ?: 0, selected = isSelected)
                        }
                    }
                }
            }

            EdgeBlur(haze)
            TopBar(
                title = status.areas.firstOrNull { it.id == focus }?.name ?: "${status.storeCount} Apple Stores",
                titleAlpha = 1f,
                leading = { BarButton(Icons.Rounded.Close, "Close", close) },
                trailing = {},
            )

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { bottomInset = it.height }
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val pin = pins.firstOrNull { it.id == selected }
                AnimatedVisibility(
                    pin != null,
                    enter = slideInVertically(smooth()) { it } + fadeIn(smooth()),
                    exit = slideOutVertically(smooth()) { it } + fadeOut(smooth()),
                ) {
                    rememberLast(pin)?.let { StoreDetail(it, status, Modifier.padding(horizontal = 16.dp).animateContentSize(smooth())) }
                }
                AreaChips(status, finish, focus) { area ->
                    focus = area
                    selected = null
                    region(area)?.let { map?.animateCamera(CameraUpdateFactory.newLatLngBounds(it, 0), 1_200) }
                }
            }
        }
    }
}

/**
 * The [coordinates], with room around the edges for the pins and the glass over the map. The same
 * region the iPhone app fits, so both show the same view.
 */
private fun region(coordinates: List<LatLng>): LatLngBounds? {
    if (coordinates.isEmpty()) return null
    val minLat = coordinates.minOf { it.latitude }
    val maxLat = coordinates.maxOf { it.latitude }
    val minLon = coordinates.minOf { it.longitude }
    val maxLon = coordinates.maxOf { it.longitude }
    val latitude = (minLat + maxLat) / 2 - (maxLat - minLat) * 0.12
    val longitude = (minLon + maxLon) / 2
    val latSpan = max(0.04, (maxLat - minLat) * 1.6)
    val lonSpan = max(0.04, (maxLon - minLon) * 1.2)
    return LatLngBounds.from(latitude + latSpan / 2, longitude + lonSpan / 2, latitude - latSpan / 2, longitude - lonSpan / 2)
}

/** A live MapLibre map, with [camera] ticking over as it moves so pins drawn over it can follow. */
@Stable
private class MapHolder(val view: MapView) {
    var map by mutableStateOf<MapLibreMap?>(null)
    var camera by mutableIntStateOf(0)
}

@Composable
private fun rememberMapHolder(): MapHolder {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val holder = remember {
        MapLibre.getInstance(context)
        // A TextureView, rather than a SurfaceView, so the map can be blurred under the glass.
        val options = MapLibreMapOptions.createFromAttributes(context).textureMode(true).logoEnabled(false)
        MapHolder(MapView(context, options).apply { onCreate(null) })
    }
    DisposableEffect(holder) {
        val view = holder.view
        var state = Lifecycle.State.CREATED
        fun moveTo(target: Lifecycle.State) {
            if (state < Lifecycle.State.STARTED && target >= Lifecycle.State.STARTED) view.onStart()
            if (state < Lifecycle.State.RESUMED && target >= Lifecycle.State.RESUMED) view.onResume()
            if (state >= Lifecycle.State.RESUMED && target < Lifecycle.State.RESUMED) view.onPause()
            if (state >= Lifecycle.State.STARTED && target < Lifecycle.State.STARTED) view.onStop()
            state = target
        }
        val observer = LifecycleEventObserver { _, event -> moveTo(maxOf(event.targetState, Lifecycle.State.CREATED)) }
        val moving = MapView.OnCameraIsChangingListener { holder.camera++ }
        val moved = MapView.OnCameraDidChangeListener { holder.camera++ }
        lifecycle.addObserver(observer)
        view.addOnCameraIsChangingListener(moving)
        view.addOnCameraDidChangeListener(moved)
        view.getMapAsync { holder.map = it }
        onDispose {
            view.removeOnCameraIsChangingListener(moving)
            view.removeOnCameraDidChangeListener(moved)
            lifecycle.removeObserver(observer)
            moveTo(Lifecycle.State.CREATED)
            view.onDestroy()
        }
    }
    return holder
}

@Composable
private fun AreaChips(status: Status, finish: Finish, focus: String?, select: (String?) -> Unit) {
    // A little room above and below, so chips swelling under a finger aren't clipped.
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") { Chip("All", focus == null, finish) { select(null) } }
        items(status.areas, key = { it.id }) { area -> Chip(area.name, focus == area.id, finish) { select(area.id) } }
    }
}

@Composable
private fun Chip(name: String, selected: Boolean, finish: Finish, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    Text(
        name,
        Modifier
            .glass(Capsule, if (selected) finish.accent.copy(alpha = 0.4f) else null, interactions)
            .clickable(interactions, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        color = if (selected) ink.primary else ink.secondary,
        style = Type.subheadline.weight(if (selected) FontWeight.SemiBold else FontWeight.Medium),
        maxLines = 1,
    )
}

/** The selected store: what's there now, and when stock was last seen there. */
@Composable
private fun StoreDetail(pin: Pin, status: Status, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val inStock = status.inStock(pin.store.number)
    val seen = status.lastSeen.firstOrNull { it.storeNumber == pin.store.number }
    Column(
        modifier.fillMaxWidth().glass(RoundedCornerShape(26.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row {
            Column(Modifier.weight(1f).alignByBaseline(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Apple ${pin.store.name}", style = Type.title3.weight(FontWeight.SemiBold))
                Text(
                    listOfNotNull(pin.store.city, pin.area).uniqued().joinToString(" · "),
                    color = ink.secondary,
                    style = Type.subheadline,
                )
            }
            Text(
                pin.store.number,
                Modifier.alignByBaseline(),
                color = ink.tertiary,
                style = Type.caption.copy(fontFamily = FontFamily.Monospace),
            )
        }
        when {
            !pin.store.covered -> Label(
                "Apple left this store out of its last answer, so it isn't being watched right now.",
                Icons.Filled.Warning,
                ink.yellow,
                Type.footnote,
            )
            inStock.isNotEmpty() -> for (sighting in inStock) {
                val phone = status.model(sighting.part)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(phone?.name ?: sighting.part, Icons.Filled.Verified, ink.green, Type.subheadline.weight(FontWeight.SemiBold), Modifier.weight(1f))
                    if (phone != null) {
                        ProminentButton(onClick = { AppleStore.open(context, phone.url) }, color = ink.green) { Text("Buy") }
                    }
                }
            }
            seen != null -> {
                val now by rememberNow(1_000)
                Label(
                    "${status.model(seen.part)?.name ?: seen.part} last seen ${relative(seen.to, now)}",
                    Icons.Outlined.Visibility,
                    ink.secondary,
                    Type.subheadline,
                )
            }
            else -> Label("No iPhone Duo seen here yet", Icons.Outlined.VisibilityOff, ink.secondary, Type.subheadline)
        }
    }
}

/** SwiftUI's Label: a symbol beside the first line of the text, both in [color]. */
@Composable
private fun Label(text: String, icon: ImageVector, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
    val line = with(LocalDensity.current) { style.lineHeight.toDp() }
    Row(modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.height(line), contentAlignment = Alignment.Center) { Symbol(icon, style, tint = color) }
        Text(text, color = color, style = style)
    }
}
