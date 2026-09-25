package com.dcelysia.csust_spider.physicsexperiment.data.remote.service

import android.util.Base64
import android.util.Log
import com.dcelysia.csust_spider.core.AESUtils
import com.dcelysia.csust_spider.core.RetrofitUtils
import com.dcelysia.csust_spider.education.data.remote.services.AuthService
import com.dcelysia.csust_spider.mooc.data.remote.api.SSOAuthApi
import com.dcelysia.csust_spider.physicsexperiment.data.remote.PhysicsLabConfig
import com.dcelysia.csust_spider.physicsexperiment.data.remote.api.PhysicsLabApi
import com.dcelysia.csust_spider.physicsexperiment.data.remote.error.PhysicsLabError
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response as OkHttpResponse
import org.jsoup.Jsoup
import retrofit2.Response

/**
 * 物理实验系统的登录（三段式）：
 *
 * ```
 * ① 统一身份认证(CAS)：service = 网关自己的回调地址
 * ② 网关建立会话：access_token cookie 由网关用 Set-Cookie 下发（OkHttp 的 CookieJar 自动收下）
 * ③ 平台登录：POST login.aspx?...，参数是 Base64(用户名/平台密码)
 * ```
 *
 * ## ① 有两条路径（真机验证后补上）
 *
 * - **SSO 免密**：若客户端已持有统一认证的全局会话（cookie 里的 `CASTGC` / `TGT-...`，
 *   通常是绑定学号时登录教务留下的），CAS **不会再返回登录页**，而是直接把请求放行到
 *   网关回调并下发 `access_token`。此时无需提交账号密码。
 * - **账号密码**：没有全局会话时，CAS 返回登录页，走金智那套表单提交
 *   （字段与加密方式与教务完全一致，见 [SSOAuthApi] 与 [AESUtils]）。
 *
 * 两条路径最终都以"网关会话可用"为准（用 [isVpnSessionAlive] 判定），
 * 而不是以"有没有拿到 accessToken 字符串"为准。
 */
object PhysicsLabAuthService {

    private const val TAG = "PhysicsLabAuth"

    /** 网关下发的会话令牌 cookie 名。 */
    private const val COOKIE_ACCESS_TOKEN = "access_token"

    /**
     * accessToken 在重定向链里以 URL **fragment** 的形式出现（`#` 之后）：
     * `.../enclient/#/login/wxLoginLoading?accessToken=xxx&ssoLogoutUri=...`
     *
     * 正常情况下网关同时会用 `Set-Cookie` 下发同名 cookie，这条提取逻辑只是兜底。
     */
    private val ACCESS_TOKEN_REGEX = Regex("accessToken=([^&#]+)")

    private val ssoApi by lazy { RetrofitUtils.instanceSSOAuth.create(SSOAuthApi::class.java) }
    private val platformApi by lazy { RetrofitUtils.instancePhysicsLab.create(PhysicsLabApi::class.java) }

    /**
     * 完整登录：统一认证 → VPN 会话 → 平台会话。
     *
     * @throws PhysicsLabError.VpnLoginFailed 统一认证这一层失败（账号密码、验证码、网关异常）
     * @throws PhysicsLabError.NeedManualLogin 平台这一层失败（平台密码不对或未提供）
     * @throws PhysicsLabError.NetworkError 网络异常
     */
    suspend fun login(account: String, authPassword: String, platformPassword: String) {
        ensureVpnSession(account, authPassword)
        loginPlatform(account, platformPassword)
    }

    // ------------------------------------------------------------------
    // ① + ② 统一认证 → VPN 会话
    // ------------------------------------------------------------------

