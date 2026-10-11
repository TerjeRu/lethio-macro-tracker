package com.lethio.macros.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Refresh on resume and at midnight; no timer runs while the screen is paused. */
@Composable
internal fun DateRefreshEffect(onRefresh: () -> Unit, nextCheckDelayMillis: () -> Long) {
    val owner = LocalLifecycleOwner.current
    val refresh by rememberUpdatedState(onRefresh)
    val nextDelay by rememberUpdatedState(nextCheckDelayMillis)
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                refresh()
                delay(nextDelay())
            }
        }
    }
}
