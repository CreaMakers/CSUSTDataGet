package com.example.csustdataget.CampusCard.repository

import com.dcelysia.csust_spider.core.RetrofitUtils
import com.example.csustdataget.CampusCard.api.ElectronicApi


class CampusCardRepository private constructor() {
    companion object {
        val instance by lazy { CampusCardRepository() }
        private const val GET_BUILDING = "synjones.onecard.query.elec.building"
        private const val GET_ROOM_INFO = "synjones.onecard.query.elec.roominfo"
    }

    private val elecApi by lazy { RetrofitUtils.instanceCampus.create(ElectronicApi::class.java) }

    suspend fun getBuildings(json: String): String {
        return elecApi.queryCampusCard(
            jsonData = json,
            funName = GET_BUILDING
        )
    }

    suspend fun getElectricity(json: String): String {
        return elecApi.queryCampusCard(
            jsonData = json,
            funName = GET_ROOM_INFO
        )
    }
}
