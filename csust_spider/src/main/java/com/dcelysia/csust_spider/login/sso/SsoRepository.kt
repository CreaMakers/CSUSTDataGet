package com.dcelysia.csust_spider.login.sso

import android.util.Log
import android.util.Log.e
import com.dcelysia.csust_spider.core.AESUtils
import com.dcelysia.csust_spider.core.KtorUtils
import com.dcelysia.csust_spider.core.Resource
import com.dcelysia.csust_spider.mooc.data.remote.dto.CheckCaptchaResponse
import com.dcelysia.csust_spider.mooc.data.remote.dto.LoginForm
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup

class SsoRepository private constructor() {
    companion object {
        val instance: SsoRepository by lazy { SsoRepository() }
        private const val TAG = "SsoRepository"

        // 1. 定义常量，确保所有请求使用完全相同的 Service 和 User-Agent
        private const val SERVICE_URL = "https://ehall.csust.edu.cn/login"
    }

    private suspend fun checkNeedCaptcha(username: String): Boolean {
        return try {
            val timestamp = System.currentTimeMillis()
            val response = KtorUtils.ssoAuthClient.get("authserver/checkNeedCaptcha.htl") {
                parameter("username", username)
                parameter("_", timestamp)
            }
            val responseText = response.bodyAsText()
            val json = Json { ignoreUnknownKeys = true }
            val data = json.decodeFromString<CheckCaptchaResponse>(responseText)
            Log.d(TAG,"成功解析json:${data.isNeed}")
            data.isNeed
        } catch (e: Exception) {
            Log.e(TAG, "Check captcha failed", e)
            false
        }
    }

    private suspend fun getLoginForm(): Pair<LoginForm?, Boolean> {
        return try {
            Log.d(TAG, "进入Mooc getLoginForm")

            val response = KtorUtils.ssoAuthClient.get("authserver/login") {
                parameter("service", SERVICE_URL)
            }

            val finalUrl = response.request.url.toString()
            Log.d(TAG, "getLoginForm finalUrl: $finalUrl")
            Log.d(TAG, "getLoginForm finalhtml: ${response.bodyAsText()}")

            // 检查是否已经登录 (重定向到了 ehall)
            if (finalUrl.contains("https://ehall.csust.edu.cn")) {
                return Pair(null, true)
            }

            val html = response.bodyAsText()
            if (html.isEmpty()) {
                return Pair(null, false)
            }

            val document = Jsoup.parse(html)
            val pwdEncryptSaltInput = document.select("input#pwdEncryptSalt").firstOrNull()
            val executionInput = document.select("input#execution").firstOrNull()

            if (pwdEncryptSaltInput == null || executionInput == null) {
                // 有时候服务器返回的页面可能是错误的，记录一下 body 以便调试
                // Log.e(TAG, "解析失败，HTML内容: $html")
                return Pair(null, false)
            }

            val loginForm = LoginForm(
                pwdEncryptSalt = pwdEncryptSaltInput.attr("value"),
                execution = executionInput.attr("value")
            )

            Pair(loginForm, false)
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(null, false)
        }
    }

    fun login(username: String, password: String) = flow {
        emit(Resource.Loading())
        Log.d(TAG, "进入Mooc登陆")

        // 1. 获取登录表单
        val (loginForm, isAlreadyLoggedIn) = getLoginForm()

        if (isAlreadyLoggedIn) {
            Log.d(TAG,"已经登陆过了")
            emit(Resource.Success(true))
            return@flow
        }

        if (loginForm == null) {
            emit(Resource.Error("网络错误：无法获取登录表单"))
            return@flow
        }

        // 2. 检查验证码 (保持 UA 一致)
        val needCaptcha = checkNeedCaptcha(username)
        if (needCaptcha) {
            emit(Resource.Error("账号状态异常，请在手机网页登录一次\n网址: https://authserver.csust.edu.cn/authserver/login"))
            return@flow
        }

        // 3. 加密密码
        val encryptedPassword = AESUtils.encryptPassword(password, loginForm.pwdEncryptSalt)

        // 4. 执行登录
        // 关键点：添加 Headers (User-Agent 和 Referer)
        var loginResponse = KtorUtils.ssoAuthClient.submitForm(
            url = "authserver/login",
            formParameters = Parameters.build {
                append("service", SERVICE_URL)
                append("username", username)
                append("password", encryptedPassword)
                append("captcha", "")
                append("_eventId", "submit")
                append("cllt", "userNameLogin")
                append("dllt", "generalLogin")
                append("lt", "")
                append("execution", loginForm.execution)
            }
        ) {

            header(HttpHeaders.Referrer, "https://authserver.csust.edu.cn/authserver/login?service=$SERVICE_URL")
        }
        if (loginResponse.status == HttpStatusCode.Found){
            val location = loginResponse.headers[HttpHeaders.Location]
            if (location != null){
                Log.d(TAG, "登录成功，获取到 Ticket，正在跳转到: $location")
                loginResponse = KtorUtils.ssoAuthClient.get(location)
            }
        }
        val finalUrl = loginResponse.request.url.toString()
        Log.d(TAG, "Final Page URL: $finalUrl")
        Log.d(TAG, "Login html: ${loginResponse.bodyAsText()}")
        // Ktor 的 Url 匹配可能需要更宽容一点，因为可能有 http/https 或端口差异
        if (finalUrl.contains("ehall.csust.edu.cn/index.html") ||
            finalUrl.contains("ehall.csust.edu.cn/default/index.html")) {
            emit(Resource.Success(true))
        } else {
            // 调试用：如果失败，打印一下返回的 HTML，看看是不是有错误提示
            emit(Resource.Error("登录失败，请检查用户名和密码"))
        }

    }.catch { e ->
        e.printStackTrace()
        Log.e(TAG, "登录流程异常: ${e.message}")
        emit(Resource.Error("网络错误: ${e.message}"))
    }
    /**
     * 登出
     */
    fun logout() = flow {
        emit(Resource.Loading())
        // 2. 登出 SSO 门户
        KtorUtils.ssoEhallClient.get("logout")

        // 3. 登出 Auth Server
        KtorUtils.ssoAuthClient.get("authserver/logout")

        // 4. 清理本地 CookieJar (通过 KtorUtils 暴露的方法)
        // 确保清理底层的 PersistentCookieJar
        KtorUtils.clearClient()

        emit(Resource.Success(true))
    }
        .catch {e->
            e(TAG, "Logout failed", e)
            // 即使网络失败，本地状态也必须清理
            KtorUtils.clearClient()
            emit(Resource.Error("登出失败")) // 依然视为登出成功
        }
}
