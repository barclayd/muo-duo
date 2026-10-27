@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeApi::class)

package com.barclayd.muoduo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch

/** iOS's navigation bar: as tall as its buttons. */
val BarHeight = 44.dp

/**
 * One screen of the app, like a page in a SwiftUI NavigationStack: the drifting backdrop, a column of
 * cards scrolling over it, and glass buttons along the top that the cards soften under. A [title] is
 * shown large at the top of the content and moves into the bar as it scrolls away, unless [inlineTitle].
 */
@Composable
fun Page(
    finish: Finish,
    title: String? = null,
    inlineTitle: Boolean = false,
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
    onRefresh: (suspend () -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val haze = rememberHazeState()
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val top = safe.calculateTopPadding()
    var bottomBarHeight by remember { mutableIntStateOf(0) }
    val large = title != null && !inlineTitle
    val titleInBar = title != null && (inlineTitle || scroll.value > with(density) { 40.dp.toPx() })
    val titleAlpha by animateFloatAsState(if (titleInBar) 1f else 0f, tween(180), label = "title")

    CompositionLocalProvider(LocalHaze provides haze) {
        Box(Modifier.fillMaxSize().background(ink.background)) {
            Backdrop(finish, Modifier.matchParentSize().hazeSource(haze, zIndex = 0f))

            val body: @Composable () -> Unit = {
                Column(
                    Modifier
                        .fillMaxSize()
                        .hazeSource(haze, zIndex = 1f)
                        .verticalScroll(scroll)
                        .padding(
                            start = 16.dp + safe.calculateStartPadding(direction),
                            end = 16.dp + safe.calculateEndPadding(direction),
                            top = top + BarHeight + 8.dp,
                            bottom = safe.calculateBottomPadding() + with(density) { bottomBarHeight.toDp() } + 16.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (large) {
                        Text(title, Modifier.padding(horizontal = 4.dp), style = Type.largeTitle.weight(FontWeight.Bold))
                    }
                    content()
                }
            }
            if (onRefresh != null) {
                val scope = rememberCoroutineScope()
                val state = rememberPullToRefreshState()
                var refreshing by remember { mutableStateOf(false) }
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = {
                        scope.launch {
                            refreshing = true
                            try { onRefresh() } finally { refreshing = false }
                        }
                    },
                    state = state,
                    indicator = {
                        PullToRefreshDefaults.Indicator(
                            state = state,
                            isRefreshing = refreshing,
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = top + BarHeight),
                        )
                    },
                    content = { body() },
                )
            } else {
                body()
            }

            EdgeBlur(haze)
            TopBar(title, titleAlpha, leading, trailing)

            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .onSizeChanged { bottomBarHeight = it.height },
            ) { bottomBar() }
        }
    }
}

/** iOS's scroll edge effect: content blurs and fades as it passes under the status bar and buttons. */
@Composable
fun EdgeBlur(haze: HazeState, modifier: Modifier = Modifier) {
    val top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    val edge = ink.background
    Box(
        modifier
            .fillMaxWidth()
            .height(top + BarHeight + 20.dp)
            .hazeBlur(
                HazeInput.Sources(haze),
                HazeBlurStyle {
                    blurRadius(12.dp)
                    noiseFactor(0f)
                    backgroundColor(edge)
                    // One blur faded out, rather than Haze's progressive blur, which costs several times as much a frame.
                    mask(Brush.verticalGradient(0.5f to Color.Black, 1f to Color.Transparent))
                },
            ),
    )
}

/** The bar along the top of a page: buttons either side, and the title in the middle while [titleAlpha] is up. */
@Composable
fun TopBar(
    title: String?,
    titleAlpha: Float,
    leading: @Composable RowScope.() -> Unit,
    trailing: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp)
            .height(BarHeight),
    ) {
        if (title != null) {
            Text(
                title,
                Modifier.align(Alignment.Center).padding(horizontal = 100.dp).graphicsLayer { alpha = titleAlpha },
                style = Type.headline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.align(Alignment.CenterStart), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { leading() }
        Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { trailing() }
    }
}

/** A bar button on its own circle of glass, which swells while pressed. */
@Composable
fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(BarHeight)
            .glass(Capsule, interactionSource = interactions)
            .clickable(interactions, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Symbol(icon, Type.body.weight(FontWeight.Medium))
    }
}

/** Bar buttons sharing one capsule of glass, as iOS groups them. */
@Composable
fun BarGroup(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.height(BarHeight).glass(Capsule).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

/** A button in a [BarGroup]. */
@Composable
fun BarIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp, BarHeight)
            .plainButton(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Symbol(icon, Type.body.weight(FontWeight.Medium))
    }
}

/**
 * Rows of a list that grows at the top, like the stock log. Rows already there when the list first
 * appears stay put; rows that arrive later slide in from the top, as iOS's do.
 */
class Arrivals {
    internal val seen = HashSet<Any>()
    internal var settled = false

    @Composable
    fun Row(id: Any, content: @Composable () -> Unit) = key(id) {
        val state = remember { MutableTransitionState(!settled || id in seen).apply { targetState = true } }
        seen += id
        AnimatedVisibility(
            state,
            enter = slideInVertically(snappy()) { -it } + fadeIn(snappy()) + expandVertically(snappy(), expandFrom = Alignment.Top),
            exit = ExitTransition.None,
        ) { content() }
    }
}

@Composable
fun rememberArrivals(): Arrivals {
    val arrivals = remember { Arrivals() }
    SideEffect { arrivals.settled = true }
    return arrivals
}
