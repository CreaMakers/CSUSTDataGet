package com.dcelysia.csust_spider.core

import android.util.Log
import com.dcelysia.csust_spider.education.data.remote.EducationData
import com.dcelysia.csust_spider.education.data.remote.services.AuthService
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

//统一认证及教务登录拦截器
//对response进行判断，一旦发现处于cookie过期状态就重新登录刷新cookie
//判断条件：本地存在已绑定账号，但返回的html却是登录页面，就进行一次自动登录并重试
//重登录后仍不是有效教务会话时，保留登录页返回，由上层提示重新登录
class NetworkRetryInterceptor(
    @Suppress("UNUSED_PARAMETER") mmkv: MMKV,
    @Suppress("UNUSED_PARAMETER") key: String
) : Interceptor {

    private val TAG = "NetworkRetryInterceptor"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val originalResponse = chain.proceed(request)
        val responseBodyString = originalResponse.body?.string().orEmpty()
        val contentType = originalResponse.body?.contentType()

        // 是否命中登录页：统一认证页面或教务登录表单（DOM 判定，不依赖具体文案）
        val hitLoginPage = AuthService.isLoginPageHtml(responseBodyString)
        Log.d(TAG, "hitLoginPage=$hitLoginPage")

        // 只有本地已绑定账号时才允许自动重登录，避免未绑定时无谓地打断请求
        val hasBoundAccount =
            EducationData.studentId.isNotBlank() && EducationData.studentPassword.isNotBlank()

        if (hitLoginPage && hasBoundAccount) {
            Log.d(TAG, "检测到登录页面，cookie 可能过期，开始自动登录流程...")

            // 阻塞登录流程（放在IO线程）
            val reloginSuccess = runBlocking(Dispatchers.IO) {
                try {
                    Log.d(TAG, "网络库得到的账号密码：${EducationData.studentId},${EducationData.studentPassword}")
                    // 只清理统一认证与教务的会话 cookie，保留校园卡等其他站点 cookie。
                    // 注意：这里绝不能清空整个 cookie jar，否则会把仍然有效的教务会话一并丢掉。
                    val eduSuccess = AuthService.login(
                        EducationData.studentId,
                        EducationData.studentPassword
                    )
                    Log.d(TAG, "教务登录结果: $eduSuccess")
                    // AuthService.login 内部已校验 xsMain.jsp 会话，这里再确认一次，避免把登录页当成功。
                    eduSuccess && AuthService.CheckLoginStates()
                } catch (e: Exception) {
                    Log.e(TAG, "登录重试异常: ${e.message}")
                    false
                }
            }

            if (reloginSuccess) {
                Log.d(TAG, "重新登录成功，重试原始请求...")
                // 重放失败（如请求体已被消费）时不能让异常冒出去，回退到原始响应
                val retryResponse = runCatching { chain.proceed(request) }.getOrNull()
                if (retryResponse != null) {
                    val retryBodyString = retryResponse.body?.string().orEmpty()
                    if (AuthService.isLoginPageHtml(retryBodyString)) {
                        Log.w(TAG, "重试后仍是登录页，保留原始响应交由上层提示重新登录")
                    } else {
                        // 重建响应体
                        return retryResponse.newBuilder()
                            .body(retryBodyString.toResponseBody(retryResponse.body?.contentType()))
                            .build()
                    }
                } else {
                    Log.w(TAG, "重试原始请求失败，保留原始响应")
                }
            } else {
                Log.w(TAG, "自动重新登录失败，保留原始响应交由上层提示重新登录")
            }
        }

        // 未触发重试或重试失败
        return originalResponse.newBuilder()
            .body(responseBodyString.toResponseBody(contentType))
            .build()
    }
}
