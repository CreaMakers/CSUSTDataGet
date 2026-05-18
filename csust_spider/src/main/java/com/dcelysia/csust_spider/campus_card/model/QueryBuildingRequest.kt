package com.example.csustdataget.CampusCard.model

import androidx.annotation.Keep

@Keep
data class QueryBuildingRequest(
    val retcode: String? = null,
    val errmsg: String? = null,
    val aid: String,
    val account: String = "000001",
    val area: Area,
    val buildingtab: List<Building>? = null
) {
    @Keep
    data class Area(
        val area: String,
        val areaname: String
    )

    @Keep
    data class Building(
        val buildingid: String,
        val building: String
    )
}
