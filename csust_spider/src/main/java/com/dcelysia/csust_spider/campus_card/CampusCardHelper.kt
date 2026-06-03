package com.example.csustdataget.CampusCard

import android.util.Log
import com.example.csustdataget.CampusCard.model.ThirdDataResponse
import com.example.csustdataget.CampusCard.model.ThirdDataRoomPowerInfo
import com.example.csustdataget.CampusCard.model.ThirdDataSelectItem
import com.example.csustdataget.CampusCard.repository.CampusCardRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.round

object CampusCardHelper {
    data class BuildingInfo(
        val name: String,
        val id: String
    )

    data class RoomInfo(
        val name: String,
        val id: String,
        val building: BuildingInfo
    )

    private data class CampusInfo(
        val displayName: String,
        val campusId: String,
        val feeItemId: String
    )

    private val json by lazy { Gson() }
    private const val TAG = "CampusCardHelper"
    private val repository by lazy { CampusCardRepository.instance }
    private var token: String? = null
    private val campusInfoMap = mapOf(
        "金盆岭校区" to CampusInfo(displayName = "金盆岭校区", campusId = "22", feeItemId = "468"),
        "金盆岭" to CampusInfo(displayName = "金盆岭校区", campusId = "22", feeItemId = "468"),
        "云塘校区" to CampusInfo(displayName = "云塘校区", campusId = "1", feeItemId = "448"),
        "云塘" to CampusInfo(displayName = "云塘校区", campusId = "1", feeItemId = "448")
    )
    val buildingFallbackMap = mutableMapOf(
        "西苑2栋" to "9",
        "东苑11栋" to "178",
        "西苑5栋" to "33",
        "东苑14栋" to "132",
        "东苑6栋" to "131",
        "南苑7栋" to "97",
        "东苑9栋" to "162",
        "西苑11栋" to "75",
        "西苑6栋" to "41",
        "东苑4栋" to "171",
        "西苑8栋" to "57",
        "东苑15栋" to "133",
        "西苑9栋" to "65",
        "南苑5栋" to "96",
        "西苑10栋" to "74",
        "东苑12栋" to "179",
        "南苑4栋" to "95",
        "东苑5栋" to "130",
        "西苑3栋" to "17",
        "西苑4栋" to "25",
        "外教楼" to "180",
        "南苑3栋" to "94",
        "西苑7栋" to "49",
        "西苑1栋" to "1",
        "南苑8栋" to "98",
        "16栋A区" to "471",
        "16栋B区" to "472",
        "17栋" to "451",
        "弘毅轩1栋A区" to "141",
        "弘毅轩1栋B区" to "148",
        "弘毅轩2栋A区1-6楼" to "197",
        "弘毅轩2栋B区" to "201",
        "弘毅轩2栋C区" to "205",
        "弘毅轩2栋D区" to "206",
        "弘毅轩3栋A区" to "155",
        "弘毅轩3栋B区" to "183",
        "弘毅轩4栋A区" to "162",
        "弘毅轩4栋B区" to "169",
        "留学生公寓" to "450",
        "敏行轩1栋A区" to "176",
        "敏行轩1栋B区" to "184",
        "敏行轩2栋A区" to "513",
        "敏行轩2栋B区" to "520",
        "敏行轩3栋A区" to "527",
        "敏行轩3栋B区" to "528",
        "敏行轩4栋A区" to "529",
        "敏行轩4栋B区" to "530",
        "行健轩1栋A区" to "85",
        "行健轩1栋B区" to "92",
        "行健轩2栋A区" to "99",
        "行健轩2栋B区" to "106",
        "行健轩3栋A区" to "113",
        "行健轩3栋B区" to "120",
        "行健轩4栋A区" to "127",
        "行健轩4栋B区" to "134",
        "行健轩5栋A区" to "57",
        "行健轩5栋B区" to "64",
        "行健轩6栋A区" to "71",
        "行健轩6栋B区" to "78",
        "至诚轩1栋A区" to "1",
        "至诚轩1栋B区" to "8",
        "至诚轩2栋A区" to "15",
        "至诚轩2栋B区" to "22",
        "至诚轩3栋A区" to "29",
        "至诚轩3栋B区" to "36",
        "至诚轩4栋B区" to "50",
        "至诚轩4栋A区" to "43"
    )

    suspend fun syncToken(ticket: String) {
        token = repository.exchangeTicketForToken(ticket)
    }

    fun clearCampusCardToken() {
        token = null
    }

