package com.dcelysia.csust_spider.login.sso

import android.util.Log
import com.dcelysia.csust_spider.core.KtorUtils
import com.dcelysia.csust_spider.core.Resource
import com.dcelysia.csust_spider.mooc.data.remote.dto.MoocCourse
import com.dcelysia.csust_spider.mooc.data.remote.dto.MoocHomework
import com.dcelysia.csust_spider.mooc.data.remote.dto.MoocHomeworkResponse
import com.dcelysia.csust_spider.mooc.data.remote.dto.MoocProfile
import com.dcelysia.csust_spider.mooc.data.remote.dto.MoocTest
import com.dcelysia.csust_spider.mooc.data.remote.dto.PendingAssignmentCourse
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import org.jsoup.Jsoup

class MoocRepository private constructor() {
    companion object {
        private const val TAG = "MoocRepository"
        val instance by lazy { MoocRepository() }

        // 关键：保持与 SSO 登录时完全一致的 UA，防止 Session 被服务器强制失效
    }

    /**
     * 登录到 MOOC 系统
     * 访问 SSO 跳转链接，利用 Cookie 换取 MOOC 的 JSESSIONID
     */
    fun loginToMooc() = flow {
        emit(Resource.Loading())
        Log.d(TAG, "正在尝试跳转 MOOC SSO...")

        // 对应原 @GET("/meol/homepage/common/sso_login.jsp")
        val response = KtorUtils.moocClient.get("meol/homepage/common/sso_login.jsp")
        val finalUrl = response.request.url.toString()
        Log.d(TAG,"url:${finalUrl}")
        val urlObj = response.request.url
// 手动拼接不带掩码的 URL
        val rawUrl = "${urlObj.protocol.name}://${urlObj.host}${urlObj.encodedPathAndQuery}"
// 或者如果确实包含 user/password 信息：
// val rawUrl = "${urlObj.protocol.name}://${urlObj.user}:${urlObj.password}@${urlObj.host}${urlObj.encodedPathAndQuery}"

        Log.d(TAG, "真实URL: $rawUrl")
        val body = response.bodyAsText()

        // 验证登录成功的标志：URL 跳转到了个人中心，或者页面里有“退出”按钮
        if (finalUrl.contains("meol/personal.do") ||
            finalUrl.contains("meol/index.do") ||
            body.contains("退出")) {
            emit(Resource.Success(true))
        } else {
            Log.e(TAG, "MOOC SSO 跳转失败，停留页面: $finalUrl")
            emit(Resource.Error("MOOC 系统同步登录失败"))
        }
    }.catch { e ->
        e.printStackTrace()
        Log.e(TAG, "MOOC 登录异常", e)
        emit(Resource.Error("网络错误: ${e.message}"))
    }

    /**
     * 获取个人资料 (解析 HTML)
     */
    fun getProfile() = flow {
        emit(Resource.Loading())

        val response = KtorUtils.moocClient.get("meol/personal.do")

        if (!response.status.isSuccess()) {
            emit(Resource.Error("HTTP ${response.status.value}"))
            return@flow
        }

        val html = response.bodyAsText()
        if (html.isEmpty()) {
            emit(Resource.Error("响应内容为空"))
            return@flow
        }

        val document = Jsoup.parse(html)
        val elements = document.select(".userinfobody > ul > li")

        if (elements.size < 5) {
            emit(Resource.Error("解析失败：页面结构已变更"))
            return@flow
        }

        try {
            val name = elements[1].text()
            val lastLoginTime = elements[2].text().replace("登录时间：", "")
            val totalOnlineTime = elements[3].text().replace("在线总时长： ", "")
            val loginCountText = elements[4].text().replace("登录次数：", "")
            val loginCount = loginCountText.toIntOrNull() ?: 0

            val profile = MoocProfile(
                name = name,
                lastLoginTime = lastLoginTime,
                totalOnlineTime = totalOnlineTime,
                loginCount = loginCount
            )
            emit(Resource.Success(profile))
        } catch (e: Exception) {
            emit(Resource.Error("数据解析异常"))
        }
    }.catch { e ->
        Log.e(TAG, "获取个人资料失败", e)
        emit(Resource.Error("网络错误"))
    }

    /**
     * 获取课程列表 (解析 HTML)
     */
    fun getCourses() = flow {
        emit(Resource.Loading())

        val response = KtorUtils.moocClient.get("meol/lesson/blen.student.lesson.list.jsp")

        val html = response.bodyAsText()
        val document = Jsoup.parse(html)
        val tableElement = document.getElementById("table2")

        if (tableElement == null) {
            emit(Resource.Error("未找到课程列表，可能未登录"))
            return@flow
        }

        val rows = tableElement.select("tr")
        val courses = mutableListOf<MoocCourse>()

        // 跳过表头，从第1行开始
        for (i in 1 until rows.size) {
            val row = rows[i]
            val cols = row.select("td")

            if (cols.size < 4) continue

            val number = cols[0].text()
            val name = cols[1].text()
            val aLink = cols[1].getElementsByTag("a").firstOrNull() ?: continue

            // 提取 CourseID
            // onclick="window.open('../homepage/course/course_index.jsp?courseId=12345','manage_course')"
            val id = aLink.attr("onclick")
                .replace("window.open('../homepage/course/course_index.jsp?courseId=", "")
                .replace("','manage_course')", "")

            val department = cols[2].text()
            val teacher = cols[3].text()

            courses.add(MoocCourse(id, number, name, department, teacher))
        }

        emit(Resource.Success(courses))
    }.catch { e ->
        Log.e(TAG, "获取课程失败", e)
        emit(Resource.Error("网络错误"))
    }

