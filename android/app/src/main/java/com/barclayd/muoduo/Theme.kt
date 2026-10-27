package com.barclayd.muoduo

import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.animation.core.spring
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.LocalHazePerformanceMode

/** iOS's system colours, so the two apps look alike. */
@Immutable
data class Ink(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val separator: Color,
    val background: Color,
    val green: Color,
    val orange: Color,
    val yellow: Color,
    val red: Color,
    val blue: Color,
    val gray: Color,
) {
    companion object {
        val light = Ink(
            primary = Color.Black,
            secondary = Color(60, 60, 67).copy(alpha = 0.6f),
            tertiary = Color(60, 60, 67).copy(alpha = 0.3f),
            separator = Color(60, 60, 67).copy(alpha = 0.29f),
            background = Color.White,
            green = Color(52, 199, 89),
            orange = Color(255, 149, 0),
            yellow = Color(255, 204, 0),
            red = Color(255, 59, 48),
            blue = Color(0, 122, 255),
            gray = Color(142, 142, 147),
        )
        val dark = Ink(
            primary = Color.White,
            secondary = Color(235, 235, 245).copy(alpha = 0.6f),
            tertiary = Color(235, 235, 245).copy(alpha = 0.3f),
            separator = Color(84, 84, 88).copy(alpha = 0.6f),
            background = Color.Black,
            green = Color(48, 209, 88),
            orange = Color(255, 159, 10),
            yellow = Color(255, 214, 10),
            red = Color(255, 69, 58),
            blue = Color(10, 132, 255),
            gray = Color(142, 142, 147),
        )
    }
}

val LocalInk = staticCompositionLocalOf { Ink.dark }

val ink: Ink
    @Composable @ReadOnlyComposable get() = LocalInk.current

/** iOS's text styles, at its default size. */
object Type {
    val largeTitle = TextStyle(fontSize = 34.sp, lineHeight = 41.sp)
    val title = TextStyle(fontSize = 28.sp, lineHeight = 34.sp)
    val title2 = TextStyle(fontSize = 22.sp, lineHeight = 28.sp)
    val title3 = TextStyle(fontSize = 20.sp, lineHeight = 25.sp)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp)
    val callout = TextStyle(fontSize = 16.sp, lineHeight = 21.sp)
    val subheadline = TextStyle(fontSize = 15.sp, lineHeight = 20.sp)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val caption2 = TextStyle(fontSize = 11.sp, lineHeight = 13.sp)
}

fun TextStyle.weight(weight: FontWeight) = copy(fontWeight = weight)

/** Digits all the same width, so counts and timers don't jiggle. */
fun TextStyle.monospacedDigit() = copy(fontFeatureSettings = "tnum")

/**
 * Light or dark to match the finish, like iOS's preferredColorScheme. The configuration is overridden
 * too, because that's where the glass reads which way to render.
 */
@Composable
fun MuoDuoTheme(finish: Finish, content: @Composable () -> Unit) {
    val ink = if (finish.dark) Ink.dark else Ink.light
    val configuration = LocalConfiguration.current
    val themed = remember(configuration, finish) {
        Configuration(configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (finish.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
    }
    val colors = if (finish.dark) {
        darkColorScheme(primary = ink.blue, onPrimary = Color.White, surface = Color(28, 28, 30), onSurface = ink.primary, error = ink.red)
    } else {
        lightColorScheme(primary = ink.blue, onPrimary = Color.White, surface = Color.White, onSurface = ink.primary, error = ink.red)
    }
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(
            LocalInk provides ink,
            LocalConfiguration provides themed,
            LocalContentColor provides ink.primary,
            LocalTextStyle provides Type.body,
            // Blur from half-size copies: it can't be told apart under this much blur, and costs a quarter as much.
            LocalHazePerformanceMode provides HazePerformanceMode.Performance,
            content = content,
        )
    }
}

/** SwiftUI's named springs: half a second, with no bounce, a little, or more. */
fun <T> smooth() = spring<T>(dampingRatio = 1f, stiffness = 158f)
fun <T> snappy() = spring<T>(dampingRatio = 0.85f, stiffness = 158f)
fun <T> bouncy() = spring<T>(dampingRatio = 0.7f, stiffness = 158f)

/** Android's "Remove animations", the nearest thing to iOS's Reduce Motion. */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * SwiftUI's sensoryFeedback(trigger:): the haptic [feedback] picks each time [value] changes, though not when
 * it first appears. Returning null skips that change.
 */
@Composable
fun <T> HapticOnChange(value: T, feedback: (old: T, new: T) -> HapticFeedbackType?) {
    val haptics = LocalHapticFeedback.current
    val last = remember { arrayOf<Any?>(value) }
    LaunchedEffect(value) {
        @Suppress("UNCHECKED_CAST")
        val old = last[0] as T
        last[0] = value
        if (old != value) feedback(old, value)?.let(haptics::performHapticFeedback)
    }
}
