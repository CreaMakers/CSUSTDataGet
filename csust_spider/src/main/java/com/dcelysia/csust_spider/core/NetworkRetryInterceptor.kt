package com.dcelysia.csust_spider.core

import android.util.Log
import com.dcelysia.csust_spider.education.data.remote.EducationData
import com.dcelysia.csust_spider.education.data.remote.services.AuthService
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

//统一认证及教务登录拦截器
//教务系统的 404/空响应分两种，必须分开处理：
//  1. 瞬时抖动：负载均衡/后端会话刚落，同一个请求一会 200 一会 404（日志里 date 头同秒）。
//     这类只做短延迟重试，不要动 cookie，也不要去重新登录。
//  2. 真的会话失效：拿到登录页，或重试若干次仍是 404/空响应。
//     这时才清理统一认证 + 教务会话 cookie，带账号密码重新登录，然后重试一次。
//重登后仍拿不到业务数据时，原样返回响应，由上层提示重新登录，不再抛异常。
class NetworkRetryInterceptor(
    @Suppress("UNUSED_PARAMETER") mmkv: MMKV,
    @Suppress("UNUSED_PARAMETER") key: String
) : Interceptor {

    private val TAG = "NetworkRetryInterceptor"

    private companion object {
        /** 允许重登录的教务/统一认证域名，避免其他域名的 404 也触发登录流程。 */
        private val EDUCATION_HOSTS = setOf(
            "xk.csust.edu.cn",
            "authserver.csust.edu.cn",
            "ehall.csust.edu.cn"
        )

        /** 瞬时抖动重试次数与间隔（教务后端/负载均衡偶发 404 空响应）。 */
        private const val TRANSIENT_RETRY_TIMES = 3
        private const val TRANSIENT_RETRY_DELAY_MS = 300L

        /** 短时间内并发请求同时失效时，只允许一个请求真正走登录，其余复用刚刷新的会话。 */
        private const val RELOGIN_COOLDOWN_MS = 15_000L
        private val reloginMutex = Mutex()
        @Volatile
        private var lastReloginAt = 0L
    }

    private fun isReloginFresh(): Boolean =
        System.currentTimeMillis() - lastReloginAt < RELOGIN_COOLDOWN_MS

    /** 真正执行一次统一认证 + 教务登录，并记录成功时间。 */
    private suspend fun performLogin(): Boolean {
        // 进入临界区后可能已被其他请求刷新过会话
        if (isReloginFresh()) {
            Log.d(TAG, "已有请求刚完成重新登录，直接重试当前请求")
            return true
        }
        Log.d(TAG, "网络库得到的账号密码：${EducationData.studentId},${EducationData.studentPassword}")
        // 只清理统一认证与教务的会话 cookie，保留校园卡等其他站点 cookie。
        // 注意：这里绝不能清空整个 cookie jar，否则会把仍然有效的教务会话一并丢掉。
        val eduSuccess = AuthService.login(
            EducationData.studentId,
            EducationData.studentPassword
        )
        Log.d(TAG, "教务登录结果: $eduSuccess")
        // AuthService.login 内部已校验 xsMain.jsp 会话，这里再确认一次，避免把登录页当成功。
        val success = eduSuccess && AuthService.CheckLoginStates()
        if (success) {
            lastReloginAt = System.currentTimeMillis()
        }
        return success
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val inEducationHost = host in EDUCATION_HOSTS

        var response = chain.proceed(request)
        var body = response.body?.string().orEmpty()
        var contentType = response.body?.contentType()
        var attempt = 0

        // 阶段 1：瞬时抖动短延迟重试。教务后端偶发 404/空响应，一秒内同一请求又会 200。
        while (inEducationHost && isTransientFailure(response, body) && attempt < TRANSIENT_RETRY_TIMES) {
            attempt++
            Log.w(
                TAG,
                "瞬时失败 code=${response.code} len=${body.length}，第 $attempt 次重试 url=${request.url} " +
                    "cookies=${summarizeCookies(request.header("Cookie"))} ua=${request.header("User-Agent")}"
            )
            Thread.sleep(TRANSIENT_RETRY_DELAY_MS)
            val retried = runCatching { chain.proceed(request) }.getOrNull() ?: break
            response = retried
            body = retried.body?.string().orEmpty()
            contentType = retried.body?.contentType()
        }

        val hitLoginPage = AuthService.isLoginPageHtml(body)
        val hitSessionLost = inEducationHost && (response.code == 404 || response.code >= 500)
        Log.d(
            TAG,
            "host=$host, hitLoginPage=$hitLoginPage, hitSessionLost=$hitSessionLost, " +
                "code=${response.code}, len=${body.length}, attempt=$attempt, " +
                "cookies=${summarizeCookies(request.header("Cookie"))}, ua=${request.header("User-Agent")}"
        )

        // 阶段 2：确实拿不到业务数据时才重新登录（未登录过账号时不动）
        val hasBoundAccount =
            EducationData.studentId.isNotBlank() && EducationData.studentPassword.isNotBlank()
        if ((hitLoginPage || hitSessionLost) && hasBoundAccount) {
            Log.d(TAG, "会话重试无效，开始自动登录流程...")
            val reloginSuccess = runBlocking(Dispatchers.IO) {
                try {
                    if (isReloginFresh()) {
                        Log.d(TAG, "已有请求刚完成重新登录，直接重试当前请求")
                        true
                    } else {
                        reloginMutex.withLock { performLogin() }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "登录重试异常: ${e.message}")
                    false
                }
            }

            if (reloginSuccess) {
                Log.d(TAG, "重新登录成功，重试原始请求...")
                val retryResponse = runCatching { chain.proceed(request) }.getOrNull()
                if (retryResponse != null) {
                    val retryBody = retryResponse.body?.string().orEmpty()
                    val retryContentType = retryResponse.body?.contentType()
                    if (retryResponse.code == 404 || AuthService.isLoginPageHtml(retryBody)) {
                        Log.w(TAG, "重试后仍未恢复教务会话，保留原始响应交由上层提示重新登录")
                    } else {
                        // 只要不是 404/登录页就交给上层，空正文由业务层自行判断
                        return rebuild(retryResponse, retryBody, retryContentType)
                    }
                } else {
                    Log.w(TAG, "重试原始请求失败，保留原始响应")
                }
            } else {
                Log.w(TAG, "自动重新登录失败，保留原始响应交由上层提示重新登录")
            }
        }

        // 响应体已被读取，重建后再交回调用方
        return rebuild(response, body, contentType)
    }

    /** 教务后端的瞬时抖动：404 空响应、5xx、或声明 HTML 却没有正文。 */
    private fun isTransientFailure(response: Response, body: String): Boolean =
        response.code == 404 ||
            response.code >= 500 ||
            (body.isBlank() && isHtmlResponse(response.body?.contentType()))

    private fun rebuild(response: Response, body: String, contentType: MediaType?): Response =
        response.newBuilder()
            .body(body.toResponseBody(contentType))
            .build()

    /** 声明是 HTML 的响应；教务页面全部是 HTML，空 HTML 说明没拿到页面。 */
    private fun isHtmlResponse(contentType: MediaType?): Boolean =
        contentType?.subtype?.contains("html", ignoreCase = true) == true

    /** 只打印 cookie 名和长度，足以判断有没有同名 cookie 被重复发送，又不泄露完整会话值。 */
    private fun summarizeCookies(raw: String?): String {
        if (raw.isNullOrBlank()) return "none"
        return raw.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(",") { pair -> "${pair.substringBefore('=')}(${pair.length})" }
    }
}
