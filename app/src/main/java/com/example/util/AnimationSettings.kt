package com.example.util

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt

/**
 * Reads the device's current animator duration scale (Developer Options & Accessibility
 * "Remove animations"). Returns 0f when animations are disabled.
 */
fun getSystemAnimationScale(context: Context): Float {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) {
        return 0f
    }
    val animatorScale = try {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1.0f
        )
    } catch (_: Exception) {
        1.0f
    }
    return animatorScale.coerceIn(0f, 5f)
}

/**
 * Observes both lifecycle resume and Settings.Global changes so system animation scale updates
 * immediately take effect across all folder layouts (3×3, 2×2, 1×2, 1×4) and activities.
 */
@Composable
fun rememberSystemAnimationScale(): Float {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var scale by remember(context) { mutableFloatStateOf(getSystemAnimationScale(context)) }

    DisposableEffect(context, lifecycleOwner) {
        val refresh = {
            scale = getSystemAnimationScale(context)
        }
        refresh()

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refresh()
            }
        }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        try {
            if (uri != null) {
                context.contentResolver.registerContentObserver(uri, false, contentObserver)
            }
        } catch (_: Exception) {
            // Ignore if resolver rejects observer registration in restricted environments
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            try {
                context.contentResolver.unregisterContentObserver(contentObserver)
            } catch (_: Exception) {
            }
        }
    }

    return scale
}

fun scaledDurationMs(baseMs: Int, animScale: Float): Int {
    if (animScale <= 0.05f) return 0
    return (baseMs * animScale).roundToInt().coerceAtLeast(1)
}

fun scaledSpringSpec(
    animScale: Float,
    baseStiffness: Float = 460f,
    dampingRatio: Float = 0.88f
): AnimationSpec<Float> {
    if (animScale <= 0.05f) return snap()
    val scaledStiffness = (baseStiffness / (animScale * animScale)).coerceIn(30f, 4000f)
    return spring(dampingRatio = dampingRatio, stiffness = scaledStiffness)
}

fun <T> scaledTweenSpec(
    baseMs: Int,
    animScale: Float
): AnimationSpec<T> {
    if (animScale <= 0.05f) return snap()
    return tween(durationMillis = scaledDurationMs(baseMs, animScale))
}