    private suspend fun ensureToken(): String? {
        token?.takeIf { it.isNotBlank() }?.let { return it }
        val ticket = repository.loginToCampusCardTicket() ?: return null
        return repository.exchangeTicketForToken(ticket).also { token = it }
    }

    suspend fun getBuildings(campusName: String): List<BuildingInfo> {
        val campus = campusInfoMap[campusName] ?: return emptyList()
        return try {
            withContext(Dispatchers.IO) {
                val campusCardToken = ensureToken() ?: return@withContext emptyList()
                val response = repository.getBuildingsV2(
                    token = campusCardToken,
                    parameters = mapOf(
                        "feeitemid" to campus.feeItemId,
                        "type" to "select",
                        "level" to "1",
                        "xiaoqu_id" to campus.campusId
                    )
                )
                parseSelectItemsResponse(response).map {
                    BuildingInfo(name = it.name, id = it.value)
                }.onEach {
                    syncBuildingId(it.name, it.id)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getBuildings failed for $campusName", e)
            emptyList()
        }
    }

    suspend fun resolveBuildingId(campusName: String, buildingName: String): String? {
        if (shouldUseDynamicBuildingLookupOnly(buildingName)) {
            return resolveDynamicBuildingId(campusName, buildingName)
        }
        return resolveFallbackBuildingId(buildingName) ?: resolveDynamicBuildingId(campusName, buildingName)
    }

    internal fun resolveFallbackBuildingId(buildingName: String): String? {
        if (shouldUseDynamicBuildingLookupOnly(buildingName)) {
            return null
        }
        return getCachedBuildingId(buildingName)
    }

    internal fun shouldUseDynamicBuildingLookupOnly(buildingName: String): Boolean {
        val normalizedName = normalizeBuildingName(buildingName)
        return normalizedName.startsWith("至诚轩5栋") && getCachedBuildingId(normalizedName) == null
    }

    private suspend fun resolveDynamicBuildingId(campusName: String, buildingName: String): String? {
        val normalizedName = normalizeBuildingName(buildingName)
        val dynamicMatch = getBuildings(campusName).firstOrNull {
            normalizeBuildingName(it.name) == normalizedName
        }
        return dynamicMatch?.id
    }

    internal fun getCachedBuildingId(buildingName: String): String? {
        return buildingFallbackMap[normalizeBuildingName(buildingName)]
    }

    internal fun syncBuildingId(buildingName: String, buildingId: String) {
        buildingFallbackMap[normalizeBuildingName(buildingName)] = buildingId
    }

    internal fun normalizeBuildingName(buildingName: String): String {
        val numeralAliases = listOf(
            "一" to "1",
            "二" to "2",
            "三" to "3",
            "四" to "4",
            "五" to "5",
            "六" to "6",
            "七" to "7",
            "八" to "8",
            "九" to "9"
        )
        return numeralAliases.fold(buildingName.trim()) { normalized, (cn, digit) ->
            normalized.replace("${cn}栋", "${digit}栋")
        }
    }

    internal fun parseBuildingResponse(text: String?): List<BuildingInfo> {
        if (text.isNullOrBlank()) return emptyList()
        return parseSelectItemsResponse(text).map { BuildingInfo(name = it.name, id = it.value) }
    }

    suspend fun getRooms(campusName: String, buildingName: String): List<RoomInfo> {
        val campus = campusInfoMap[campusName] ?: return emptyList()
        return try {
            withContext(Dispatchers.IO) {
                val campusCardToken = ensureToken() ?: return@withContext emptyList()
                val building = getBuildings(campusName).firstOrNull {
                    normalizeBuildingName(it.name) == normalizeBuildingName(buildingName)
                } ?: return@withContext emptyList()
                val response = repository.getRooms(
                    token = campusCardToken,
                    parameters = mapOf(
                        "feeitemid" to campus.feeItemId,
                        "type" to "select",
                        "level" to "2",
                        "xiaoqu_id" to campus.campusId,
                        "loudong_id" to building.id
                    )
                )
                parseSelectItemsResponse(response).map {
                    RoomInfo(name = it.name, id = it.value, building = building)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getRooms failed for $campusName / $buildingName", e)
            emptyList()
        }
    }

    /**
     * @param campusName 校区名字
     * @param buildingName 楼栋名字
     * @param roomId 房间号
     */
    suspend fun queryElectricity(
        campusName: String,
        buildingName: String,
        roomId: String
    ): Double? {
        val campus = campusInfoMap[campusName]
        if (campus == null) {
            Log.e(TAG, "queryElectricity: invalid campus or building -> $campusName / $buildingName")
            return null
        }
        return try {
            withContext(Dispatchers.IO) {
                val campusCardToken = ensureToken()
                if (campusCardToken.isNullOrBlank()) {
                    Log.e(TAG, "queryElectricity: campus card login failed")
                    return@withContext null
                }
                val building = getBuildings(campusName).firstOrNull {
                    normalizeBuildingName(it.name) == normalizeBuildingName(buildingName)
                }
                if (building == null) {
                    Log.e(TAG, "queryElectricity: initial building lookup failed -> $campusName / $buildingName")
                    return@withContext null
                }

                val room = getRooms(campusName, building.name).firstOrNull {
                    normalizeRoomName(it.name) == normalizeRoomName(roomId) ||
                            normalizeRoomName(it.id) == normalizeRoomName(roomId)
                }
                if (room == null) {
                    Log.e(TAG, "queryElectricity: room lookup failed -> $campusName / ${building.name} / $roomId")
                    return@withContext null
                }

                attemptQueryElectricity(
                    token = campusCardToken,
                    campus = campus,
                    buildingId = building.id,
                    roomId = room.id
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryElectricity failed", e)
            null
        }
    }

    private suspend fun attemptQueryElectricity(
        token: String,
        campus: CampusInfo,
        buildingId: String,
        roomId: String
    ): Double? {
        val response = repository.getElectricityV2(
            token = token,
            parameters = mapOf(
                "feeitemid" to campus.feeItemId,
                "type" to "IEC",
                "level" to "3",
                "xiaoqu_id" to campus.campusId,
                "loudong_id" to buildingId,
                "room_id" to roomId
            )
        )
        Log.d(TAG, response)
        val powerInfo = parseRoomPowerInfoResponse(response) ?: return extractElectricityFromString(response)
        val allValue = powerInfo.allAmp?.toDoubleOrNull()
        val usedValue = powerInfo.usedAmp?.toDoubleOrNull()
        if (allValue == null || usedValue == null) {
            return extractElectricityFromString(response)
        }
        return roundToTwoDecimals(allValue - usedValue)
    }

    private fun parseSelectItemsResponse(text: String?): List<ThirdDataSelectItem> {
        if (text.isNullOrBlank()) return emptyList()
        val type = object : TypeToken<ThirdDataResponse<List<ThirdDataSelectItem>>>() {}.type
        val response = runCatching {
            json.fromJson<ThirdDataResponse<List<ThirdDataSelectItem>>>(text, type)
        }.getOrNull() ?: return emptyList()
        if (response.code == 401) {
            token = null
            return emptyList()
        }
        return response.map?.data.orEmpty()
    }

    private fun parseRoomPowerInfoResponse(text: String?): ThirdDataRoomPowerInfo? {
        if (text.isNullOrBlank()) return null
        val type = object : TypeToken<ThirdDataResponse<ThirdDataRoomPowerInfo>>() {}.type
        val response = runCatching {
            json.fromJson<ThirdDataResponse<ThirdDataRoomPowerInfo>>(text, type)
        }.getOrNull() ?: return null
        if (response.code == 401) {
            token = null
            return null
        }
        return response.map?.data
    }

    private fun normalizeRoomName(roomName: String): String {
        return roomName.trim().lowercase()
    }

    fun extractElectricityFromString(text: String?): Double? {
        if (text.isNullOrBlank()) return null

        val patterns = listOf(
            "电量[:：\\s]*([0-9]+(?:\\.[0-9]+)?)",
            "剩余[:：\\s]*([0-9]+(?:\\.[0-9]+)?)",
            "\"balance\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)",
            "balance[:：\\s]*([0-9]+(?:\\.[0-9]+)?)",
            "([0-9]+(?:\\.[0-9]+)?)\\s*(?:度|kwh|kWh)"
        )

        for (pattern in patterns) {
            val matched = Regex(pattern, RegexOption.IGNORE_CASE).find(text)
            val captured = matched?.groups?.get(1)?.value
            if (!captured.isNullOrEmpty()) {
                return captured.toDoubleOrNull()?.let(::roundToTwoDecimals)
            }
        }

        return Regex("([0-9]+(?:\\.[0-9]+)?)")
            .find(text)
            ?.groups
            ?.get(1)
            ?.value
            ?.toDoubleOrNull()
            ?.let(::roundToTwoDecimals)
    }

    private fun roundToTwoDecimals(value: Double): Double {
        return round(value * 100) / 100
    }
}
