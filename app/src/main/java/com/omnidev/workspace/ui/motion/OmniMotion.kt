package com.omnidev.workspace.ui.motion

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** One short, interruptible motion language; no blur, perpetual glow or bitmap snapshots. */
@Immutable
data class MotionPolicy(val reduced: Boolean = false, val compact: Boolean = false) {
    val navigationMillis: Int get() = if (reduced) 0 else if (compact) 180 else 260
    val responseMillis: Int get() = if (reduced) 0 else if (compact) 120 else 180
    val ambientMotion: Boolean get() = !reduced && !compact
}

val LocalOmniMotion = staticCompositionLocalOf { MotionPolicy() }
val OmniEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

@Composable
fun rememberMotionPolicy(): MotionPolicy {
    val context = LocalContext.current.applicationContext
    val activityManager = remember(context) { context.getSystemService(ActivityManager::class.java) }
    val powerManager = remember(context) { context.getSystemService(PowerManager::class.java) }
    val lowRam = remember(activityManager) {
        val memory = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memory)
        activityManager?.isLowRamDevice == true || memory.totalMem in 1..(4L * 1024 * 1024 * 1024)
    }
    fun readPolicy() = MotionPolicy(
        reduced = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f,
        compact = lowRam || powerManager?.isPowerSaveMode == true
    )
    var policy by remember(context) { mutableStateOf(readPolicy()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { policy = readPolicy() }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { policy = readPolicy() }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer
        )
        ContextCompat.registerReceiver(context, receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        policy = readPolicy()
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    return policy
}

/** Default disclosure animation. Explicit caller transitions still obey reduced motion. */
@Composable
fun OmniAnimatedVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition? = null,
    exit: ExitTransition? = null,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    val policy = LocalOmniMotion.current
    val duration = policy.responseMillis
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (policy.reduced) EnterTransition.None else enter
            ?: (fadeIn(tween(duration, easing = OmniEasing)) + expandVertically(tween(duration, easing = OmniEasing))),
        exit = if (policy.reduced) ExitTransition.None else exit
            ?: (fadeOut(tween(duration / 2)) + shrinkVertically(tween(duration, easing = OmniEasing))),
        content = content
    )
}

@Composable
fun Modifier.omniAnimateContentSize(): Modifier =
    if (LocalOmniMotion.current.reduced) this else animateContentSize(
        animationSpec = tween(LocalOmniMotion.current.responseMillis, easing = OmniEasing)
    )
