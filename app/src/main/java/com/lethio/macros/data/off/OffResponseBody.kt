package com.lethio.macros.data.off

import okhttp3.Response

internal fun Response.bodyWithCeiling(): String? {
    val source = body?.source() ?: return null

    if (source.request(MAX_BODY_BYTES + 1)) return null
    return source.readUtf8()
}

private const val MAX_BODY_BYTES = 512L * 1024L
