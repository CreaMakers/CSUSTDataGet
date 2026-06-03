package com.example.csustdataget.CampusCard.model

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class ThirdDataResponse<T>(
    val code: Int,
    val msg: String? = null,
    val message: String? = null,
    val map: ThirdDataMap<T>? = null
)

@Keep
data class ThirdDataMap<T>(
    val data: T
)

@Keep
data class ThirdDataSelectItem(
    val name: String,
    val value: String
)

@Keep
data class ThirdDataRoomPowerInfo(
    @SerializedName("room_id")
    val roomId: String? = null,
    val allAmp: String? = null,
    @SerializedName("xiaoqu_id")
    val campusId: String? = null,
    val usedAmp: String? = null,
    @SerializedName("loudong_id")
    val buildingId: String? = null
)

