package com.barclayd.muoduo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Text
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val LiveState.color: Color
    @Composable @ReadOnlyComposable get() = when (this) {
        LiveState.Live -> ink.green
        LiveState.Paused -> ink.gray
        is LiveState.Failing -> ink.orange
        LiveState.Stale, is LiveState.Slowed -> ink.yellow
    }

/**
 * "LIVE", with a dot that ripples on every check, for as long as the Worker is answering and checking
 * Apple. Anything else inverts it into a solid pill saying what's wrong.
 */
@Composable
fun LiveBadge(model: StatusModel, status: Status) {
    val problem = problem(model, status)
    val foreground by animateColorAsState(if (problem == null) ink.primary else ink.background, smooth(), label = "badge")
    // Solid rather than tinted glass, which would wash out the orange.
    val solid by animateFloatAsState(if (problem == null) 0f else 1f, smooth(), label = "badge")
    val primary = ink.primary
    Row(
        modifier = Modifier
            .height(44.dp)
            .glass(Capsule)
            .drawBehind { drawRoundRect(primary.copy(alpha = solid), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2)) }
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = problem,
            transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) using SizeTransform(clip = false) },
            contentAlignment = Alignment.Center,
            label = "badge",
        ) { problem ->
            if (problem == null) {
                LiveDot(ink.green, model.pulses)
            } else {
                // The only colour: orange for something wrong, none for a deliberate pause.
                Symbol(problem.icon, Type.caption2, tint = if (problem == Problem.Paused) ink.background else ink.orange)
            }
        }
        Text(
            problem?.label ?: "LIVE",
            color = foreground,
            style = Type.footnote.weight(FontWeight.Bold).copy(letterSpacing = 0.8.sp),
        )
    }
}

private enum class Problem(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Offline("OFFLINE", Icons.Filled.WifiOff),
    Paused("PAUSED", Icons.Filled.Pause),
    Failing("FAILING", Icons.Filled.PriorityHigh),
    Stopped("STOPPED", Icons.Filled.Stop),
    Stale("STALE", Icons.Filled.QuestionMark),
}

/** Worst first. Slowed still counts as live: checks are getting through, just less often. */
private fun problem(model: StatusModel, status: Status): Problem? = when {
    model.errorMessage != null -> Problem.Offline
    status.config.paused -> Problem.Paused
    status.health.consecutiveFailures > 0 -> Problem.Failing
    stopped(model, status) -> Problem.Stopped
    status.resultsMayBeStale -> Problem.Stale
    else -> null
}

/**
 * The Worker answered, but its next check is over 2 minutes overdue or it has none. The alarm is cleared
 * while a check runs, so then it goes by the last check, with room for a slowed pace.
 */
private fun stopped(model: StatusModel, status: Status): Boolean {
    val receivedAt = model.receivedAt ?: return true
    val due = status.nextCheckAt ?: status.health.lastCheckAt ?: return true
    return java.time.Duration.between(due, receivedAt).seconds > 120
}

@Composable
fun LiveDot(color: Color, trigger: Int, size: Dp = 8.dp) {
    Box(Modifier.size(size)) {
        Ripple(color, trigger, reach = 3.2f, modifier = Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(color, CircleShape))
    }
}

/** A ring of colour that swells and fades each time [trigger] changes. */
@Composable
fun Ripple(color: Color, trigger: Int, modifier: Modifier = Modifier, reach: Float = 2.6f, durationMillis: Int = 900) {
    val progress = remember { Animatable(1f) }
    val initial = remember { trigger }
    LaunchedEffect(trigger) {
        if (trigger == initial) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis, easing = EaseInOut))
    }
    Box(
        modifier
            .graphicsLayer {
                val p = progress.value
                scaleX = 1 + (reach - 1) * p
                scaleY = scaleX
                alpha = 0.6f * (1 - p)
            }
            .background(color, CircleShape),
    )
}

/**
 * Rings that spread out from behind the phone, one per check, over a slowly breathing glow. They
 * overlap, so each is its own ring rather than one restarting animation.
 */
@Composable
fun Radar(color: Color, trigger: Int, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val rings = remember { mutableStateListOf<Int>() }
    val scope = rememberCoroutineScope()
    val initial = remember { trigger }
    LaunchedEffect(trigger) {
        if (trigger == initial || reduceMotion) return@LaunchedEffect
        rings += trigger
        scope.launch {
            delay(RING_MILLIS + 100L)
            rings -= trigger
        }
    }
    val breath = rememberInfiniteTransition(label = "radar")
    val swell by breath.animateFloat(0f, 1f, infiniteRepeatable(tween(2_600, easing = EaseInOut), RepeatMode.Reverse), label = "swell")

    Box(modifier.aspectRatio(1f)) {
        Canvas(Modifier.matchParentSize()) {
            val radius = min(size.width, size.height) / 2
            for (scale in listOf(0.42f, 0.68f, 0.94f)) {
                drawCircle(color.copy(alpha = 0.16f), radius * scale - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
            }
        }
        Canvas(
            Modifier.matchParentSize().graphicsLayer {
                val scale = if (reduceMotion) 1f else 0.9f + 0.16f * swell
                scaleX = scale
                scaleY = scale
                alpha = 0.7f + 0.3f * swell
            },
        ) {
            val radius = min(size.width, size.height) / 2
            // The glow fades out by 190 of the 200 points to the edge.
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.42f), color.copy(alpha = 0f)), center, radius * 0.95f), radius)
        }
        for (ring in rings) key(ring) { Ring(color, Modifier.matchParentSize()) }
    }
}

