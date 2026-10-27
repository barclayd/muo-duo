package com.barclayd.muoduo

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit

/**
 * The two iPhone Duo colours. The app takes its look from whichever is selected: Star White is the
 * dawn desert on its default wallpaper, Night Sky the same desert at dusk.
 */
enum class Finish(val title: String, val dark: Boolean) {
    NightSky("Night Sky", dark = true),
    StarWhite("Star White", dark = false);

    /** The colour of the phone itself, for swatches. */
    val swatch: Color
        get() = when (this) {
            NightSky -> rgb(0.17, 0.21, 0.28)
            StarWhite -> rgb(0.90, 0.89, 0.87)
        }

    /** Tints glass and pulses. */
    val accent: Color
        get() = when (this) {
            NightSky -> rgb(0.66, 0.62, 1.0)
            StarWhite -> rgb(0.74, 0.47, 0.22)
        }

    /** Sky, horizon and dunes, three by three, for the backdrop's mesh gradient. */
    val palette: List<Color>
        get() = when (this) {
            NightSky -> listOf(
                rgb(0.06, 0.08, 0.17), rgb(0.09, 0.11, 0.23), rgb(0.06, 0.08, 0.16),
                rgb(0.33, 0.27, 0.40), rgb(0.50, 0.35, 0.46), rgb(0.29, 0.25, 0.38),
                rgb(0.13, 0.11, 0.17), rgb(0.20, 0.17, 0.24), rgb(0.11, 0.10, 0.15),
            )
            StarWhite -> listOf(
                rgb(0.66, 0.76, 0.87), rgb(0.77, 0.83, 0.90), rgb(0.70, 0.79, 0.88),
                rgb(0.93, 0.84, 0.75), rgb(0.96, 0.88, 0.79), rgb(0.91, 0.81, 0.71),
                rgb(0.84, 0.77, 0.67), rgb(0.80, 0.72, 0.62), rgb(0.75, 0.67, 0.57),
            )
        }
}

private fun rgb(red: Double, green: Double, blue: Double) = Color(red.toFloat(), green.toFloat(), blue.toFloat())

/** The selected finish, kept between launches. */
class FinishPreference(context: Context) {
    private val prefs = context.getSharedPreferences("muoduo", Context.MODE_PRIVATE)

    var value by mutableStateOf(Finish.entries.firstOrNull { it.title == prefs.getString(KEY, null) } ?: Finish.NightSky)
        private set

    fun select(finish: Finish) {
        value = finish
        prefs.edit { putString(KEY, finish.title) }
    }

    private companion object {
        const val KEY = "finish"
    }
}
