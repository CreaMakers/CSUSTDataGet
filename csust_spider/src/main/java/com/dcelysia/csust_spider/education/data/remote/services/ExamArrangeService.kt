package com.dcelysia.csust_spider.education.data.remote.services

import android.os.Build
import android.util.Log
import com.dcelysia.csust_spider.core.RetrofitUtils
import com.dcelysia.csust_spider.education.data.remote.api.ExamApi
import com.dcelysia.csust_spider.education.data.remote.error.EduHelperError
import com.dcelysia.csust_spider.education.data.remote.model.ExamArrange
import org.jsoup.Jsoup
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object ExamArrangeService {

    private val api by lazy { RetrofitUtils.instanceExam.create(ExamApi::class.java) }

    private val TAG = "Exam_Arrange"

    private val ExamList= ArrayList<ExamArrange>()


    suspend fun getExamArrange(Semester: String, SemesterType: String): ArrayList<ExamArrange>? {
        if (AuthService.CheckLoginStates()) {
            val querySemester =
                Semester.ifEmpty {
                    getSemesterMessage()[0]
                }

            val body =
                api.queryExamList(SemesterType, querySemester, getSemesterid(SemesterType)).body()

            val html = Jsoup.parse(body.toString())

            val data = (html.select("#dataList").first())
            if (data != null) {
                if (data.html().contains("未查询到数据")) {
                    return null
                }
                val list = data.select("tr")

                list.forEachIndexed { index, row ->
                    if (index == 0) return@forEachIndexed  // 跳过表头

                    val cols = row.select("td")
                    if (cols.size < 11) {
                        throw EduHelperError.examScheduleRetrievalFailed("获取数据行列数异常， 行数为：${cols.size}")
                    }

                    val examTimeRange = parseDate(cols[6].text().trim())
                    val exam = ExamArrange(
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

                    ExamList.add(exam)

                }
                return ExamList
            } else {
                throw EduHelperError.examScheduleRetrievalFailed("未查询到考试安排表")
            }
        } else {
            throw EduHelperError.NotLoggedIn("请重新登录")
        }

    }

    private suspend fun parseDate(Timestring: String): Pair<LocalDateTime, LocalDateTime> {
        val list = Timestring.split(" ")
        if (list.size != 2) throw EduHelperError.TimeParseFailed("时间字符串格式无效")
        val timeList = list[1].split("~")
        if (timeList.size != 2) throw EduHelperError.TimeParseFailed("时间段字符串格式无效")

        val dateFormatter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        } else {
            TODO("VERSION.SDK_INT < O")
        }

        try {
            // 使用空格连接日期与时间（而不是 '+'）
            val startDate = LocalDateTime.parse("${list[0]} ${timeList[0]}", dateFormatter)
            val endDate = LocalDateTime.parse("${list[0]} ${timeList[1]}", dateFormatter)
            Log.d(TAG, "startTime:${startDate},endTime:${endDate}")
            return Pair(startDate, endDate)
        } catch (e: java.time.format.DateTimeParseException) {
            throw EduHelperError.TimeParseFailed("字符串转换时间格式异常: ${e.message}")
        }
    }

    private suspend fun getSemesterid(SemesterType: String): String {
        val id = when (SemesterType) {
            "beginning" -> {
                "1"
            }

            "middle" -> {
                "2"
            }

            "end" -> {
            }

            else -> {
                "3"
            }
        }.toString()
        return id
    }


    private suspend fun getSemesterMessage(): ArrayList<String> {
        val body = api.getExamSemester().body().toString()
        val document = Jsoup.parse(body)

        val semesters = document.select("#xnxqid").first()
        if (semesters == null) throw EduHelperError.examScheduleRetrievalFailed("未找到学期选择元素")

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
            throw EduHelperError.availableSemestersForExamScheduleRetrievalFailed("学期选择中没找到学期")
        }
        if (defaultSemester.isEmpty()) {
            throw EduHelperError.availableSemestersForExamScheduleRetrievalFailed("未找到默认学期")
        }

        result.add(defaultSemester)

        return result
    }

}