    /**
     * 确保网关会话可用。
     *
     * 注意这里**只清网关域的 cookie**：统一认证的 `CASTGC` / `TGT` 必须保留，
     * 否则每次都要重新提交账号密码，也会把用户其它系统的登录态踢掉。
     */
    private suspend fun ensureVpnSession(account: String, authPassword: String) {
        RetrofitUtils.clearPhysicsLabSession()

        val entryResponse = ssoApi.getLoginForm(service = PhysicsLabConfig.VPN_CAS_CALLBACK)
        val entryHtml = entryResponse.body().orEmpty()
        val loginForm = AuthService.parseLoginForm(entryHtml)

        // ---- 路径 A：统一认证已有全局会话，本次请求被直接放行到网关回调 ----
        if (loginForm == null) {
            Log.d(TAG, "未取到统一认证登录表单，按“已有全局会话(SSO)”处理")
            if (ensureGatewaySession(entryResponse)) {
                Log.d(TAG, "SSO 免密登录成功")
                return
            }
            throw PhysicsLabError.VpnLoginFailed("统一认证登录页解析失败，请稍后重试")
        }

        // ---- 路径 B：正常账号密码流程 ----
        val captchaResponse = ssoApi.checkNeedCaptcha(account, System.currentTimeMillis())
        if (!captchaResponse.isSuccessful || captchaResponse.body()?.isNeed != false) {
            throw PhysicsLabError.VpnLoginFailed("该账号需要验证码，请在手机网页登录一次后再试")
        }

        var loginResponse = ssoApi.login(
            service = PhysicsLabConfig.VPN_CAS_CALLBACK,
            username = account,
            password = AESUtils.encryptPassword(authPassword, loginForm.pwdEncryptSalt),
            execution = loginForm.execution
        )
        // 统一认证用 401 表示凭据错误，不能当成网络失败
        if (loginResponse.code() == 401) {
            throw PhysicsLabError.VpnLoginFailed("统一认证学号或密码错误，请重新绑定学号")
        }
        if (!loginResponse.isSuccessful) {
            throw PhysicsLabError.VpnLoginFailed("统一认证请求失败（${loginResponse.code()}）")
        }

        val body = loginResponse.body().orEmpty()

        // 请求成功但仍停在登录页 = 认证没通过。
        // 除密码错误外，风控要求验证码时也是这个表现（实测此时 checkNeedCaptcha 仍返回 false），
        // 那种情况只能让用户手动登录一次，所以归到 NeedManualLogin 而不是 VpnLoginFailed。
        if (AuthService.parseLoginForm(body) != null) {
            throw PhysicsLabError.NeedManualLogin("统一认证登录未通过，可能需要验证码，请手动登录一次后再试")
        }

        // CAS 的老毛病：可能需要再提交一次 continue 表单
        if (Jsoup.parse(body).selectFirst("form#continue") != null) {
            val continueExecution = AuthService.findContinueExecution(body)
                ?: throw PhysicsLabError.VpnLoginFailed("统一认证二次确认页解析失败")
            loginResponse = ssoApi.continueLogin(
                service = PhysicsLabConfig.VPN_CAS_CALLBACK,
                execution = continueExecution
            )
            if (!loginResponse.isSuccessful) {
                throw PhysicsLabError.VpnLoginFailed("统一认证二次确认失败（${loginResponse.code()}）")
            }
        }

        if (!ensureGatewaySession(loginResponse)) {
            throw PhysicsLabError.VpnLoginFailed("未能取得网关会话，请稍后重试")
        }
    }

    /**
     * 确认网关会话已建立；万一网关没有下发 cookie，则从重定向链里取出 accessToken 兜底注入。
     *
     * 真机日志显示：网关在回调那一跳会用 `Set-Cookie` 下发 4 次 `access_token`，
     * OkHttp 的 CookieJar 会自动保存，所以常规情况下这里第一次探测就会返回 true。
     */
    private suspend fun ensureGatewaySession(response: Response<*>): Boolean {
        if (isVpnSessionAlive()) return true

        val token = extractAccessToken(response.raw()) ?: return false
        Log.d(TAG, "网关未下发 cookie，改用 URL 里的 accessToken 兜底注入（长度 ${token.length}）")
        injectAccessTokenCookie(token)
        return isVpnSessionAlive()
    }

    /**
     * 提取 accessToken（兜底用）。
     *
     * ① 最终响应的 URL 上可能就带着 —— OkHttp 的 [okhttp3.HttpUrl] 会保留 fragment；
     * ② 否则回溯重定向链的 `Location` 头：
     * ```
     * POST /authserver/login          → 302 .../cas/callback?ticket=ST-xxx
     * GET  .../cas/callback?ticket=…  → 302 .../enclient/#/login/wxLoginLoading?accessToken=xxx
     * GET  /enclient/                 → 200
     * ```
     */
    private fun extractAccessToken(response: OkHttpResponse?): String? {
        response?.request?.url?.toString()?.let { url ->
            ACCESS_TOKEN_REGEX.find(url)?.let { return it.groupValues[1] }
        }
        var hop: OkHttpResponse? = response
        while (hop != null) {
            val location = hop.header("Location")
            if (!location.isNullOrBlank()) {
                ACCESS_TOKEN_REGEX.find(location)?.let { return it.groupValues[1] }
            }
            hop = hop.priorResponse
        }
        return null
    }

