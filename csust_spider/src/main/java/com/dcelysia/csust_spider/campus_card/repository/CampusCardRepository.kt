package com.example.csustdataget.CampusCard.repository

import android.net.Uri
import com.dcelysia.csust_spider.core.RetrofitUtils
import com.example.csustdataget.CampusCard.api.ElectronicApi


class CampusCardRepository private constructor() {
    companion object {
        val instance by lazy { CampusCardRepository() }
        private const val CAMPUS_CARD_BASIC_AUTH =
            "Basic bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm06bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm1fc2VjcmV0"
        private const val CHARGE_AUTH = "Y2hhcmdlOmNoYXJnZV9zZWNyZXQ="
        private const val GET_BUILDING = "synjones.onecard.query.elec.building"
        private const val GET_ROOM_INFO = "synjones.onecard.query.elec.roominfo"
    }

    private val elecApi by lazy { RetrofitUtils.instanceCampus.create(ElectronicApi::class.java) }

    suspend fun loginToCampusCardTicket(): String? {
        val response = elecApi.loginToCampusCard()
        val finalUrl = response.raw().request.url.toString()
        val ticket = finalUrl.substringAfter("ticket=", missingDelimiterValue = "")
        return ticket.substringBefore("&").takeIf { it.isNotBlank() }?.let {
            Uri.decode(it)
        }
    }

    suspend fun exchangeTicketForToken(ticket: String): String {
        return elecApi.exchangeTicketForToken(
            authorization = CAMPUS_CARD_BASIC_AUTH,
            username = ticket,
            password = ticket
        ).accessToken
    }

    suspend fun getBuildingsV2(token: String, parameters: Map<String, String>): String {
        return elecApi.getThirdData(
            authorization = CHARGE_AUTH,
            authToken = "bearer $token",
            parameters = parameters
        )
    }

    suspend fun getRooms(token: String, parameters: Map<String, String>): String {
        return elecApi.getThirdData(
            authorization = CHARGE_AUTH,
            authToken = "bearer $token",
            parameters = parameters
        )
    }

    suspend fun getElectricityV2(token: String, parameters: Map<String, String>): String {
        return elecApi.getThirdData(
            authorization = CHARGE_AUTH,
            authToken = "bearer $token",
            parameters = parameters
        )
    }

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
