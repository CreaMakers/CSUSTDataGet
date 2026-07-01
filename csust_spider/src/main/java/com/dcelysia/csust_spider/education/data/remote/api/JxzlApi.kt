package com.dcelysia.csust_spider.education.data.remote.api

import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

/**
 * 教学历（jxzl）接口：POST `/jsxsd/jxzl/jxzl_query` 拉取"学期首日"等教学历信息。
 *
 * 返回教务系统原始 HTML（由 [com.dcelysia.csust_spider.education.data.remote.repository.EducationRepository]
 * 解析）。挂在 [com.dcelysia.csust_spider.core.RetrofitUtils.instanceEduCourse]（EduCourse Retrofit）上，
 * 复用登录 Cookie 与自动重登录拦截器。
 *
 * 对齐 iOS `SemesterService.getSemesterStartDate(academicYearSemester:)`。
 */
interface JxzlApi {

    @FormUrlEncoded
    @POST("/jsxsd/jxzl/jxzl_query")
    suspend fun queryJxzl(
        @Field("xnxq01id") academicYearSemester: String = ""
    ): Response<String>
}