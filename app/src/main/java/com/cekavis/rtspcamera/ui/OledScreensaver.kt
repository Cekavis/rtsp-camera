package com.cekavis.rtspcamera.ui

import android.app.Activity
import android.os.SystemClock
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.random.Random

private data class DriftingParticle(
    val from: Offset,
    val to: Offset,
    val since: Long,
    val duration: Long,
    val radius: Float,
) {
    fun position(now: Long): Offset {
        val progress = ((now - since).toFloat() / duration).coerceIn(0f, 1f)
        val eased = progress * progress * (3f - 2f * progress)
        return from + (to - from) * eased
    }

    fun opacity(now: Long): Float {
        val progress = ((now - since).toFloat() / duration).coerceIn(0f, 1f)
        return (minOf(progress, 1f - progress) * 5f).coerceIn(0f, 1f)
    }
}

private fun randomPoint() = Offset(Random.nextFloat() * .9f + .05f, Random.nextFloat() * .9f + .05f)

@Composable
internal fun OledScreensaver(activity: Activity, onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    DisposableEffect(activity) {
        val window = activity.window
        val previousBrightness = window.attributes.screenBrightness
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        val previousBehavior = bars.systemBarsBehavior
        window.attributes = window.attributes.apply { screenBrightness = .05f }
        // A fixed, bright third-party floating button defeats an otherwise dark OLED screen.
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            window.attributes = window.attributes.apply { screenBrightness = previousBrightness }
            if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(false)
            bars.systemBarsBehavior = previousBehavior
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    val startedAt = remember { SystemClock.elapsedRealtime() }
    var elapsed by remember { mutableLongStateOf(0L) }
    var particles by remember {
        mutableStateOf(List(5) {
            DriftingParticle(randomPoint(), randomPoint(), 0L, Random.nextLong(18_000L, 42_000L), Random.nextFloat() * 1.5f + 1.5f)
        })
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            elapsed = SystemClock.elapsedRealtime() - startedAt
            particles = particles.map { particle ->
                if (elapsed - particle.since >= particle.duration) {
                    particle.copy(from = randomPoint(), to = randomPoint(), since = elapsed, duration = Random.nextLong(18_000L, 42_000L))
                } else {
                    particle
                }
            }
            delay(100L)
        }
    }
    val resting = elapsed >= 300_000L && elapsed % 300_000L < 15_000L
    Canvas(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(onExit) { detectTapGestures { onExit() } }
            .semantics {
                contentDescription = "屏幕保护，轻触退出"
                onClick(label = "退出屏幕保护") { onExit(); true }
            },
    ) {
        if (!resting) {
            particles.forEach { particle ->
                val position = particle.position(elapsed)
                drawCircle(
                    color = Color(0xFF0A100C).copy(alpha = particle.opacity(elapsed)),
                    radius = particle.radius * density,
                    center = Offset(position.x * size.width, position.y * size.height),
                )
            }
        }
    }
}
