package com.dcelysia.csust_spider.mooc.data.remote.repository
import android.util.Log
import com.dcelysia.csust_spider.core.AESUtils
import com.dcelysia.csust_spider.core.Resource
import com.dcelysia.csust_spider.core.RetrofitUtils
import com.dcelysia.csust_spider.mooc.data.remote.api.MoocApi
import com.dcelysia.csust_spider.mooc.data.remote.api.SSOAuthApi
import com.dcelysia.csust_spider.mooc.data.remote.api.SSOEhallApi
import com.dcelysia.csust_spider.mooc.data.remote.dto.LoginForm
import com.dcelysia.csust_spider.mooc.data.remote.dto.MoocCourse
import com.dcelysia.csust_spider.mooc.data.remote.dto.MoocHomework
import com.dcelysia.csust_spider.mooc.data.remote.dto.MoocProfile
import com.dcelysia.csust_spider.mooc.data.remote.dto.MoocTest
import com.dcelysia.csust_spider.mooc.data.remote.dto.PendingAssignmentCourse
import com.dcelysia.csust_spider.mooc.data.remote.error.MoocHelperError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import org.jsoup.Jsoup
import java.util.regex.Pattern

class MoocRepository private constructor() {
    companion object {
        val instance by lazy { MoocRepository() }
        private const val TAG = "MoocRepository"
    }

    private val api by lazy { RetrofitUtils.instanceMooc.create(MoocApi::class.java) }
    private val ssoAuthApi by lazy { RetrofitUtils.instanceSSOAuth.create(SSOAuthApi::class.java) }
    private val ssoEhallApi by lazy { RetrofitUtils.instanceSSOEhall.create(SSOEhallApi::class.java) }

    private fun isLoginRequired(response: String): Boolean {
        return response.contains("<TITLE>错误！</TITLE>") || response.contains("请登录！")
    }

    // 检查是否需要验证码
    private suspend fun checkNeedCaptcha(username: String): Boolean {
        return try {
            val timestamp = System.currentTimeMillis()
            val response = ssoAuthApi.checkNeedCaptcha(username, timestamp)
            response.body()?.isNeed ?: false
        } catch (e: Exception) {
            false
        }
    }

