package com.example.csustdataget.CampusCard.api

import com.example.csustdataget.CampusCard.model.CampusCardTokenResponse
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface ElectronicApi {
    @GET("berserker-auth/cas/login/wisedu?targetUrl=https%3A%2F%2Fhxyxh5.csust.edu.cn%2Fplat%2F%3Fname%3DloginTransit")
    suspend fun loginToCampusCard(): Response<String>

    @FormUrlEncoded
    @POST("berserker-auth/oauth/token")
    suspend fun exchangeTicketForToken(
        @Header("Authorization") authorization: String,
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("grant_type") grantType: String = "password",
        @Field("scope") scope: String = "all",
        @Field("loginFrom") loginFrom: String = "h5",
        @Field("logintype") loginType: String = "sso",
        @Field("device_token") deviceToken: String = "h5",
        @Field("synAccessSource") synAccessSource: String = "h5"
    ): CampusCardTokenResponse

    @FormUrlEncoded
    @POST("charge/feeitem/getThirdData")
    suspend fun getThirdData(
        @Header("Authorization") authorization: String,
        @Header("synjones-auth") authToken: String,
        @FieldMap parameters: Map<String, String>
    ): String

    @FormUrlEncoded
    @POST("web/Common/Tsm.html")
    suspend fun queryCampusCard(
        @Field("jsondata") jsonData: String,
        @Field("funname") funName: String,
        @Field("json") json: String = "true"
    ): String
}
