package com.lethio.macros.data.off

import com.lethio.macros.BuildConfig

/**
 * How the app identifies itself to Open Food Facts: `AppName/Version (ContactEmail)`, as OFF
 * requires. It names the app, never the user or the install. Shared by lookup and contribution.
 */
object OffUserAgent {
    val VALUE = "LethioMacros/${BuildConfig.VERSION_NAME} (support@lethio.com)"
}
