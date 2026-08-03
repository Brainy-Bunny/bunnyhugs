package com.ssbmedia.twogether.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Lightweight full-screen heart-burst celebration, shown when a fresh apart->together reunion is detected. */
@Composable
fun ReunionOverlay(onDismiss: () -> Unit) {
    val centerScale = remember { Animatable(0f) }
    val particles = remember { List(10) { Animatable(0f) } }

    LaunchedEffect(Unit) {
        launch {
            centerScale.animateTo(1.15f, tween(350, easing = LinearOutSlowInEasing))
            centerScale.animateTo(1f, tween(150))
        }
        particles.forEachIndexed { index, particle ->
            launch {
                delay(index * 40L)
                particle.animateTo(1f, tween(900, easing = LinearOutSlowInEasing))
            }
        }
        delay(2600)
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        particles.forEachIndexed { index, particle ->
            val angle = (index * (360f / particles.size)) * (Math.PI / 180.0)
            val radius = 130.dp
            val progress = particle.value
            val x = (cos(angle) * radius.value * progress).dp
            val y = (sin(angle) * radius.value * progress).dp
            Text(
                text = "💕",
                fontSize = 22.sp,
                modifier = Modifier
                    .offset(x = x, y = y)
                    .graphicsLayer { alpha = (1f - progress) }
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "💗",
                fontSize = 72.sp,
                modifier = Modifier.graphicsLayer { scaleX = centerScale.value; scaleY = centerScale.value }
            )
            Text(
                text = "Together again!",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                text = "Welcome back to each other 🎉",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
