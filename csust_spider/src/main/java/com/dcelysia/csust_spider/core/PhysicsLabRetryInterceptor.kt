package com.dcelysia.csust_spider.core

import android.util.Log
import com.dcelysia.csust_spider.education.data.remote.EducationData
import com.dcelysia.csust_spider.physicsexperiment.data.remote.PhysicsLabConfig
import com.dcelysia.csust_spider.physicsexperiment.data.remote.PhysicsLabData
import com.dcelysia.csust_spider.physicsexperiment.data.remote.service.PhysicsLabAuthService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * 物理实验平台的会话自动续期拦截器（结构对齐 [NetworkRetryInterceptor]）。
 *
 * ## 为什么要单独写一个
 *
 * 物理实验平台的**两层会话都在 `vpn.csust.edu.cn` 一个 host 下**，
 * 教务那套靠 host 白名单区分的方式在这里失效，只能靠**落地 URL** 区分：
 *
 * | 现象 | 含义 | 处理 |
 * |---|---|---|
 * | 落地含 `/enclient` | 网关会话失效 | 完整重登录（统一认证 → 网关 → 平台） |
 * | 落地含 `/login.html`，或正文是平台登录页 | 平台会话失效 | 只重登平台（`login.aspx`） |
 *
 * ## 几个必要的保护
 *
 * - **本拦截器绝不能挂在登录请求自己的 client 上**，否则会话失效时会递归调用自己。
 *   登录用 [RetrofitUtils.PhysicsLabClientForAuth]，业务查询用
 *   [RetrofitUtils.PhysicsLabClientForService]，与教务的 Login/Service 拆分同一个思路。
 * - 互斥锁 + 冷却时间：短时间内的并发请求同时失效时，只让一个真正去登录。
 * - 重登失败或重试后仍是登录页，就**原样返回响应**，由上层提示用户，不抛异常。
 */
class PhysicsLabRetryInterceptor : Interceptor {

    private companion object {
        const val TAG = "PhysicsLabRetry"

        /** 网关会话失效的落地特征 */
        const val MARK_VPN_LOGIN = "/enclient"

        /** 平台会话失效的落地特征 */
        const val MARK_PLATFORM_LOGIN = "/login.html"

        /** 重登冷却：刚登录过就不再重复登录（并发请求共用结果） */
        const val RELOGIN_COOLDOWN_MS = 15_000L

        val reloginMutex = Mutex()

        @Volatile
        var lastReloginAt = 0L
    }

    /** 会话失效的两种类型 */
    private enum class SessionLoss { VPN, PLATFORM }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // 只管物理实验平台（网关域）；其它域名原样放行
        if (request.url.host != PhysicsLabConfig.VPN_HOST) {
            return chain.proceed(request)
        }

        val response = chain.proceed(request)
        val body = response.body.string()
        val contentType = response.body.contentType()

        val loss = classify(response, body)
        if (loss == null) {
            return rebuild(response, body, contentType)
        }

        Log.d(TAG, "检测到会话失效（$loss），落地=${response.request.url}")

        val reloginSuccess = runBlocking(Dispatchers.IO) {
            try {
                if (isReloginFresh()) {
                    Log.d(TAG, "刚重登过，直接重试当前请求")
                    true
                } else {
                    reloginMutex.withLock { performRelogin(loss) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "自动重登录异常：${e.message}")
                false
            }
        }

        if (!reloginSuccess) {
            Log.w(TAG, "自动重登录未成功，保留原始响应交由上层提示")
            return rebuild(response, body, contentType)
        }

        val retried = runCatching { chain.proceed(request) }.getOrNull()
            ?: return rebuild(response, body, contentType)

        val retriedBody = retried.body.string()
        if (classify(retried, retriedBody) != null) {
            Log.w(TAG, "重试后仍是登录页，保留原始响应")
            return rebuild(response, body, contentType)
        }
        return rebuild(retried, retriedBody, retried.body.contentType())
    }

    private fun isReloginFresh(): Boolean =
        System.currentTimeMillis() - lastReloginAt < RELOGIN_COOLDOWN_MS

    /** 真正执行一次重登录；成功则刷新时间戳。 */
    private suspend fun performRelogin(loss: SessionLoss): Boolean {
        if (isReloginFresh()) return true

        val account = EducationData.studentId
        val authPassword = EducationData.studentPassword
        val platformPassword = PhysicsLabData.platformPassword
        if (account.isBlank() || authPassword.isBlank()) {
            Log.w(TAG, "未绑定学号，无法自动重登录")
            return false
        }

        val success = when (loss) {
            // 网关会话没了，整条链路重走（统一认证 → 网关 → 平台）
            SessionLoss.VPN -> run {
                // 平台密码缺失时，网关会话仍可重建，平台层的失败交由上层提示
                PhysicsLabAuthService.login(account, authPassword, platformPassword)
                true
            }
            // 网关会话还好，只需要重登平台
            SessionLoss.PLATFORM -> run {
                if (platformPassword.isBlank()) {
                    Log.w(TAG, "缺少平台密码，无法自动重登平台")
                    false
                } else {
                    PhysicsLabAuthService.renewPlatformSession(account, platformPassword)
                    true
                }
            }
        }
        if (success) lastReloginAt = System.currentTimeMillis()
        return success
    }

    /** 判断这次响应是不是"会话失效"。 */
    private fun classify(response: Response, body: String): SessionLoss? {
        val landingUrl = response.request.url.toString()
        return when {
            landingUrl.contains(MARK_VPN_LOGIN) -> SessionLoss.VPN
            landingUrl.contains(MARK_PLATFORM_LOGIN) -> SessionLoss.PLATFORM
            isPlatformLoginPage(body) -> SessionLoss.PLATFORM
            else -> null
        }
    }

    /** 平台自己的登录页（登录框 id 是固定的）。 */
    private fun isPlatformLoginPage(body: String): Boolean =
        body.contains("id=\"txtUserName\"") || body.contains("id=\"frmUser\"")

    /** 响应体已被读取，重建后再交回调用方。 */
    private fun rebuild(response: Response, body: String, contentType: MediaType?): Response =
        response.newBuilder()
            .body(body.toResponseBody(contentType))
            .build()
}
