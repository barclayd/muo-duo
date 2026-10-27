package com.barclayd.muoduo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val WIDTH = 340f

/** SwiftUI's .smooth(duration: 0.6), which a change of finish animates with. */
fun <T> finishChange() = spring<T>(dampingRatio = 1f, stiffness = 110f)

/** The phone in the selected colour, with the Worker's checks rippling out from behind it. */
@Composable
fun Hero(model: StatusModel, status: Status, finish: Finish, select: (Finish) -> Unit) {
    val inStock = status.sightings.any { status.model(it.part)?.finish == finish }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("iPhone Duo", style = TextStyle(fontSize = 38.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold))
            val (headline, color) = when {
                status.state == LiveState.Paused -> "Paused · not checking" to ink.secondary
                status.state is LiveState.Failing -> "Can't reach Apple right now" to ink.secondary
                status.sightings.isEmpty() -> "Watching ${status.storeCount} Apple Stores in ${status.areas.size} areas" to ink.secondary
                else -> "In stock now" to ink.green
            }
            Text(headline, color = color, style = Type.subheadline.weight(FontWeight.Medium))
        }

        Box(Modifier.size(WIDTH.dp, (WIDTH * 0.62f).dp), contentAlignment = Alignment.Center) {
            Radar(if (inStock) ink.green else finish.accent, model.pulses, Modifier.requiredSize(400.dp))
            Phone(model, finish)
        }

        FinishPicker(finish, status, select)
    }
}

@Composable
private fun Phone(model: StatusModel, finish: Finish) {
    val shadow = Color.Black.copy(alpha = if (finish == Finish.NightSky) 0.5f else 0.22f)
    Box(
        Modifier
            .offset(y = (WIDTH * 0.29f).dp)
            .requiredSize((WIDTH * 0.72f).dp, 18.dp)
            .blur(14.dp, BlurredEdgeTreatment.Unbounded)
            .background(shadow, androidx.compose.foundation.shape.GenericShape { size, _ -> addOval(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)) }),
    )
    AnimatedContent(
        targetState = finish,
        transitionSpec = {
            (fadeIn(finishChange()) + scaleIn(finishChange(), initialScale = 0.94f)) togetherWith
                (fadeOut(finishChange()) + scaleOut(finishChange(), targetScale = 0.94f))
        },
        contentAlignment = Alignment.Center,
        label = "phone",
    ) { finish ->
        // Apple's shots have a lot of empty space above and below the phone; only the middle is laid out.
        Box(Modifier.requiredSize(WIDTH.dp, (WIDTH * 1112 / 940).dp).blurReplace(this), contentAlignment = Alignment.Center) {
            val image = model.image(finish)
            if (image != null) {
                Image(image, "iPhone Duo in ${finish.title}", Modifier.matchParentSize().floating(), contentScale = ContentScale.Fit)
            } else {
                CircularProgressIndicator(Modifier.size(20.dp), color = ink.secondary, strokeWidth = 2.dp)
            }
        }
    }
}

/** SwiftUI's blurReplace: content blurs as it leaves and comes into focus as it arrives. */
@Composable
fun Modifier.blurReplace(scope: AnimatedVisibilityScope): Modifier {
    val radius by scope.transition.animateFloat({ finishChange() }, label = "blur") { if (it == EnterExitState.Visible) 0f else 12f }
    return graphicsLayer {
        if (radius > 0.1f) {
            renderEffect = android.graphics.RenderEffect
                .createBlurEffect(radius * density, radius * density, android.graphics.Shader.TileMode.DECAL)
                .asComposeRenderEffect()
        }
    }
}

@Composable
private fun Modifier.floating(): Modifier {
    if (rememberReduceMotion()) return this
    val float = rememberInfiniteTransition(label = "float")
    val y by float.animateFloat(3f, -5f, infiniteRepeatable(tween(3_200, easing = EaseInOut), RepeatMode.Reverse), label = "y")
    return graphicsLayer { translationY = y * density }
}

/** Night Sky / Star White, as glass capsules that light up in the selected colour. */
@Composable
fun FinishPicker(selection: Finish, status: Status, select: (Finish) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (finish in Finish.entries) {
            val selected = finish == selection
            val inStock = status.sightings.any { status.model(it.part)?.finish == finish }
            val interactions = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .glass(Capsule, if (selected) finish.accent.copy(alpha = 0.4f) else null, interactions)
                    .clickable(interactions, indication = null, role = Role.Button) {
                        if (!selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        select(finish)
                    }
                    .semantics { this.selected = selected }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Swatch(finish, 18)
                Text(
                    finish.title,
                    color = if (selected) ink.primary else ink.secondary,
                    style = Type.subheadline.weight(if (selected) FontWeight.SemiBold else FontWeight.Medium),
                )
                if (inStock) Box(Modifier.size(7.dp).background(ink.green, CircleShape))
            }
        }
    }
}

/** A dot in the phone's own colour. */
@Composable
fun Swatch(finish: Finish, size: Int) {
    Box(
        Modifier
            .size(size.dp)
            .background(finish.swatch.gradient(), CircleShape)
            .border(1.dp, ink.primary.copy(alpha = 0.25f), CircleShape),
    )
}
