package com.dcelysia.csust_spider.mooc.cookie

import okhttp3.Cookie

data class SerializableCookie(
    val name: String?,
    val value: String?,
    val expiresAt: Long?,
    val domain: String?,
    val path: String?,
    val secure: Boolean?,
    val httpOnly: Boolean?,
    val hostOnly: Boolean?
) {
    fun toOkHttpCookieOrNull(): Cookie? {
        val safeName = name?.trim()?.takeUnless { it.isEmpty() } ?: return null
        val safeValue = value ?: return null
        val safeDomain = domain?.trim()?.takeUnless { it.isEmpty() } ?: return null
        val safePath = path?.trim()?.takeUnless { it.isEmpty() } ?: "/"
        val safeExpiresAt = expiresAt ?: return null
        if (safeExpiresAt <= System.currentTimeMillis()) return null

        return Cookie.Builder()
            .name(safeName)
            .value(safeValue)
            .expiresAt(safeExpiresAt)
            .path(safePath)
            .apply {
                if (hostOnly == true) hostOnlyDomain(safeDomain) else domain(safeDomain)
                if (secure == true) secure()
                if (httpOnly == true) httpOnly()
            }
            .build()
    }
}