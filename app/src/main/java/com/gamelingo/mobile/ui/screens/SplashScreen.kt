package com.gamelingo.mobile.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gamelingo.mobile.R
import com.gamelingo.mobile.ui.components.LogoMark
import com.gamelingo.mobile.ui.components.glScreenBackground
import com.gamelingo.mobile.ui.theme.GlColors
import com.gamelingo.mobile.ui.theme.GlSpacing
import kotlinx.coroutines.delay

/**
 * Short branded intro: the mark fades and scales in over a soft glow, then
 * hands over to the app after about a second. One animated float drives
 * everything, so it costs almost nothing.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    var started by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "splash",
    )
    val finished by rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        started = true
        delay(1_100L)
        finished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .glScreenBackground(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(300.dp)
                .graphicsLayer { alpha = progress }
                .background(
                    Brush.radialGradient(
                        colors = listOf(GlColors.Primary.copy(alpha = 0.32f), Color.Transparent),
                    ),
                ),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LogoMark(
                size = 120.dp,
                modifier = Modifier.graphicsLayer {
                    alpha = progress
                    val scale = 0.82f + 0.18f * progress
                    scaleX = scale
                    scaleY = scale
                },
            )
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                color = GlColors.TextPrimary,
                modifier = Modifier
                    .padding(top = GlSpacing.xl)
                    .graphicsLayer { alpha = progress },
            )
            Text(
                text = stringResource(R.string.splash_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = GlColors.TextSecondary,
                modifier = Modifier
                    .padding(top = GlSpacing.xs)
                    .graphicsLayer { alpha = progress },
            )
        }
    }
}
