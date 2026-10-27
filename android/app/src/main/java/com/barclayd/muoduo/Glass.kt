@file:OptIn(ExperimentalHazeApi::class)

package com.barclayd.muoduo

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass

/** The page's haze: what every pane of glass on it refracts. */
val LocalHaze = staticCompositionLocalOf<HazeState> { error("Glass needs a Page around it") }

val Capsule = RoundedCornerShape(percent = 50)

/**
 * Liquid glass, like iOS's glassEffect. Inside a page's scrolling content it refracts the backdrop; over
 * it, as the bars are, it refracts the content too. With an interaction source it swells when pressed, like
 * iOS's interactive glass.
 */
@Composable
fun Modifier.glass(shape: RoundedCornerShape, tint: Color? = null, interactionSource: InteractionSource? = null): Modifier {
    val style = remember(shape, tint) {
        GlassStyle {
            shape(shape)
            optics(blurRadius = 24.dp)
            if (tint != null) tint(tint)
        }
    }
    // Haze's own pressed response can only shrink the glass, so the swell is done here.
    val pressed = if (interactionSource != null) interactionSource.collectIsPressedAsState().value else false
    val swell by animateFloatAsState(if (pressed) 1.04f else 1f, spring(dampingRatio = 0.6f, stiffness = 600f))
    return graphicsLayer { scaleX = swell; scaleY = swell }.hazeGlass(HazeInput.Sources(LocalHaze.current), style)
}

/** A pane of glass for a section of a page. */
@Composable
fun Modifier.card(tint: Color? = null): Modifier =
    fillMaxWidth().glass(RoundedCornerShape(28.dp), tint?.copy(alpha = 0.22f)).padding(20.dp)

/** Tappable without Material's ripple, dimming while pressed like iOS's plain buttons. */
@Composable
fun Modifier.plainButton(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    return graphicsLayer { alpha = if (pressed) 0.6f else 1f }
        .clickable(interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
}

/** iOS's .glass button: the label in the accent colour, on clear glass. */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    large: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) = ButtonFrame(onClick, modifier, enabled, large, tint = null, label = if (enabled) ink.blue else ink.tertiary, content)

/** iOS's .glassProminent button: a white label on glass filled with [color]. */
@Composable
fun ProminentButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = ink.blue,
    enabled: Boolean = true,
    large: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) = ButtonFrame(
    onClick, modifier, enabled, large,
    tint = if (enabled) color else ink.gray.copy(alpha = 0.3f),
    label = if (enabled) Color.White else ink.tertiary,
    content = content,
)

@Composable
private fun ButtonFrame(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    large: Boolean,
    tint: Color?,
    label: Color,
    content: @Composable RowScope.() -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .glass(Capsule, tint, interactionSource = interactions)
            .clickable(interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (large) 20.dp else 14.dp, vertical = if (large) 14.dp else 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides label,
            LocalTextStyle provides Type.body.weight(FontWeight.Medium),
        ) { content() }
    }
}

/** A section's title, small and in capitals, with its symbol and an optional count. */
@Composable
fun CardHeader(title: String, icon: ImageVector, trailing: String? = null) {
    CompositionLocalProvider(LocalContentColor provides ink.secondary) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Symbol(icon, Type.footnote)
            Spacer(Modifier.width(6.dp))
            Text(title.uppercase(), style = Type.footnote.weight(FontWeight.SemiBold).copy(letterSpacing = 0.6.sp))
            Spacer(Modifier.weight(1f))
            if (trailing != null) Text(trailing, style = Type.footnote.monospacedDigit())
        }
    }
}

/** An icon sized to sit beside text in [style], as an SF Symbol would. */
@Composable
fun Symbol(icon: ImageVector, style: TextStyle = LocalTextStyle.current, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    val size: Dp = with(LocalDensity.current) { style.fontSize.toDp() } * 1.2f
    Icon(icon, contentDescription = null, modifier = modifier.size(size), tint = tint)
}

/** iOS's hairline between rows. */
@Composable
fun Separator(modifier: Modifier = Modifier) = HorizontalDivider(modifier, thickness = Dp.Hairline, color = ink.separator)

/** iOS's switch: green when on, with a full-size white thumb either way. */
@Composable
fun Toggle(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            thumbContent = {},
            colors = SwitchDefaults.colors(
                checkedTrackColor = ink.green,
                checkedThumbColor = Color.White,
                checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = ink.gray.copy(alpha = 0.28f),
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = Color.Transparent,
                disabledCheckedTrackColor = ink.green.copy(alpha = 0.5f),
                disabledCheckedThumbColor = Color.White,
                disabledUncheckedTrackColor = ink.gray.copy(alpha = 0.14f),
                disabledUncheckedThumbColor = Color.White,
                disabledUncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** SwiftUI's colour.gradient: a little lighter at the top, a little darker at the bottom. */
fun Color.gradient() = Brush.verticalGradient(listOf(lerp(this, Color.White, 0.12f), lerp(this, Color.Black, 0.06f)))