    /**
     * 把 accessToken 落成 `access_token` cookie。
     *
     * [RetrofitUtils.totalCookieJar] 是 OkHttp 的 `CookieJar`，`saveFromResponse`
     * 本身就是公开接口，所以不需要改动 cookie jar 的实现。
     */
    private fun injectAccessTokenCookie(accessToken: String) {
        val cookie = Cookie.Builder()
            .name(COOKIE_ACCESS_TOKEN)
            .value(accessToken)
            .domain(PhysicsLabConfig.VPN_HOST)
            .path("/")
            .build()
        RetrofitUtils.totalCookieJar.saveFromResponse(
            PhysicsLabConfig.VPN_ORIGIN.toHttpUrl(),
            listOf(cookie)
        )
    }

    // ------------------------------------------------------------------
    // ③ 平台登录
    // ------------------------------------------------------------------

    /**
     * 平台登录。
     *
     * 两个实测要点：
     * 1. **先预热**：网关需要一次往返把"目标站会话"建起来，否则第一个 POST 会被
     *    302 打回登录页（表现为登录失败但账号密码其实没错）；
     * 2. 参数**不做 URL 编码**，Base64 用 `NO_WRAP`（默认的 `Base64.DEFAULT` 会插入换行，
     *    而平台是把它当普通 query 参数解析的）。
     */
    private suspend fun loginPlatform(account: String, platformPassword: String) {
        // 预热：失败不影响后续判断，只是让网关先把目标站会话建好
        runCatching { platformApi.index() }
            .onFailure { Log.d(TAG, "平台预热请求失败（可忽略）：${it.message}") }

        val url = buildString {
            append(PhysicsLabConfig.platformBaseUrl)
            append("login.aspx?UserType=").append(PhysicsLabConfig.USER_TYPE)
            append("&txtUserName=").append(base64(account))
            append("&txtPass=").append(base64(platformPassword))
        }

        val response = platformApi.login(url)
        if (!response.isSuccessful) {
            throw PhysicsLabError.NetworkError("平台登录请求失败（${response.code()}）")
        }

        val landingUrl = response.raw().request.url.toString()
        val body = response.body().orEmpty().trim()

        if (landingUrl.contains("/enclient")) {
            // 被弹回网关自己的登录页，说明网关会话没建立起来
            throw PhysicsLabError.VpnLoginFailed("网关会话未能建立，请稍后重试")
        }
        if (!body.startsWith("true")) {
            // 落到平台的 login.html / login.aspx，说明平台层没通过
            throw PhysicsLabError.NeedManualLogin(
                "物理实验平台登录未通过，请确认平台密码（可能与统一认证不同）"
            )
        }
        Log.d(TAG, "平台登录成功")
    }

    /** 平台要求的是「UTF-8 字节 → 标准 Base64」，且不能带换行。 */
    private fun base64(raw: String): String =
        Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    /**
     * 只重建平台会话（网关会话仍然有效时使用）。
     *
     * 供 [com.dcelysia.csust_spider.core.PhysicsLabRetryInterceptor] 在检测到
     * "平台层会话过期"时调用 —— 平台会话寿命很短（实测不到 45 分钟），
     * 而网关会话长得多，没必要每次都把统一认证重走一遍。
     *
     * @throws PhysicsLabError.NeedManualLogin 平台密码不对或未提供
     */
    suspend fun renewPlatformSession(account: String, platformPassword: String) {
        loginPlatform(account, platformPassword)
    }

    // ------------------------------------------------------------------
    // 会话探针
    // ------------------------------------------------------------------

    /**
     * 网关会话是否可用。
     *
     * 用网关的用户信息接口判断：返回 JSON 且 code=200 才算有效。
     * 这是整个登录流程的**唯一成功判据** —— 比"有没有拿到某个字符串"可靠。
     */
    suspend fun isVpnSessionAlive(): Boolean {
        return runCatching {
            val response = platformApi.vpnUserInfo(PhysicsLabConfig.VPN_USER_INFO_URL)
            val body = response.body().orEmpty()
            response.isSuccessful && body.contains("\"code\":\"200\"")
        }.getOrDefault(false)
    }
}
