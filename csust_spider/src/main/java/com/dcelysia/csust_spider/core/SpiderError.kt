package com.dcelysia.csust_spider.core

import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.RedirectResponseException
import io.ktor.client.plugins.ServerResponseException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.serialization.SerializationException

/**
 * 结构化错误，便于上层精确判断失败位置和类型。
 */
data class SpiderError(
    val code: String,
    val message: String,
    val source: String,
    val category: Category,
    val endpoint: String? = null,
    val httpStatus: Int? = null,
    val causeMessage: String? = null
) {
    enum class Category {
        NETWORK,
        HTTP,
        AUTH,
        PARSE,
        BUSINESS,
        UNKNOWN
    }

    fun toReadableMessage(): String {
        val parts = mutableListOf<String>()
        parts += "code=$code"
        parts += "source=$source"
        parts += "category=${category.name}"
        endpoint?.let { parts += "endpoint=$it" }
        httpStatus?.let { parts += "http=$it" }
        parts += "message=$message"
        causeMessage?.let { parts += "cause=$it" }
        return parts.joinToString(" | ")
    }
}

object SpiderErrors {
    fun business(code: String, source: String, message: String, endpoint: String? = null): SpiderError {
        return SpiderError(code, message, source, SpiderError.Category.BUSINESS, endpoint = endpoint)
    }

    fun auth(code: String, source: String, message: String, endpoint: String? = null): SpiderError {
        return SpiderError(code, message, source, SpiderError.Category.AUTH, endpoint = endpoint)
    }

    fun parse(code: String, source: String, message: String, endpoint: String? = null): SpiderError {
        return SpiderError(code, message, source, SpiderError.Category.PARSE, endpoint = endpoint)
    }

    fun fromThrowable(throwable: Throwable, source: String, endpoint: String? = null): SpiderError {
        return when (throwable) {
            is RedirectResponseException -> {
                val response = throwable.response
                SpiderError(
                    code = "HTTP_STATUS_ERROR",
                    message = "服务器返回异常状态码",
                    source = source,
                    category = SpiderError.Category.HTTP,
                    endpoint = endpoint,
                    httpStatus = response.status.value,
                    causeMessage = throwable.message
                )
            }

            is ClientRequestException -> {
                val response = throwable.response
                SpiderError(
                    code = "HTTP_STATUS_ERROR",
                    message = "服务器返回异常状态码",
                    source = source,
                    category = SpiderError.Category.HTTP,
                    endpoint = endpoint,
                    httpStatus = response.status.value,
                    causeMessage = throwable.message
                )
            }

            is ServerResponseException -> {
                val response = throwable.response
                SpiderError(
                    code = "HTTP_STATUS_ERROR",
                    message = "服务器返回异常状态码",
                    source = source,
                    category = SpiderError.Category.HTTP,
                    endpoint = endpoint,
                    httpStatus = response.status.value,
                    causeMessage = throwable.message
                )
            }

            is HttpRequestTimeoutException,
            is java.net.SocketTimeoutException -> {
                SpiderError(
                    code = "NETWORK_TIMEOUT",
                    message = "网络请求超时",
                    source = source,
                    category = SpiderError.Category.NETWORK,
                    endpoint = endpoint,
                    causeMessage = throwable.message
                )
            }

            is UnknownHostException -> {
                SpiderError(
                    code = "NETWORK_DNS_ERROR",
                    message = "无法解析服务器地址",
                    source = source,
                    category = SpiderError.Category.NETWORK,
                    endpoint = endpoint,
                    causeMessage = throwable.message
                )
            }

            is ConnectException,
            is SocketException,
            is SSLException -> {
                SpiderError(
                    code = "NETWORK_CONNECT_ERROR",
                    message = "网络连接失败",
                    source = source,
                    category = SpiderError.Category.NETWORK,
                    endpoint = endpoint,
                    causeMessage = throwable.message
                )
            }

            is SerializationException,
            is IllegalStateException -> {
                SpiderError(
                    code = "RESPONSE_PARSE_ERROR",
                    message = "响应解析失败",
                    source = source,
                    category = SpiderError.Category.PARSE,
                    endpoint = endpoint,
                    causeMessage = throwable.message
                )
            }

            else -> {
                SpiderError(
                    code = "UNKNOWN_ERROR",
                    message = throwable.message ?: "未知错误",
                    source = source,
                    category = SpiderError.Category.UNKNOWN,
                    endpoint = endpoint,
                    causeMessage = throwable.message
                )
            }
        }
    }
}

