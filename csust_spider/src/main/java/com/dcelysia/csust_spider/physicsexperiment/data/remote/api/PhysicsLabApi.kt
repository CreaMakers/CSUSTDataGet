package com.dcelysia.csust_spider.physicsexperiment.data.remote.api

import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * 物理实验教学管理系统的接口。
 *
 * 页面类接口全部返回 HTML 字符串，由 Repository 用 Jsoup 解析
 * （这套系统是 ASP.NET WebForms，数据都以表格 / 内嵌 JS 数组的形式出现在页面里）。
 */
interface PhysicsLabApi {

    /**
     * 平台登录。
     *
     * **参数直接拼在 URL 上**，复刻 `login.html` 里 JS 的行为：
     * ```javascript
     * var para = "UserType=" + $("#usertype").val();
     * para += "&txtUserName=" + base64encode(utf16to8($("#txtUserName").val()));
     * para += "&txtPass="     + base64encode(utf16to8($("#txtPass").val()));
     * $.ajax({ url: "login.aspx?" + para, type: "POST" });
     * ```
     * JS 全程 **没有做任何 URL 编码**，所以这里用 [Url] 传完整地址，
     * 避免 Retrofit 对 Base64 里的 `+ / =` 二次编码。
     *
     * 成功时响应体是纯文本 `true`（失败会 302 回登录页）。
     */
    @POST
    suspend fun login(@Url url: String): Response<String>

    /**
     * 框架主页。内嵌两棵 JS 数据树：
     * - `tree1Data`：全部实验项目（实验目录）
     * - `tree2Data`：我已选的实验
     */
    @GET("Index.aspx")
    suspend fun index(): Response<String>

    /** 我的课程表（iframe 子页，数据在 `table#gvList` 里）。 */
    @GET("Student/myalltasklist.aspx")
    suspend fun myAllTaskList(
        @Query("generalCourseId") generalCourseId: String,
        @Query("generalCourseName") generalCourseName: String
    ): Response<String>

    /** 我的成绩（iframe 子页，数据在 `table#gvList` 里）。 */
    @GET("Student/GeneralCourseScore.aspx")
    suspend fun myScores(): Response<String>

    /**
     * WebForms 回发翻页：POST 当前页 URL，表单体只带三个字段。
     *
     * 实测课表页（15~20 条/页）与成绩页都没有 `__EVENTVALIDATION`，
     * 也没有 `ctl00$` 命名容器前缀。
     */
    @FormUrlEncoded
    @POST
    suspend fun postback(
        @Url url: String,
        @Header("Referer") referer: String,
        @Field("__EVENTTARGET") eventTarget: String,
        @Field("__EVENTARGUMENT") eventArgument: String,
        @Field("__VIEWSTATE") viewState: String
    ): Response<String>

    /**
     * 网关的用户信息接口（绝对地址，见
     * [com.dcelysia.csust_spider.physicsexperiment.data.remote.PhysicsLabConfig.VPN_USER_INFO_URL]）。
     *
     * 作为"VPN 会话是否有效"的探针：返回 JSON，比解析 HTML 判断可靠。
     */
    @GET
    suspend fun vpnUserInfo(@Url url: String): Response<String>
}
