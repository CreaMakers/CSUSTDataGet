package com.dcelysia.csust_spider.education.data.remote

import android.util.Log
import com.dcelysia.csust_spider.core.Resource
import com.dcelysia.csust_spider.education.data.remote.model.Course
import com.dcelysia.csust_spider.education.data.remote.model.CourseGradeResponse
import com.dcelysia.csust_spider.education.data.remote.model.CourseNature
import com.dcelysia.csust_spider.education.data.remote.model.DisplayMode
import com.dcelysia.csust_spider.education.data.remote.model.GradeDetailResponse
import com.dcelysia.csust_spider.education.data.remote.model.StudyMode
import com.dcelysia.csust_spider.education.data.remote.repository.EducationRepository

object EducationHelper {
    private val TAG = "EducationHelper"
    private val repository by lazy { EducationRepository.instance }
    
    /**
     * Gets course schedule by term and returns raw HTML string (backward compatibility)
     * 
     * @param week The week number
     * @param academicSemester The academic semester identifier
     * @return Raw HTML string of the course schedule
     */
    suspend fun getCourseScheduleByTerm(week: String, academicSemester: String): Resource<List<Course>> {
        return try {
            // For backward compatibility, we still call the API but return the raw response
            // In a real implementation, you might want to store the raw response separately
            getParsedCourseScheduleByTerm(week,academicSemester)
        } catch (e: Exception) {
            Log.d(TAG,e.toString())
            return Resource.Error("发生错误")
        }
    }
    
    /**
     * Gets course schedule by term and parses it into a List of Course objects
     * 
     * @param week The week number
     * @param academicSemester The academic semester identifier
     * @return List of Course objects parsed from the course schedule
     */
    suspend fun getParsedCourseScheduleByTerm(week: String, academicSemester: String): Resource<List<Course>> {
        return repository.getCourseScheduleByTerm(week, academicSemester)
    }

    /* 获取课程成绩
    * - Parameters:
    *   - academicYearSemester: 学年学期，格式为 "2023-2024-1"，如果为 `nil` 则为全部学期
    *   - courseNature: 课程性质，如果为 `nil` 则查询所有性质的课程
    *   - courseName: 课程名称，默认为空字符串表示查询所有课程
    *   - displayMode: 显示模式，默认为显示最好成绩
    *   - studyMode: 修读方式，默认为主修
    * Returns: 课程成绩信息数组
    */
    suspend fun getCourseGrades(
        academicYearSemester: String? = null,
        courseNature: CourseNature? = null,
        courseName: String = "",
        displayMode: DisplayMode = DisplayMode.BEST_GRADE,
        studyMode: StudyMode = StudyMode.MAJOR
    ): CourseGradeResponse? {
        return try {
            repository.getCourseGrades(academicYearSemester,courseNature,courseName,displayMode,studyMode)
        } catch (e: Exception) {
            Log.d(TAG,e.toString())
            null
        }
    }

    /* 获取课程成绩的所有可用学期
    * - Returns: 包含所有可用学期的数组
    */
    suspend fun getAvailableSemestersForCourseGrades(): List<String> {
        return try {
            repository.getAvailableSemestersForCourseGrades()
        } catch (e: Exception) {
            Log.d(TAG,e.toString())
            emptyList()
        }
    }

    /* 获取成绩详情
    * - Parameter url: 课程详细URL
    * - Returns: 成绩详情
    */
    suspend fun getGradeDetail(url: String): GradeDetailResponse? {
        return try {
            repository.getGradeDetail(url)
        } catch (e: Exception) {
            Log.e(TAG, e.toString())
            null
        }
    }
}