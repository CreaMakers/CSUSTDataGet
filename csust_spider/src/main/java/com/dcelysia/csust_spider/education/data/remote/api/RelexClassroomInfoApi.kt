package com.dcelysia.csust_spider.education.data.remote.api

import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

interface RelexClassroomInfoApi {
    /**
     * 提交空教室查询表单并返回原始 HTML。
     *
     * 该接口对应教务系统的空教室 iframe 查询页面，
     * 调用方需要自行解析返回的课表 HTML。
     *
     * @param skyx 上课院系，默认留空表示全部
     * @param campusId 校区 ID
     * @param buildingId 教学楼 ID，默认留空表示全部教学楼
     * @param functionArea 功能区，默认留空表示全部
     * @param classroomId 教室 ID，默认留空表示全部
     * @param classroomName 教室名称，默认留空表示全部
     * @param weekStart 起始周次
     * @param weekEnd 结束周次
     * @param dayOfWeekStart 起始星期
     * @param dayOfWeekEnd 结束星期
     * @param sectionStart 起始节次
     * @param sectionEnd 结束节次
     * @return 教务系统返回的原始 HTML
     */
    @FormUrlEncoded
    @POST("/jsxsd/kbcx/kbxx_classroom_ifr")
    suspend fun getAvailableClassrooms(
        @Field("skyx") skyx: String = "",
        @Field("xqid") campusId: String,
        @Field("jzwid") buildingId: String = "",
        @Field("gnq") functionArea: String = "",
        @Field("skjsid") classroomId: String = "",
        @Field("skjs") classroomName: String = "",
        @Field("zc1") weekStart: String,
        @Field("zc2") weekEnd: String,
        @Field("skxq1") dayOfWeekStart: String,
        @Field("skxq2") dayOfWeekEnd: String,
        @Field("jc1") sectionStart: String,
        @Field("jc2") sectionEnd: String
    ): Response<String>
}
