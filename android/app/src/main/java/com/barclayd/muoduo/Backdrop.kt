package com.barclayd.muoduo

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.floor
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * A slowly drifting sky behind everything, in the colours of the selected finish's wallpaper, for the
 * glass to refract. Night Sky gets stars.
 */
@Composable
fun Backdrop(finish: Finish, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val time = remember { mutableDoubleStateOf(now()) }
    // Half the iPhone's 30 a second: the sky moves a pixel or two a step, which can't be seen, and every
    // pane of glass over it has to be blurred again each time it does.
    LaunchedEffect(reduceMotion) {
        while (!reduceMotion) {
            time.doubleValue = now()
            delay(1_000L / 15)
        }
    }
    val starWhite by animateFloatAsState(if (finish == Finish.StarWhite) 1f else 0f, tween(900, easing = EaseInOut), label = "finish")
    val mesh = remember { Mesh() }

    Spacer(
        modifier.drawBehind {
            val t = time.doubleValue
            val colors = Finish.NightSky.palette.zip(Finish.StarWhite.palette) { night, day -> lerp(night, day, starWhite) }
            drawIntoCanvas { mesh.draw(it.nativeCanvas, size.width, size.height, colors, t) }
            if (starWhite < 1f) stars(t, opacity = 1f - starWhite)
        },
    )
}

/** Seconds since 2001, like iOS's reference date, so the sky drifts the same on both. */
private fun now() = System.currentTimeMillis() / 1000.0 - 978_307_200.0

/** The same stars every frame: a fixed seed, only their brightness changes. */
private fun DrawScope.stars(t: Double, opacity: Float) {
    var seed = 7UL
    fun random(): Double {
        seed = seed * 6_364_136_223_846_793_005UL + 1_442_695_040_888_963_407UL
        return (seed shr 33).toDouble() / (1L shl 31).toDouble()
    }
    val band = size.height * 0.42
    for (i in 0 until 80) {
        val x = random() * size.width
        val y = random() * band
        val radius = (0.4 + random() * 1.0) * density
        val twinkle = 0.5 + 0.5 * sin(t * (0.5 + random()) + i)
        val alpha = (0.35 + 0.65 * twinkle) * (1 - y / band) * 0.85 * opacity
        drawCircle(Color.White, radius.toFloat(), Offset((x + radius).toFloat(), (y + radius).toFloat()), alpha.toFloat().coerceIn(0f, 1f))
    }
}

/**
 * A 3×3 mesh gradient, like SwiftUI's MeshGradient: the points and colours are interpolated smoothly
 * across a finer grid of triangles, which the canvas shades between their corners.
 */
private class Mesh {
    private val steps = 24
    private val count = steps + 1
    private val vertices = FloatArray(count * count * 2)
    private val colors = IntArray(count * count)
    private val indices = ShortArray(steps * steps * 6).also { indices ->
        var i = 0
        for (row in 0 until steps) for (column in 0 until steps) {
            val a = (row * count + column).toShort()
            val b = (row * count + column + 1).toShort()
            val c = ((row + 1) * count + column).toShort()
            val d = ((row + 1) * count + column + 1).toShort()
            indices[i++] = a; indices[i++] = b; indices[i++] = c
            indices[i++] = b; indices[i++] = d; indices[i++] = c
        }
    }
    private val paint = Paint().apply { isAntiAlias = false }
    private val xs = DoubleArray(9)
    private val ys = DoubleArray(9)
    private val channels = Array(3) { DoubleArray(9) }

    fun draw(canvas: Canvas, width: Float, height: Float, palette: List<Color>, t: Double) {
        fun drift(phase: Double, amount: Double) = sin(t / 7 + phase) * amount
        val points = doubleArrayOf(
            0.0, 0.0, 0.5, 0.0, 1.0, 0.0,
            0.0, 0.45 + drift(0.0, 0.05), 0.5 + drift(1.0, 0.08), 0.5 + drift(2.0, 0.06), 1.0, 0.42 + drift(3.0, 0.05),
            0.0, 1.0, 0.5, 1.0, 1.0, 1.0,
        )
        for (i in 0 until 9) {
            xs[i] = points[i * 2]
            ys[i] = points[i * 2 + 1]
            channels[0][i] = palette[i].red.toDouble()
            channels[1][i] = palette[i].green.toDouble()
            channels[2][i] = palette[i].blue.toDouble()
        }
        for (row in 0 until count) for (column in 0 until count) {
            val u = column.toDouble() / steps * 2
            val v = row.toDouble() / steps * 2
            val index = row * count + column
            vertices[index * 2] = (patch(xs, u, v) * width).toFloat()
            vertices[index * 2 + 1] = (patch(ys, u, v) * height).toFloat()
            colors[index] = Color(
                patch(channels[0], u, v).coerceIn(0.0, 1.0).toFloat(),
                patch(channels[1], u, v).coerceIn(0.0, 1.0).toFloat(),
                patch(channels[2], u, v).coerceIn(0.0, 1.0).toFloat(),
            ).toArgb()
        }
        canvas.drawVertices(Canvas.VertexMode.TRIANGLES, vertices.size, vertices, 0, null, 0, colors, 0, indices, 0, indices.size, paint)
    }

    /** One value across the 3×3 grid at (u, v), each running 0 to 2: a Catmull-Rom spline each way. */
    private fun patch(values: DoubleArray, u: Double, v: Double): Double {
        val row0 = spline(values[0], values[1], values[2], u)
        val row1 = spline(values[3], values[4], values[5], u)
        val row2 = spline(values[6], values[7], values[8], u)
        return spline(row0, row1, row2, v)
    }

    private fun spline(p0: Double, p1: Double, p2: Double, s: Double): Double {
        val segment = floor(s).toInt().coerceIn(0, 1)
        val f = s - segment
        val (a, b) = if (segment == 0) p0 to p1 else p1 to p2
        // Tangents: central in the middle, one-sided at the ends.
        val m0 = if (segment == 0) p1 - p0 else (p2 - p0) / 2
        val m1 = if (segment == 0) (p2 - p0) / 2 else p2 - p1
        val f2 = f * f
        val f3 = f2 * f
        return (2 * f3 - 3 * f2 + 1) * a + (f3 - 2 * f2 + f) * m0 + (-2 * f3 + 3 * f2) * b + (f3 - f2) * m1
    }
}
