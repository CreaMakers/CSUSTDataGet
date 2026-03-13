package com.dcelysia.csust_spider.education.data.remote.api


import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST


interface ExamApi {

    @FormUrlEncoded
    @POST("jsxsd/xsks/xsksap_list")
    suspend fun queryExamList(
        @Field("xqlbmc") semesterTypeName: String, // 期初/期中/期末/空
        @Field("xnxqid") semester: String,         // 学期，如 2025-2026-1
        @Field("xqlb") semesterTypeId: String      // 1/2/3/空
    ): Response<String>

    @GET("jsxsd/xsks/xsksap_query")
    suspend fun getExamSemester(): Response<String>
}

