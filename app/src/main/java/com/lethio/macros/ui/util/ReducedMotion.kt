package com.lethio.macros.ui.util

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the reader allows animation. Android's "Remove animations" sets
 * `ANIMATOR_DURATION_SCALE` to 0, and Compose animations ignore it unless asked. Read per
 * composition: changing it recreates the activity anyway.
 */
@Composable
fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }
}