private const val RING_MILLIS = 2_600

@Composable
private fun Ring(color: Color, modifier: Modifier) {
    val spread = remember { Animatable(0f) }
    LaunchedEffect(Unit) { spread.animateTo(1f, tween(RING_MILLIS, easing = EaseOut)) }
    Canvas(
        modifier.graphicsLayer {
            val scale = 0.3f + 0.75f * spread.value
            scaleX = scale
            scaleY = scale
            alpha = 0.9f * (1 - spread.value)
        },
    ) {
        val radius = min(size.width, size.height) / 2
        drawCircle(color.copy(alpha = 0.08f), radius)
        drawCircle(color, radius - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
    }
}

/** A glass bar along the bottom showing each check as it happens. Tapping it opens the live activity. */
@Composable
fun Ticker(model: StatusModel, status: Status, finish: Finish, open: () -> Unit) {
    val check = model.liveChecks.firstOrNull()
    val paused = status.config.paused
    val interactions = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = 4.dp)
            .fillMaxWidth()
            .glass(Capsule, interactionSource = interactions)
            .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(interactions, indication = null, role = Role.Button, onClickLabel = "Shows live activity", onClick = open),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                if (paused) Symbol(Icons.Filled.Pause, Type.title3, tint = finish.accent)
                else Radiowaves(finish.accent, animating = status.state == LiveState.Live)
            }

            // The old line slides out as the new one slides in, so this is clipped to the text, not the capsule.
            AnimatedContent(
                targetState = paused to check,
                contentKey = { (paused, check) -> if (paused) "paused" else check?.id },
                modifier = Modifier.weight(1f).clipToBounds(),
                transitionSpec = {
                    (slideInVertically(snappy()) { it } + fadeIn(snappy())) togetherWith
                        (slideOutVertically(snappy()) { -it } + fadeOut(snappy())) using SizeTransform(clip = false)
                },
                contentAlignment = Alignment.CenterStart,
                label = "ticker",
            ) { (paused, check) ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    when {
                        paused -> {
                            OneLine("Checking is paused", Type.subheadline.weight(FontWeight.SemiBold))
                            OneLine("You won't get stock alerts", Type.caption, ink.secondary)
                        }
                        check != null -> {
                            OneLine(if (check.ok) "Checked ${check.area}" else "${check.area} didn't answer", Type.subheadline.weight(FontWeight.SemiBold))
                            OneLine("${status.subject(check)} · ${check.ms} ms", Type.caption, ink.secondary)
                        }
                        else -> OneLine("Waiting for the first check", Type.subheadline.weight(FontWeight.SemiBold))
                    }
                }
            }

            if (!paused) Symbol(Icons.Rounded.KeyboardArrowUp, Type.caption.copy(fontSize = 14.sp), tint = ink.tertiary)
        }

        if (paused) {
            ProminentButton(onClick = { model.launch { setPaused(false) } }, enabled = !model.isWorking) { Text("Resume") }
        }
    }
}

/** One line, so the capsule keeps its height. Long area names shrink a little first. */
@Composable
fun OneLine(text: String, style: TextStyle, color: Color = androidx.compose.material3.LocalContentColor.current, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        color = color,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = style.fontSize * 0.8f, maxFontSize = style.fontSize, stepSize = 0.5.sp),
    )
}

/**
 * SF Symbols' dot.radiowaves.left.and.right: a dot with two waves either side. While [animating], a
 * highlight runs out through the waves and starts again, like its iterative variable colour.
 */
@Composable
fun Radiowaves(color: Color, animating: Boolean, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val sweep = rememberInfiniteTransition(label = "radiowaves")
    val phase by sweep.animateFloat(0f, 3f, infiniteRepeatable(tween(1_500, easing = androidx.compose.animation.core.LinearEasing)), label = "phase")
    val running = animating && !reduceMotion
    Canvas(modifier.size(width = 28.dp, height = 20.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        val stroke = 2.dp.toPx()
        drawCircle(color, 2.4.dp.toPx(), center)
        for ((index, radius) in listOf(6.5.dp, 11.dp).withIndex()) {
            // Each wave is lit in its turn, and dim the rest of the time.
            val alpha = if (!running) 1f else {
                val distance = kotlin.math.abs(phase - (index + 0.5f)).coerceAtMost(1f)
                0.3f + 0.7f * (1 - distance)
            }
            val r = radius.toPx()
            val box = Size(r * 2, r * 2)
            val topLeft = Offset(center.x - r, center.y - r)
            for (start in listOf(140f, -40f)) {
                drawArc(color.copy(alpha = alpha), start, 80f, useCenter = false, topLeft = topLeft, size = box, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
    }
}
