package com.dcelysia.csust_spider.core.cookie

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class SerializableKtorCookie(
        @SerializedName("name") val name: String?,
        @SerializedName("value") val value: String?,
        @SerializedName("expiresAt") val expiresAt: Long?,
        @SerializedName("domain") val domain: String?,
        @SerializedName("path") val path: String?,
        @SerializedName("secure") val secure: Boolean?,
        @SerializedName("httpOnly") val httpOnly: Boolean?
)
