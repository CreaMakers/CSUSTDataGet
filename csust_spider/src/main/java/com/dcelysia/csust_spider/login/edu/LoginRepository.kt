package com.dcelysia.csust_spider.login.edu

import android.util.Log.e
import com.dcelysia.csust_spider.core.KtorUtils
import com.dcelysia.csust_spider.core.Resource
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

class LoginRepository private constructor(){
    companion object{
        private val TAG = "LoginRepository"
        val instance by lazy { LoginRepository() }
    }

    fun login() = flow {
        emit(Resource.Loading())

        // 1. 预访问，建立基本的 cookie 状态
        KtorUtils.educationClientForLogin.get("sso.jsp")

        // 2. 请求认证服务器登录接口
        var response = KtorUtils.ssoAuthClient.get("authserver/login?service=http%3A%2F%2Fxk.csust.edu.cn%2Fsso.jsp")

        // 3. 处理重定向 (核心修改)
        // 日志显示状态码为 302 (HttpStatusCode.Found)
        if (response.status == HttpStatusCode.Found) {
            val location = response.headers[HttpHeaders.Location]
            if (location != null) {
                // 拿到重定向地址 (http://xk.csust.edu.cn/sso.jsp?ticket=...)
                // 注意：这里应该使用 'educationClientForLogin'，因为跳转的目标是教务系统域名
                // 通过访问这个带 ticket 的地址，教务系统会验证 ticket 并设置 JSESSIONID
                response = KtorUtils.educationClientForLogin.get(location)
            } else {
                emit(Resource.Error("登录异常：未找到重定向地址"))
                return@flow
            }
        }

        // 4. 验证最终页面的 HTML 内容
        val html = response.bodyAsText()

        // 如果重定向后页面仍然包含“请输入账号”，说明 ticket 验证失败或 session 无效
        // 或者如果没发生重定向，直接返回了登录页，也包含此文字
        if (html.contains("请输入账号")) {
            emit(Resource.Error("教务登陆失败"))
            return@flow
        }

        emit(Resource.Success(true))
    }.catch { e ->
        e(TAG, "login: 教务登录失败", e)
        emit(Resource.Error("教务登录失败:${e.message}"))
    }
}