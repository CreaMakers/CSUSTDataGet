package com.dcelysia.csust_spider.core.cookie

data class SerializableKtorCookie(
    val name: String,
    val value: String,
    val expiresAt: Long?,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean
)

