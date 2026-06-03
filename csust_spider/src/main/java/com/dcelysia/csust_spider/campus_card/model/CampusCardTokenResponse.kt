package com.example.csustdataget.CampusCard.model

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class CampusCardTokenResponse(
    @SerializedName("access_token")
    val accessToken: String
)