    // 获取登录表单
    private suspend fun getLoginForm(): Pair<LoginForm?, Boolean> {
        return try {
            Log.d(TAG, "进入Mooc getLoginForm")
            val response = ssoAuthApi.getLoginForm()

            val finalUrl = response.raw().request.url.toString()
            if (finalUrl.contains("https://ehall.csust.edu.cn")) {
                return Pair(null, true)
            }

            val html = response.body()
            if (html.isNullOrEmpty()) {
                return Pair(null, false)
            }

            val document = Jsoup.parse(html)
            val pwdEncryptSaltInput = document.select("input#pwdEncryptSalt").firstOrNull()
            val executionInput = document.select("input#execution").firstOrNull()

            if (pwdEncryptSaltInput == null || executionInput == null) {
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

    // SSO 登录
    fun login(username: String, password: String) = flow {
        emit(Resource.Loading())
        Log.d(TAG, "进入Mooc登陆")
        // 1. 获取登录表单
        val (loginForm, isAlreadyLoggedIn) = getLoginForm()
        if (isAlreadyLoggedIn) {
            emit(Resource.Success(true))
            return@flow
        }

        if (loginForm == null) {
            emit(Resource.Error("网络错误"))
            return@flow
        }

        // 2. 检查是否需要验证码
        val needCaptcha = checkNeedCaptcha(username)
        if (needCaptcha) {
            emit(Resource.Error("账号状态异常，请在手机网页登录一次\n网址:" +
                    "https://authserver.csust.edu.cn/authserver/login"))
            return@flow
        }
        // 3. 加密密码
        val encryptedPassword = AESUtils.encryptPassword(password, loginForm.pwdEncryptSalt)
        // 4. 执行登录
        val loginResponse = ssoAuthApi.login(
            username = username,
            password = encryptedPassword,
            execution = loginForm.execution
        )
        // 5. 检查登录结果
        var finalUrl = loginResponse.raw().request.url.toString()
        if (finalUrl.contains("ehall.csust.edu.cn/index.html") ||
            finalUrl.contains("ehall.csust.edu.cn/default/index.html")
        ) {
//            emit(Resource.Success(true))
        } else {
            emit(Resource.Error("登录失败，请检查用户名和密码"))
            return@flow
        }

        val response = api.loginToMooc()
        finalUrl = response.raw().request.url.toString()
        if (finalUrl.contains("pt.csust.edu.cn/meol/personal.do")) {
            emit(Resource.Success(true))
        } else {
            emit(Resource.Error("MOOC 登录失败"))
        }
    }.catch { e ->
        e.printStackTrace()
        Log.e(TAG, "获取待完成作业课程失败: ${e.message}")
        emit(Resource.Error("网络错误"))
    }

    // 获取登录用户信息
    fun getLoginUser() = flow {
        emit(Resource.Loading())

        val response = ssoEhallApi.getLoginUser()
        if (!response.isSuccessful) {
            emit(Resource.Error("HTTP ${response.code()}"))
            return@flow
        }

        val user = response.body()?.data
        if (user == null) {
            emit(Resource.Error("用户信息不存在"))
            return@flow
        }

        emit(Resource.Success(user))


    }.catch  {e->
        e.printStackTrace()
        Log.e("MoocRepository", "获取用户信息失败: ${e.message}")
        emit(Resource.Error("网络错误"))
    }

    fun getProfile() = flow {
        emit(Resource.Loading())

        val response = api.getProfile()
        if (!response.isSuccessful) {
            emit(Resource.Error("HTTP ${response.code()}"))
            return@flow
        }

        val html = response.body()
        if (html.isNullOrEmpty()) {
            emit(Resource.Error("Empty response"))
            return@flow
        }

        val document = Jsoup.parse(html)
        val elements = document.select(".userinfobody > ul > li")

        if (elements.size < 5) {
            emit(Resource.Error("Unexpected profile format"))
            return@flow
        }

        val name = elements[1].text()
        val lastLoginTime = elements[2].text().replace("登录时间：", "")
        val totalOnlineTime = elements[3].text().replace("在线总时长： ", "")
        val loginCountText = elements[4].text().replace("登录次数：", "")

        val loginCount = loginCountText.toIntOrNull()
        if (loginCount == null) {
            emit(Resource.Error("Invalid login count format"))
            return@flow
        }

        val profile = MoocProfile(
            name = name,
            lastLoginTime = lastLoginTime,
            totalOnlineTime = totalOnlineTime,
            loginCount = loginCount
        )

        emit(Resource.Success(profile))

    }.catch { e ->
        e.printStackTrace()
        Log.e("MoocRepository", "获取个人资料失败: ${e.message}")
        emit(Resource.Error("网络错误"))
    }

    fun getCourses() = flow {
        emit(Resource.Loading())

        val response = api.getCourses()
        if (!response.isSuccessful) {
            emit(Resource.Error("HTTP ${response.code()}"))
            return@flow
        }

        val html = response.body()
        if (html.isNullOrEmpty()) {
            emit(Resource.Error("Empty response"))
            return@flow
        }

        val document = Jsoup.parse(html)
        val tableElement = document.getElementById("table2")
        if (tableElement == null) {
            emit(Resource.Error("Course table not found"))
            return@flow
        }

        val rows = tableElement.select("tr")
        if (rows.isEmpty()) {
            emit(Resource.Error("Invalid course table format"))
            return@flow
        }

        val courses = mutableListOf<MoocCourse>()

        for (i in 1 until rows.size) {
            val row = rows[i]
            val cols = row.select("td")

            if (cols.size < 4) {
                continue // 跳过格式不正确的行
            }

            val number = cols[0].text()
            val name = cols[1].text()
            val a = cols[1].getElementsByTag("a").firstOrNull()
            if (a == null) {
                continue // 跳过没有链接的行
            }

            val id = a.attr("onclick")
                .replace("window.open('../homepage/course/course_index.jsp?courseId=", "")
                .replace("','manage_course')", "")
            val department = cols[2].text()
            val teacher = cols[3].text()

            courses.add(
                MoocCourse(
                    id = id,
                    number = number,
                    name = name,
                    department = department,
                    teacher = teacher
                )
            )
        }

        emit(Resource.Success(courses))

    }.catch { e ->
        e.printStackTrace()
        Log.e("MoocRepository", "获取课程列表失败: ${e.message}")
        emit(Resource.Error("网络错误"))
    }

    fun getCourseHomeworks(courseId: String) = flow {
        emit(Resource.Loading())

        val response = api.getCourseHomeworks(courseId = courseId)
        when {
            response.code() == 200 -> {
                val homeworks = response.body()?.datas?.hwtList?.map { item ->
                    MoocHomework(
                        id = item.id,
                        title = item.title,
                        publisher = item.realName,
                        canSubmit = item.submitStruts,
                        submitStatus = item.answerStatus != null,
                        deadline = item.deadLine,
                        startTime = item.startDateTime
                    )
                } ?: emptyList()
                emit(Resource.Success(homeworks))
            }

            else -> {
                emit(Resource.Error(response.message() ?: "获取作业失败"))
            }
        }

    }.catch { e ->
        e.printStackTrace()
        Log.e("MoocRepository", "获取课程作业失败: ${e.message}")
        emit(Resource.Error("网络错误"))
    }

    fun getCourseTests(courseId: String) = flow {
        emit(Resource.Loading())
        emit(Resource.Success(getCourseTestsDirect(courseId)))
    }.catch { e ->
        if (e is CancellationException) throw e
        e.printStackTrace()
        Log.e("MoocRepository", "获取课程测试失败: ${e.message}")
        emit(Resource.Error(e.message ?: "网络错误"))
    }

    /**
     * 获取指定课程的测验列表。
     *
     * 逻辑参考 iOS `MoocHelper.getCourseTests`：
     * 请求课程测验页面后解析 `.valuelist` 表格，并提取标题、开始时间、
     * 截止时间、允许重考次数、限时和提交状态。
     *
     * @param courseId 课程 ID
     * @throws MoocHelperError.TestRetrievalFailed 当请求失败、返回为空或页面结构异常时抛出
     * @throws MoocHelperError.CookieExpiredException 当 MOOC 登录状态失效时抛出
     * @return 课程测验列表
     */
    suspend fun getCourseTestsDirect(courseId: String): List<MoocTest> {
        val response = api.getCourseTests(cateId = courseId)
        if (!response.isSuccessful) {
            throw MoocHelperError.TestRetrievalFailed("HTTP ${response.code()}")
        }

        val html = response.body().orEmpty()
        if (html.isBlank()) {
            throw MoocHelperError.TestRetrievalFailed("Empty response")
        }
        if (isLoginRequired(html)) {
            throw MoocHelperError.CookieExpiredException("请先重新登录 MOOC")
        }

        val document = Jsoup.parse(html)
        val tableElement = document.getElementsByClass("valuelist").firstOrNull()
            ?: throw MoocHelperError.TestRetrievalFailed("Test table not found")
        val rows = tableElement.getElementsByTag("tr")
        if (rows.size <= 1) {
            return emptyList()
        }

        val tests = mutableListOf<MoocTest>()
        for (i in 1 until rows.size) {
            val cols = rows[i].getElementsByTag("td")
            if (cols.size < 8) {
                throw MoocHelperError.TestRetrievalFailed("测试表格行格式异常")
            }

            val timeLimit = cols[4].text().toIntOrNull()
                ?: throw MoocHelperError.TestRetrievalFailed("时间限制格式无效")

            tests += MoocTest(
                title = cols[0].text(),
                startTime = cols[1].text(),
                endTime = cols[2].text(),
                allowRetake = cols[3].text().takeUnless { it == "不限制" }?.toIntOrNull(),
                timeLimit = timeLimit,
                isSubmitted = cols[7].html().contains("查看结果")
            )
        }

        return tests
    }

    fun getCourseNamesWithPendingHomeworks(): Flow<Resource<List<PendingAssignmentCourse>>> = flow {
        emit(Resource.Loading())
        val response = api.getCourseNamesWithPendingHomeworks()
        if (!response.isSuccessful) {
            emit(Resource.Error("HTTP ${response.code()}"))
            return@flow
        }

        val html = response.body()
        if (html.isNullOrEmpty()) {
            emit(Resource.Error("网络错误"))
            return@flow
        }

        val document = Jsoup.parse(html)
        val reminderElement = document.getElementById("reminder")
        if (reminderElement == null) {
            emit(Resource.Error("网络错误"))
            return@flow
        }

        val courseNamesContainer = reminderElement.getElementsByTag("li").firstOrNull()
        if (courseNamesContainer == null) {
            emit(Resource.Success(emptyList()))
            return@flow
        }

        val courseNameElements = courseNamesContainer.select("li > ul > li > a")
        val courseNames = mutableListOf<PendingAssignmentCourse>()

        for (courseNameElement in courseNameElements) {
            val id = courseNameElement.attr("onclick")
                .replace("window.open('./lesson/enter_course.jsp?lid=", "")
                .replace("&t=hw','manage_course')", "")
            val courseName = courseNameElement.text().trim()
            courseNames.add(PendingAssignmentCourse(id, courseName))
        }
        emit(Resource.Success(courseNames.toList()))
    }.catch { e ->
        e.printStackTrace()
        Log.e("MoocRepository", "获取待完成作业课程失败: ${e.message}")
        emit(Resource.Error("网络错误"))
    }

    fun logout() = flow {
        emit(Resource.Loading())

        // 1. 先登出 MOOC
        api.logout()

        // 2. 登出 SSO 门户
        ssoEhallApi.logoutEhall()

        // 3. 登出 SSO 认证服务器
        ssoAuthApi.logoutAuthserver()

        emit(Resource.Success(true))
    }.catch { e ->
        e.printStackTrace()
        Log.e("MoocRepository", "登出失败: ${e.message}")
        emit(Resource.Error("网络错误"))
    }
}
