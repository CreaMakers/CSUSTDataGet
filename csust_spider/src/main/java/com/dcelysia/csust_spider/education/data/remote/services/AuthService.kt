package com.dcelysia.csust_spider.education.data.remote.services

import com.dcelysia.csust_spider.core.AESUtils
import com.dcelysia.csust_spider.core.RetrofitUtils
import com.dcelysia.csust_spider.education.data.remote.api.EduLoginApi
import com.dcelysia.csust_spider.education.data.remote.error.EduHelperError
import com.dcelysia.csust_spider.mooc.data.remote.api.SSOAuthApi
import com.dcelysia.csust_spider.mooc.data.remote.dto.LoginForm
import org.jsoup.Jsoup
import retrofit2.Response

object AuthService {
    private const val EDUCATION_SERVICE = "http://xk.csust.edu.cn/sso.jsp"

    private val loginApi by lazy {
        RetrofitUtils.instanceEduLogin.create(EduLoginApi::class.java)
    }
    private val ssoApi by lazy {
        RetrofitUtils.instanceSSOAuth.create(SSOAuthApi::class.java)
    }

    suspend fun CheckLoginStates(): Boolean {
        val response = loginApi.checkLoginStates()
        if (isLoginPage(response)) {
            throw EduHelperError.NotLoggedIn("登录失效，请重新登录")
        }
        return true
    }

    suspend fun login(account: String, password: String): Boolean {
        if (account.isBlank() || password.isBlank()) return false

        RetrofitUtils.clearEducationAuthSession()
        val entryResponse = loginApi.login()
        if (!entryResponse.isSuccessful) return false

        val formResponse = ssoApi.getLoginForm(service = EDUCATION_SERVICE)
        val loginForm = formResponse.body()
            ?.takeIf { formResponse.isSuccessful }
            ?.let(::parseLoginForm)
            ?: return false

        val captchaResponse = ssoApi.checkNeedCaptcha(account, System.currentTimeMillis())
        if (!captchaResponse.isSuccessful || captchaResponse.body()?.isNeed != false) return false

        var loginResponse = ssoApi.login(
            service = EDUCATION_SERVICE,
            username = account,
            password = AESUtils.encryptPassword(password, loginForm.pwdEncryptSalt),
            execution = loginForm.execution
        )
        if (loginResponse.code() == 401 || !loginResponse.isSuccessful) return false

        val loginHtml = loginResponse.body().orEmpty()
        val loginDocument = Jsoup.parse(loginHtml)
        val continueForm = loginDocument.selectFirst("form#continue")
        if (continueForm != null) {
            val continueExecution = findContinueExecution(loginHtml) ?: return false
            loginResponse = ssoApi.continueLogin(
                service = EDUCATION_SERVICE,
                execution = continueExecution
            )
            if (!loginResponse.isSuccessful) return false
        }

        val finalUrl = loginResponse.raw().request.url
        if (finalUrl.host != "xk.csust.edu.cn") return false

        val homeResponse = loginApi.checkLoginStates()
        val homeUrl = homeResponse.raw().request.url
        val homeHtml = homeResponse.body().orEmpty()
        return homeResponse.isSuccessful &&
            homeUrl.host == "xk.csust.edu.cn" &&
            homeUrl.encodedPath.substringBefore(';') == "/jsxsd/framework/xsMain.jsp" &&
            Jsoup.parse(homeHtml)
                .selectFirst("input[name=username], input[type=password]") == null
    }

    internal fun parseLoginForm(html: String): LoginForm? {
        val document = Jsoup.parse(html)
        val passwordSalt = document.selectFirst("input#pwdEncryptSalt")
            ?.attr("value")
            ?.takeIf(String::isNotBlank)
            ?: return null
        val execution = document.selectFirst("input[name=execution]")
            ?.attr("value")
            ?.takeIf(String::isNotBlank)
            ?: return null
        return LoginForm(passwordSalt, execution)
    }

    internal fun findContinueExecution(html: String): String? = Jsoup.parse(html)
        .selectFirst("form#continue input[name=execution]")
        ?.attr("value")
        ?.takeIf(String::isNotBlank)

    private fun isLoginPage(response: Response<String>): Boolean =
        isLoginPageHtml(response.body().orEmpty())

    /**
     * 判断响应 Html 是否为登录页/认证页。
     *
     * 不用具体中文文案（"用户登录"/"请输入账号"/"请先登录系统" 都会变），
     * 只要出现账号或密码输入框就视为登录页。
     */
    fun isLoginPageHtml(html: String): Boolean {
        val body = html.trim()
        // 先做廉价的前置判断，避免对每个查询响应都跑一遍 Jsoup
        if (body.isEmpty() ||
            !body.contains("<input", ignoreCase = true) ||
            (!body.contains("password", ignoreCase = true) &&
                !body.contains("username", ignoreCase = true) &&
                !body.contains("form#continue", ignoreCase = true))
        ) {
            return false
        }
        return runCatching {
            Jsoup.parse(body).selectFirst("input[name=username], input[type=password]") != null
        }.getOrDefault(false)
    }

    suspend fun LoginOut(): Boolean {
        val response = loginApi.loginout(System.currentTimeMillis())
        if (!response.isSuccessful) {
            throw EduHelperError.NotLoggedIn("登出失败,${response.body()}")
        }
        RetrofitUtils.ClearClient("EducationClient")
        return true
    }
}
