package com.example.csustdataget.CampusCard.api

import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

interface ElectronicApi {
    @FormUrlEncoded
    @POST("web/Common/Tsm.html")
    suspend fun queryCampusCard(
        @Field("jsondata") jsonData: String,
        @Field("funname") funName: String,
        @Field("json") json: String = "true"
    ): String
}
