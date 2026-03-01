package com.example.csustdataget.CampusCard.model

import androidx.annotation.Keep

@Keep
data class QueryEleRequest(
    val errmsg: String? = null,
    val aid: String?,
    val account: String = "000001",
    val room: Room,
    val floor: Floor,
    val area: Area,
    val building: Building
) {
    @Keep
    data class Room(
        val roomid: String,
        val room: String
    )

    @Keep
    data class Floor(
        val floorid: String,
        val floor: String
    )

    @Keep
    data class Area(
        val area: String,
        val areaname: String
    )

    @Keep
    data class Building(
        val buildingid: String?,
        val building: String
    )
}
