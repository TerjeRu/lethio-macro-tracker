package com.lethio.macros.data.off

import okhttp3.Response

/**
 * Reads a response body with a size ceiling. Nothing guarantees a small body (proxies, captive
 * portals), and `OutOfMemoryError` would pass the callers' `Exception` catches and crash the app.
 *
 * @return the body, or null when absent or over [MAX_BODY_BYTES]; callers treat both as "try again
 *   later", since neither says anything about the product.
 */
internal fun Response.bodyWithCeiling(): String? {
    val source = body?.source() ?: return null
    // True only when more than the ceiling is available; at most ceiling + 1 bytes are buffered.
    if (source.request(MAX_BODY_BYTES + 1)) return null
    return source.readUtf8()
}

/** About 200 times a real response; no honest response reaches it. */
private const val MAX_BODY_BYTES = 512L * 1024L
