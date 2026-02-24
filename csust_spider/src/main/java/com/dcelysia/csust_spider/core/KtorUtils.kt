package com.dcelysia.csust_spider.core

import android.R.attr.password
import android.os.AsyncTask.execute
import android.util.Log
import android.util.Log.e
import com.dcelysia.csust_spider.core.cookie.PersistentCookiesStorage
import com.dcelysia.csust_spider.education.data.remote.EducationData
import com.dcelysia.csust_spider.education.data.remote.services.AuthService
import com.dcelysia.csust_spider.login.edu.LoginRepository
import com.dcelysia.csust_spider.login.sso.SsoRepository
import com.dcelysia.csust_spider.mooc.data.remote.repository.MoocRepository
import com.tencent.mmkv.MMKV
import io.ktor.client.HttpClient
import io.ktor.client.call.save
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.plugin
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType.Application.Json
import io.ktor.http.contentType
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

object KtorUtils {
    private const val TAG = "KtorUtils"
    private const val MMKV_ID = "csust_cookie_jar"
    private const val USER_AGENT = "okhttp/4.11.0"

    // URL Constants
    private const val MOOC_LOCATION = "http://pt.csust.edu.cn"
    private const val SSO_AUTH_URL = "https://authserver.csust.edu.cn"
    private const val SSO_EHALL_URL = "https://ehall.csust.edu.cn"
    private const val EDUCA_LOGIN_URL = "http://xk.csust.edu.cn"
    private const val CAMPUS_CARD_LOCATION = "http://yktwd.csust.edu.cn:8988"

    // Lazy load the CookieJar shared instance

    // --- 配置通用部分 ---
    // 为了避免重复代码，创建一个基础配置函数
    private fun createBaseClient(
            baseUrl: String? = null,
            addRetryInterceptor: Boolean = false
    ): HttpClient {
        val client =
                HttpClient(CIO) {
                    engine {
                        https {
                            trustManager =
                                    object : X509TrustManager {
                                        override fun checkClientTrusted(
                                                p0: Array<out X509Certificate>?,
                                                p1: String?
                                        ) {}
                                        override fun checkServerTrusted(
                                                p0: Array<out X509Certificate>?,
                                                p1: String?
                                        ) {}
                                        override fun getAcceptedIssuers(): Array<X509Certificate>? =
                                                null
                                    }
                        }
                    }
                    install(HttpCookies) { storage = PersistentCookiesStorage.instance }
                    install(Logging) {
                        logger =
                                object : Logger {
                                    override fun log(message: String) {
                                        android.util.Log.d(TAG, message)
                                    }
                                }
                        level = LogLevel.ALL
                    }
                    install(HttpTimeout) {
                        requestTimeoutMillis = 30_000
                        connectTimeoutMillis = 30_000
                        socketTimeoutMillis = 30_000
                    }
                    install(ContentNegotiation) {
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                            encodeDefaults = true
                        }
                    }
                    defaultRequest {
                        contentType(Json)
                        if (baseUrl != null) {
                            url(baseUrl)
                        }
                    }
                }

        if (addRetryInterceptor) {
            client.plugin(HttpSend).intercept { request ->
                var call = execute(request)
                // 用 save() 把响应 body 缓冲到内存，防止读取后流被消耗掉
                call = call.save()
                val responseBody = call.response.bodyAsText()
                if (responseBody.contains("用户登录")) {
                    Log.d(TAG, "检测到登录页面，cookie 可能过期，开始自动登录流程...")
                    val reloginSuccess =
                        withContext(Dispatchers.IO) {
                            try {
                                MMKV.mmkvWithID(MMKV_ID).clearAll()
                                PersistentCookiesStorage.instance.clear()
                                val response = SsoRepository.instance.login(
                                    username = "202408130230",
                                    password = "@Wsl20060606"
                                )
                                val result = response.filter { it !is Resource.Loading }.first()
                                when (result) {
                                    is Resource.Success -> {
                                        Log.d(TAG, "sso登录成功")
                                        val eduResponse = LoginRepository.instance.login()
                                        val eduResult =
                                            eduResponse.filter { it !is Resource.Loading }.first()
                                        when (eduResult) {
                                            is Resource.Success -> {
                                                Log.d(TAG, "教务登录成功")
                                                true
                                            }

                                            is Resource.Error -> {
                                                Log.d(TAG, "教务登录失败")
                                                false
                                            }

                                            else -> false
                                        }

                                    }

                                    is Resource.Error -> {
                                        Log.d(TAG, "sso登录失败:${result.msg}")
                                        false
                                    }

                                    else -> false
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "登录重试异常: ${e.message}")
                                false
                            }
                        }

                    if (reloginSuccess) {
                        Log.d(TAG, "重新登录成功，重试原始请求...")
                        execute(request)
                    } else {
                        Log.d(TAG, "重新登录失败")
                        call
                    }
                } else {
                    call
                }
            }
        }

        return client
    }

    val moocClient: HttpClient by lazy { createBaseClient(baseUrl = MOOC_LOCATION) }
    val ssoAuthClient: HttpClient by lazy { createBaseClient(baseUrl = SSO_AUTH_URL) }

    val ssoEhallClient: HttpClient by lazy { createBaseClient(baseUrl = SSO_EHALL_URL) }

    val educationClientForLogin: HttpClient by lazy { createBaseClient(baseUrl = EDUCA_LOGIN_URL) }

    val educationClientForService: HttpClient by lazy {
        createBaseClient(baseUrl = EDUCA_LOGIN_URL, addRetryInterceptor = true)
    }

    val campusClient: HttpClient by lazy { createBaseClient(baseUrl = CAMPUS_CARD_LOCATION) }

    /** 清理 Client 缓存和 Cookie */
    fun clearClient() {
        MMKV.mmkvWithID(MMKV_ID).clear()
    }
}