    /**
     * 获取课程作业 (JSON 接口)
     */
    fun getCourseHomeworks(courseId: String) = flow {
        emit(Resource.Loading())

        val response = KtorUtils.moocClient.get("meol/hw/stu/hwStuHwtList.do") {
            parameter("courseId", courseId)
            parameter("pagingPage", 1)
            parameter("pagingNumberPer", 1000) // 不分页，获取全部
            parameter("sortDirection", -1)
            parameter("sortColumn", "deadline")
        }

        if (response.status.isSuccess()) {
            // 这里利用 Ktor 的 ContentNegotiation 自动将 JSON 转为对象
            val result = response.body<MoocHomeworkResponse>()

            val homeworks = result.datas?.hwtList?.map { item ->
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
        } else {
            emit(Resource.Error("获取作业失败: ${response.status}"))
        }
    }.catch { e ->
        Log.e(TAG, "获取作业异常", e)
        emit(Resource.Error("网络错误"))
    }

    /**
     * 获取课程测试/考试 (解析 HTML)
     */
    fun getCourseTests(courseId: String) = flow {
        emit(Resource.Loading())

        val response = KtorUtils.moocClient.get("meol/common/question/test/student/list.jsp") {
            parameter("cateId", courseId)
            parameter("pagingPage", 1)
            parameter("pagingNumberPer", 1000)
            parameter("status", 1)
            parameter("sortColumn", "createTime")
            parameter("sortDirection", -1)
        }

        val html = response.bodyAsText()
        val document = Jsoup.parse(html)
        val tableElement = document.getElementsByClass("valuelist").firstOrNull()

        if (tableElement == null) {
            // 没有找到表格，视为没有测试，返回空列表而不是报错
            emit(Resource.Success(emptyList<MoocTest>()))
            return@flow
        }

        val rows = tableElement.getElementsByTag("tr")
        val tests = mutableListOf<MoocTest>()

        for (i in 1 until rows.size) {
            val row = rows[i]
            val cols = row.getElementsByTag("td")

            if (cols.size < 8) continue

            val title = cols[0].text()
            val startTime = cols[1].text()
            val endTime = cols[2].text()
            val rawAllowRetake = cols[3].text()
            val allowRetake = if (rawAllowRetake == "不限制") null else rawAllowRetake.toIntOrNull()
            val timeLimit = cols[4].text().toIntOrNull() ?: 0
            val isSubmitted = cols[7].html().contains("查看结果")

            tests.add(MoocTest(title, startTime, endTime, allowRetake, timeLimit, isSubmitted))
        }

        emit(Resource.Success(tests))
    }.catch { e ->
        Log.e(TAG, "获取测试异常", e)
        emit(Resource.Error("网络错误"))
    }

    /**
     * 获取有待办作业的课程名称列表 (解析 HTML)
     */
    fun getCourseNamesWithPendingHomeworks(): Flow<Resource<List<PendingAssignmentCourse>>> = flow {
        emit(Resource.Loading())

        val response = KtorUtils.moocClient.get("meol/welcomepage/student/interaction_reminder_v8.jsp")

        val html = response.bodyAsText()
        val document = Jsoup.parse(html)

        val reminderElement = document.getElementById("reminder")

        // 健壮性检查：如果没有 reminder 元素，可能只是没有待办，不一定是错误
        if (reminderElement == null) {
            emit(Resource.Success(emptyList()))
            return@flow
        }

        val courseNamesContainer = reminderElement.getElementsByTag("li").firstOrNull()
        if (courseNamesContainer == null) {
            emit(Resource.Success(emptyList()))
            return@flow
        }

        val courseNameElements = courseNamesContainer.select("li > ul > li > a")
        val courseNames = mutableListOf<PendingAssignmentCourse>()

        for (el in courseNameElements) {
            // 解析 onClick 里的 lid (Lesson ID)
            val id = el.attr("onclick")
                .replace("window.open('./lesson/enter_course.jsp?lid=", "")
                .replace("&t=hw','manage_course')", "")
            val name = el.text().trim()

            courseNames.add(PendingAssignmentCourse(id, name))
        }

        emit(Resource.Success(courseNames.toList()))
    }.catch { e ->
        e.printStackTrace()
        emit(Resource.Error("网络错误"))
    }


}