package com.dcelysia.csust_spider.core

import android.util.Log
import com.dcelysia.csust_spider.education.data.remote.EducationData
import com.dcelysia.csust_spider.physicsexperiment.data.remote.PhysicsLabConfig
import com.dcelysia.csust_spider.physicsexperiment.data.remote.PhysicsLabData
import com.dcelysia.csust_spider.physicsexperiment.data.remote.service.PhysicsLabAuthService
import java.io.IOException
import java.net.ProtocolException
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

        // 首次请求。网关会话失效时 `/http/<token>/…` 会 302 指回自身（实测），
        // OkHttp 跟到重定向上限后抛 ProtocolException —— 这是"网关会话失效"的确定信号，
        // 必须就地翻译成 [SessionLoss.VPN]，否则异常会直接穿出拦截器，
        // 下面的 classify 与自动重登根本不会执行。
        //
        // ⚠️ 只有 ProtocolException 算会话失效：断网 / 超时 / 连接被拒这类普通 IOException
        // 一律原样上抛。因为误判会触发重登，而重登会清掉用户会话（见 [performRelogin]），
        // 等于把一次网络抖动升级成"必须重新绑定学号"。
        var redirectLoop: ProtocolException? = null
        val first = try {
            val response = chain.proceed(request)
            ConsumedResponse(response, response.body.string(), response.body.contentType())
        } catch (e: ProtocolException) {
            Log.w(TAG, "请求被网关重定向自环中断，判定网关会话失效：${e.message}")
            redirectLoop = e
            null
        }

        // 拿不到响应体时按网关会话失效处理；否则用落地 URL 与正文判类型
        val loss = if (first == null) {
            SessionLoss.VPN
        } else {
            classify(first.response, first.body)
                ?: return rebuild(first.response, first.body, first.contentType)
        }

        Log.d(TAG, "检测到会话失效（$loss），落地=${first?.response?.request?.url ?: "重定向自环"}")

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
            return fallback(first, redirectLoop)
        }

        val retried = try {
            val response = chain.proceed(request)
            ConsumedResponse(response, response.body.string(), response.body.contentType())
        } catch (e: IOException) {
            Log.w(TAG, "重登后重试请求失败，保留原始响应交由上层提示：${e.message}")
            null
        } ?: return fallback(first, redirectLoop)

        if (classify(retried.response, retried.body) != null) {
            Log.w(TAG, "重试后仍是登录页，保留原始响应")
            return fallback(first, redirectLoop)
        }
        return rebuild(retried.response, retried.body, retried.contentType)
    }

    /**
     * 兜底返回：手里有原始响应就原样交回（保持既有语义 —— 不抛异常，由上层提示用户）；
     * 首次请求就是被重定向自环中断、手里没有任何响应时，只能把原始异常抛出去。
     */
    private fun fallback(first: ConsumedResponse?, redirectLoop: ProtocolException?): Response =
        first?.let { rebuild(it.response, it.body, it.contentType) }
            ?: throw requireNotNull(redirectLoop)

    /** 响应体已被读取（OkHttp 的 body 只能读一次），连同 contentType 一起带着走。 */
    private class ConsumedResponse(
        val response: Response,
        val body: String,
        val contentType: MediaType?,
    )

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
            // 网关自己的错误页：会话失效或被拒时返回它（判定方式见 PhysicsLabConfig）
            PhysicsLabConfig.isGatewayErrorPage(body) -> SessionLoss.VPN
            PhysicsLabConfig.isPlatformLoginPage(body) -> SessionLoss.PLATFORM
            else -> null
        }
    }


    /** 响应体已被读取，重建后再交回调用方。 */
    private fun rebuild(response: Response, body: String, contentType: MediaType?): Response =
        response.newBuilder()
            .body(body.toResponseBody(contentType))
            .build()
}
