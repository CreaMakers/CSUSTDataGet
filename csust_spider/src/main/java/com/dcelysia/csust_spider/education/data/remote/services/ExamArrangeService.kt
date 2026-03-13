package com.dcelysia.csust_spider.education.data.remote.services

import android.os.Build
import android.util.Log
import com.dcelysia.csust_spider.core.Resource
import com.dcelysia.csust_spider.core.RetrofitUtils
import com.dcelysia.csust_spider.education.data.remote.api.ExamApi
import com.dcelysia.csust_spider.education.data.remote.error.EduHelperError
import com.dcelysia.csust_spider.education.data.remote.model.ExamArrange
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import org.jsoup.Jsoup

object ExamArrangeService {

    private val api by lazy { RetrofitUtils.instanceExam.create(ExamApi::class.java) }
    private const val TAG = "ExamArrangeService"

    enum class ExamTermType(val xqlbmc: String, val xqlb: String) {
        ALL("", ""),
        BEGINNING("期初", "1"),
        MIDDLE("期中", "2"),
        END("期末", "3");

        companion object {
            fun from(raw: String?): ExamTermType {
                return when (raw?.trim()?.lowercase()) {
                    null, "", "all", "全部" -> ALL
                    "beginning", "期初", "开学" -> BEGINNING
                    "middle", "期中" -> MIDDLE
                    "end", "期末" -> END
                    else -> ALL
                }
            }
        }
    }

    // 新 API：强类型，推荐用这个
    suspend fun getExamArrange(
        semester: String = "",
        semesterType: ExamTermType = ExamTermType.ALL
    ): Resource<List<ExamArrange>> {
        return fetchExamArrange(semester, semesterType)
    }

    // 兼容旧调用：UI 传字符串时可用
    suspend fun getExamArrangeRaw(
        semester: String,
        semesterTypeRaw: String
    ): Resource<List<ExamArrange>> {
        return fetchExamArrange(semester, ExamTermType.from(semesterTypeRaw))
    }

    private suspend fun fetchExamArrange(
        semester: String,
        semesterType: ExamTermType
    ): Resource<List<ExamArrange>> {
        return try {
            val (_, defaultSemester) = getSemesterMessage()
            val querySemester = semester.takeIf { it.isNotBlank() } ?: defaultSemester

            val response = api.queryExamList(
                semesterTypeName = semesterType.xqlbmc,
                semester = querySemester,
                semesterTypeId = semesterType.xqlb
            )

            if (!response.isSuccessful) {
                return Resource.Error("网络请求失败: code=${response.code()}")
            }

            val body = response.body()?.trim()
            if (body.isNullOrEmpty()) {
                return Resource.Error("服务器返回数据为空")
            }

            val html = Jsoup.parse(body)
            val dataTable = html.selectFirst("#dataList")
            if (dataTable == null) {
                val errorMsg = html.select("font[color=red]").text().trim()
                if (errorMsg.isNotEmpty()) return Resource.Error("教务系统提示: $errorMsg")
                return Resource.Error("解析失败：未找到数据表格")
            }

            if (dataTable.html().contains("未查询到数据")) {
                return Resource.Success(emptyList())
            }

            val rows = dataTable.select("tr")
            val examList = mutableListOf<ExamArrange>()

            rows.forEachIndexed { index, row ->
                if (index == 0) return@forEachIndexed

                val cols = row.select("td")
                if (cols.size < 11) {
                    Log.w(TAG, "跳过异常行：列数不足(${cols.size})")
                    return@forEachIndexed
                }

                val timeString = cols[6].text().trim()
                val examTimeRange = runCatching { parseDate(timeString) }.getOrElse {
                    Log.w(TAG, "跳过时间解析失败行: $timeString", it)
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

            Resource.Success(examList)
        } catch (e: EduHelperError) {
            Resource.Error(e.message ?: "发生未知错误")
        } catch (e: Exception) {
            Log.e(TAG, "getExamArrange error", e)
            Resource.Error("未知错误: ${e.message}")
        }
    }

    private fun parseDate(timeString: String): Pair<LocalDateTime, LocalDateTime> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            throw EduHelperError.TimeParseFailed("系统版本过低，不支持此功能 (需 Android 8.0+)")
        }

        val normalized = timeString
            .trim()
            .replace("～", "~")
            .replace("—", "~")
            .replace("–", "~")

        val parts = normalized.split(Regex("\\s+"), limit = 2)
        if (parts.size != 2) throw EduHelperError.TimeParseFailed("日期格式错误: $timeString")

        val datePart = parts[0]
        val timeRangePart = parts[1].substringBefore("(").trim()
        val timeList = timeRangePart.split(Regex("\\s*[~-]\\s*"))
        if (timeList.size != 2) throw EduHelperError.TimeParseFailed("时间段格式错误: $timeString")

        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        try {
            val startDate = LocalDateTime.parse("$datePart ${timeList[0]}", dateFormatter)
            val endDate = LocalDateTime.parse("$datePart ${timeList[1]}", dateFormatter)
            return startDate to endDate
        } catch (e: DateTimeParseException) {
            throw EduHelperError.TimeParseFailed("时间解析异常: $timeString")
        }
    }

    // 返回：所有学期 + 默认学期
    private suspend fun getSemesterMessage(): Pair<List<String>, String> {
        val body = api.getExamSemester().body()?.trim()
            ?: throw EduHelperError.examScheduleRetrievalFailed("获取学期列表失败：响应为空")

        val document = Jsoup.parse(body)
        val semesterSelect = document.selectFirst("#xnxqid")
            ?: throw EduHelperError.examScheduleRetrievalFailed("未找到学期下拉框")

        val options = semesterSelect.select("option")
        if (options.isEmpty()) {
            throw EduHelperError.availableSemestersForExamScheduleRetrievalFailed("学期列表为空")
        }

        val semesters = mutableListOf<String>()
        var defaultSemester: String? = null

        options.forEach { option ->
            val name = option.text().trim()
            if (name.isNotEmpty()) {
                semesters.add(name)
                if (option.hasAttr("selected")) defaultSemester = name
            }
        }

        if (semesters.isEmpty()) {
            throw EduHelperError.availableSemestersForExamScheduleRetrievalFailed("学期列表为空")
        }

        return semesters to (defaultSemester ?: semesters.first())
    }
}