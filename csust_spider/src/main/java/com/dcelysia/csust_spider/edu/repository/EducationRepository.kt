package com.dcelysia.csust_spider.edu.repository

import android.os.Build
import android.util.Log
import com.dcelysia.csust_spider.core.KtorUtils
import com.dcelysia.csust_spider.core.Resource
import com.dcelysia.csust_spider.edu.error.EduHelperError
import com.dcelysia.csust_spider.edu.model.Course
import com.dcelysia.csust_spider.edu.model.CourseGrade
import com.dcelysia.csust_spider.edu.model.CourseGradeResponse
import com.dcelysia.csust_spider.edu.model.CourseNature
import com.dcelysia.csust_spider.edu.model.DisplayMode
import com.dcelysia.csust_spider.edu.model.ExamArrange
import com.dcelysia.csust_spider.edu.model.GradeComponent
import com.dcelysia.csust_spider.edu.model.GradeDetail
import com.dcelysia.csust_spider.edu.model.GradeDetailResponse
import com.dcelysia.csust_spider.edu.model.StudyMode
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import org.jsoup.Jsoup
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class EducationRepository private constructor() {
    companion object {
        val instance = EducationRepository()
        const val TAG = "EducationRepository"
    }

    // ───────────────────────────────────────────────
    // 课程成绩 (Course Grades)
    // ───────────────────────────────────────────────

    /* 获取课程成绩
     * - Parameters:
     *   - academicYearSemester: 学年学期，格式为 "2023-2024-1"，如果为 `nil` 则为全部学期
     *   - courseNature: 课程性质，如果为 `nil` 则查询所有性质的课程
     *   - courseName: 课程名称，默认为空字符串表示查询所有课程
     *   - displayMode: 显示模式，默认为显示最好成绩
     *   - studyMode: 修读方式，默认为主修
     * Returns: 课程成绩信息数组（Flow）
     */
    fun getCourseGrades(
        academicYearSemester: String? = null,
        courseNature: CourseNature? = null,
        courseName: String = "",
        displayMode: DisplayMode = DisplayMode.BEST_GRADE,
        studyMode: StudyMode = StudyMode.MAJOR
    ) =
            flow {
                emit(Resource.Loading())
                val response =
                    KtorUtils.educationClientForService.submitForm(
                        url = "/jsxsd/kscj/cjcx_list",
                        formParameters =
                            Parameters.Companion.build {
                                append("kksj", academicYearSemester ?: "")
                                append("kcxz", courseNature?.id ?: "")
                                append("kcmc", courseName)
                                append("xsfs", displayMode.id)
                                append("fxkc", studyMode.id)
                            }
                    )

                val html = response.bodyAsText()
                if (html.isBlank()) {
                    emit(Resource.Error("响应体为空"))
                    return@flow
                }

                if (html.contains("用户登录") || html.contains("统一身份认证")) {
                    emit(Resource.Error("登录状态已失效，请重新登录"))
                    return@flow
                }

                val grades = parseCourseGrades(html)
                emit(Resource.Success(CourseGradeResponse("200", "成功", grades)))
            }
                    .catch { e ->
                        Log.e(TAG, "getCourseGrades: 获取成绩失败", e)
                        emit(Resource.Error("获取成绩失败:${e.message}"))
                    }

    private fun parseCourseGrades(html: String): List<CourseGrade> {
        val document = Jsoup.parse(html)
        val table =
                document.selectFirst("#dataList")
                        ?: throw EduHelperError.CourseGradesRetrievalFailed("未找到课程成绩表格")

        if (table.text().contains("未查询到数据")) return emptyList()

        val rows = table.select("tr")
        val courseGrades = mutableListOf<CourseGrade>()

        for (i in 1 until rows.size) {
            val row = rows[i]
            val cols = row.select("td")

            if (cols.size < 17) {
                throw EduHelperError.CourseGradesRetrievalFailed("行列数不足：${cols.size}")
            }

            try {
                val semester = cols[1].text().trim()
                val courseID = cols[2].text().trim()
                val courseName = cols[3].text().trim()
                val groupName = cols[4].text().trim()
                val gradeString = cols[5].text().trim()
                val grade =
                        gradeString.toIntOrNull()
                                ?: throw EduHelperError.CourseGradesRetrievalFailed(
                                        "成绩格式无效：$gradeString"
                                )

                val gradeDetailUrlElement = cols[5].select("a")
                var gradeDetailUrl = gradeDetailUrlElement.attr("href").trim()
                gradeDetailUrl =
                        gradeDetailUrl
                                .replace("javascript:openWindow('", "http://xk.csust.edu.cn")
                                .replace("',700,500)", "")

                val studyMode = cols[6].text().trim()
                val gradeIdentifier = cols[7].text().trim()
                val credit =
                        cols[8].text().trim().toDoubleOrNull()
                                ?: throw EduHelperError.CourseGradesRetrievalFailed(
                                        "学分格式无效: ${cols[8].text()}"
                                )

                val totalHours =
                        cols[9].text().trim().toIntOrNull()
                                ?: throw EduHelperError.CourseGradesRetrievalFailed(
                                        "总学时格式无效: ${cols[9].text()}"
                                )

                val gradePoint =
                        cols[10].text().trim().toDoubleOrNull()
                                ?: throw EduHelperError.CourseGradesRetrievalFailed(
                                        "绩点格式无效: ${cols[10].text()}"
                                )

                val retakeSemester = cols[11].text().trim()
                val assessmentMethod = cols[12].text().trim()
                val examNature = cols[13].text().trim()
                val courseAttribute = cols[14].text().trim()

                val courseNatureString = cols[15].text().trim()
                val courseNature = CourseNature.Companion.fromChineseName(courseNatureString)

                val courseCategory = cols[16].text().trim()

                val courseGrade =
                    CourseGrade(
                        semester,
                        courseID,
                        courseName,
                        groupName,
                        grade,
                        gradeDetailUrl,
                        studyMode,
                        gradeIdentifier,
                        credit,
                        totalHours,
                        gradePoint,
                        retakeSemester,
                        assessmentMethod,
                        examNature,
                        courseAttribute,
                        courseNature,
                        courseCategory
                    )

                courseGrades.add(courseGrade)
            } catch (e: Exception) {
                if (e is EduHelperError) throw e
                throw EduHelperError.CourseGradesRetrievalFailed("解析成绩时出错：${e.message}")
            }
        }

        return courseGrades
    }

    // ───────────────────────────────────────────────
    // 可用学期 (Available Semesters for Grades)
    // ───────────────────────────────────────────────

    /* 获取课程成绩的所有可用学期
     * - Returns: Flow<Resource<List<String>>>
     */
    fun getAvailableSemestersForCourseGrades() =
            flow {
                emit(Resource.Loading())
                val html =
                    KtorUtils.educationClientForService
                        .get("/jsxsd/kscj/cjcx_query")
                        .bodyAsText()

                if (html.isBlank()) {
                    emit(Resource.Error("响应体为空"))
                    return@flow
                }

                if (html.contains("用户登录") || html.contains("统一身份认证")) {
                    emit(Resource.Error("登录状态已失效，请重新登录"))
                    return@flow
                }

                emit(Resource.Success(parseAvailableSemesters(html)))
            }
                    .catch { e ->
                        Log.e(TAG, "getAvailableSemestersForCourseGrades error", e)
                        emit(Resource.Error("获取学期列表失败: ${e.message}"))
                    }

    private fun parseAvailableSemesters(html: String): List<String> {
        val document = Jsoup.parse(html)
        val semesterSelect =
                document.selectFirst("#kksj")
                        ?: throw EduHelperError.AvailableSemestersForCourseGradesRetrievalFailed(
                                "未找到学期选择元素"
                        )

        val options = semesterSelect.select("option")
        val semesters = mutableListOf<String>()

        for (option in options) {
            val name = option.text().trim()
            if (name.contains("全部学期")) continue
            semesters.add(name)
        }

        return semesters
    }

    // ───────────────────────────────────────────────
    // 成绩详情 (Grade Detail)
    // ───────────────────────────────────────────────

    /* 获取成绩详情
     * - Parameter url: 课程详细URL（完整 URL）
     * - Returns: Flow<Resource<GradeDetailResponse>>
     */
    fun getGradeDetail(url: String) =
            flow {
                emit(Resource.Loading())
                val html = KtorUtils.educationClientForService.get(url).bodyAsText()

                if (html.isBlank()) {
                    emit(Resource.Error("响应体为空"))
                    return@flow
                }

                if (html.contains("用户登录") || html.contains("统一身份认证")) {
                    emit(Resource.Error("登录状态已失效，请重新登录"))
                    return@flow
                }

                val gradeDetail = parseGradeDetail(html)
                emit(Resource.Success(GradeDetailResponse("200", "成功", gradeDetail)))
            }
                    .catch { e ->
                        Log.e(TAG, "getGradeDetail error", e)
                        emit(Resource.Error("获取成绩详情失败: ${e.message}"))
                    }

    private fun parseGradeDetail(html: String): GradeDetail {
        val document = Jsoup.parse(html)

        val table =
                document.selectFirst("#dataList")
                        ?: throw EduHelperError.GradeDetailRetrievalFailed("未找到成绩详情表格")

        val rows = table.select("tr")
        if (rows.size < 2) {
            throw EduHelperError.GradeDetailRetrievalFailed("成绩详情表行数不足")
        }

        val headerRow = rows[0]
        val headerCols = headerRow.select("th")

        val valueRow = rows[1]
        val valueCols = valueRow.select("td")

        if (headerCols.size < 4 || valueCols.size < 4) {
            throw EduHelperError.GradeDetailRetrievalFailed("成绩详情表列数不足")
        }

        val components = mutableListOf<GradeComponent>()

        for (i in 1 until headerCols.size - 1 step 2) {
            val type = headerCols[i].text().trim()
            val gradeString = valueCols[i].text().trim()
            val ratioString = valueCols[i + 1].text().trim().replace("%", "")

            val grade =
                    gradeString.toDoubleOrNull()
                            ?: throw EduHelperError.GradeDetailRetrievalFailed(
                                    "成绩格式无效: $gradeString"
                            )

            val ratio =
                    ratioString.toIntOrNull()
                            ?: throw EduHelperError.GradeDetailRetrievalFailed(
                                    "比例格式无效: $ratioString"
                            )

            components.add(GradeComponent(type, grade, ratio))
        }

        val totalGradeString = valueCols.last()?.text()?.trim()
        val totalGrade =
                totalGradeString?.toIntOrNull()
                        ?: throw EduHelperError.GradeDetailRetrievalFailed(
                                "总成绩格式无效: $totalGradeString"
                        )

        return GradeDetail(components, totalGrade)
    }

    // ───────────────────────────────────────────────
    // 课程表 (Course Schedule)
    // ───────────────────────────────────────────────

    /**
     * 获取课程表（Ktor 实现）
     *
     * @param week 周次
     * @param academicSemester 学年学期
     * @return 课程列表
     */
    fun getCourseScheduleByTerm(week: String, academicSemester: String) =
            flow {
                emit(Resource.Loading())
                val html =
                    KtorUtils.educationClientForService
                        .submitForm(
                            url = "/jsxsd/xskb/xskb_list.do",
                            formParameters =
                                Parameters.Companion.build {
                                    append("zc", week)
                                    append("xnxq01id", academicSemester)
                                }
                        )
                        .bodyAsText()

                Log.d(TAG, "getCourseScheduleByTerm html length: ${html.length}")

                if (html.contains("用户登录") || html.contains("统一身份认证")) {
                    emit(Resource.Error("需要重新登录"))
                    return@flow
                }

                emit(parseCourseSchedule(html))
            }
                    .catch { e ->
                        Log.e(TAG, "getCourseScheduleByTerm error", e)
                        emit(Resource.Error("发生错误: ${e.message}"))
                    }

    private fun parseCourseSchedule(html: String): Resource<List<Course>> {
        val courses = mutableListOf<Course>()

        try {
            val document = Jsoup.parse(html)

            val emptyDataElement = document.select("td[colspan=10]")
            if (emptyDataElement.any { it.text().contains("未查询到数据") }) {
                Log.d(TAG, "未查询到课程数据")
                return Resource.Error("未查询到课程")
            }

            val courseDivs = document.select("div.kbcontent")

            for (div in courseDivs) {
                val courseBlocks = div.html().split("---------------------")

                for (block in courseBlocks) {
                    val courseBlock = Jsoup.parse(block).body()
                    val courseName = courseBlock.ownText().trim()

                    var teacher: String? = null
                    var weeks: String? = null
                    var classroom: String? = null
                    var weekday: String? = null

                    val divId = div.attr("id")
                    if (divId != null && divId.isNotEmpty()) {
                        val idParts = divId.split("-")
                        if (idParts.size >= 2) {
                            weekday = idParts[idParts.size - 2]
                        }
                    }

                    val fonts = courseBlock.select("font")
                    for (font in fonts) {
                        val title = font.attr("title")
                        val text = font.text()
                        when (title) {
                            "老师" -> teacher = text
                            "周次(节次)" -> weeks = text
                            "教室" -> classroom = text
                        }
                    }

                    if (courseName.isNotEmpty()) {
                        courses.add(
                            Course(
                                courseName = courseName,
                                teacher = teacher ?: "",
                                weeks = weeks ?: "",
                                classroom = classroom ?: "",
                                weekday = weekday ?: ""
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseCourseSchedule error", e)
            return Resource.Error("解析课程表失败: ${e.message}")
        }

        return Resource.Success(courses)
    }

    // ───────────────────────────────────────────────
    // 考试安排 (Exam Arrange)
    // ───────────────────────────────────────────────

    /**
     * 获取考试安排
     *
     * @param semester 学年学期，为空时自动从系统获取当前学期
     * @param semesterType 学期类型："beginning" / "middle" / "end"
     */
    fun getExamArrange(semester: String, semesterType: String) =
            flow {
                emit(Resource.Loading())
                val querySemester =
                    semester.ifEmpty {
                        val semesters = getSemesterMessage()
                        if (semesters.isEmpty()) {
                            emit(Resource.Error("获取学期信息失败：列表为空"))
                            return@flow
                        }
                        semesters[0]
                    }

                val semesterId = getSemesterid(semesterType)

                val html =
                    KtorUtils.educationClientForService
                        .submitForm(
                            url = "/jsxsd/xsks/xsksap_list",
                            formParameters =
                                Parameters.Companion.build {
                                    append("xqlbmc", semesterType)
                                    append("xnxqid", querySemester)
                                    append("xqlb", semesterId)
                                }
                        )
                        .bodyAsText()

                if (html.isBlank()) {
                    emit(Resource.Error("服务器返回数据为空"))
                    return@flow
                }

                if (html.contains("用户登录") || html.contains("统一身份认证")) {
                    emit(Resource.Error("登录状态已失效，请重新登录"))
                    return@flow
                }

                val document = Jsoup.parse(html)
                val dataDiv = document.select("#dataList").first()

                if (dataDiv == null) {
                    val errorMsg = document.select("font[color=red]").text()
                    if (errorMsg.isNotEmpty()) {
                        emit(Resource.Error("教务系统提示: $errorMsg"))
                    } else {
                        emit(Resource.Error("解析失败：未找到数据表格"))
                    }
                    return@flow
                }

                if (dataDiv.html().contains("未查询到数据")) {
                    emit(Resource.Success(emptyList<ExamArrange>()))
                    return@flow
                }

                val list = dataDiv.select("tr")
                val examList = mutableListOf<ExamArrange>()
                var parseError: String? = null

                list.forEachIndexed { index, row ->
                    if (parseError != null) return@forEachIndexed
                    if (index == 0) return@forEachIndexed // 跳过表头

                    val cols = row.select("td")
                    if (cols.size < 11) {
                        parseError = "表格格式异常，列数不足: ${cols.size}"
                        return@forEachIndexed
                    }

                    val timeString = cols[6].text().trim()
                    val examTimeRange =
                        try {
                            parseDate(timeString)
                        } catch (e: Exception) {
                            parseError = "时间解析失败 ($timeString): ${e.message}"
                            return@forEachIndexed
                        }

                    examList.add(
                        ExamArrange(
                            cols[1].text().trim(),
                            cols[2].text().trim(),
                            cols[3].text().trim(),
                            cols[4].text().trim(),
                            cols[5].text().trim(),
                            cols[6].text().trim(),
                            examTimeRange.first,
                            examTimeRange.second,
                            cols[7].text().trim(),
                            cols[8].text().trim(),
                            cols[9].text().trim(),
                            cols[10].text().trim()
                        )
                    )
                }

                if (parseError != null) {
                    emit(Resource.Error(parseError!!))
                } else {
                    emit(Resource.Success(examList))
                }
            }
                    .catch { e ->
                        Log.e(TAG, "getExamArrange error", e)
                        val errorMsg = if (e is EduHelperError) e.message else "未知错误: ${e.message}"
                        emit(Resource.Error(errorMsg ?: "发生未知错误"))
                    }

    private fun parseDate(timeString: String): Pair<LocalDateTime, LocalDateTime> {
        val list = timeString.split(" ")
        if (list.size != 2) throw EduHelperError.TimeParseFailed("日期格式错误")

        val timeList = list[1].split("~")
        if (timeList.size != 2) throw EduHelperError.TimeParseFailed("时间段格式错误")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            throw EduHelperError.TimeParseFailed("系统版本过低，不支持此功能 (需 Android 8.0+)")
        }

        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        try {
            val startDate = LocalDateTime.parse("${list[0]} ${timeList[0]}", dateFormatter)
            val endDate = LocalDateTime.parse("${list[0]} ${timeList[1]}", dateFormatter)
            return Pair(startDate, endDate)
        } catch (e: DateTimeParseException) {
            throw EduHelperError.TimeParseFailed("时间解析异常")
        }
    }

    private fun getSemesterid(semesterType: String): String {
        return when (semesterType) {
            "beginning" -> "1"
            "middle" -> "2"
            "end" -> "3"
            else -> "3"
        }
    }

    private suspend fun getSemesterMessage(): ArrayList<String> {
        val html = KtorUtils.educationClientForService.get("/jsxsd/xsks/xsksap_query").bodyAsText()

        if (html.isBlank()) {
            throw EduHelperError.examScheduleRetrievalFailed("获取学期列表失败：响应为空")
        }

        val document = Jsoup.parse(html)
        val semesters =
                document.select("#xnxqid").first()
                        ?: throw EduHelperError.examScheduleRetrievalFailed("未找到学期下拉框")

        val options = semesters.select("option")
        val result = ArrayList<String>()
        var defaultSemester = ""

        for (option in options) {
            val name = option.text().trim()
            if (option.hasAttr("selected")) {
                defaultSemester = name
            }
            result.add(name)
        }

        if (result.isEmpty()) {
            throw EduHelperError.availableSemestersForExamScheduleRetrievalFailed("学期列表为空")
        }

        if (defaultSemester.isNotEmpty()) {
            result.remove(defaultSemester)
            result.add(0, defaultSemester)
        }

        return result
    }
}