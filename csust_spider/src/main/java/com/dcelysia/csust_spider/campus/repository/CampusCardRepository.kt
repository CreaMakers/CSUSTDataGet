package com.dcelysia.csust_spider.campus.repository

import android.util.Log
import com.dcelysia.csust_spider.core.KtorUtils
import com.dcelysia.csust_spider.core.Resource
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

class CampusCardRepository private constructor() {
    companion object {
        val instance by lazy { CampusCardRepository() }
        const val TAG = "CampusCardRepository"

        private const val GET_ROOM_INFO = "synjones.onecard.query.elec.roominfo"
        private const val ENDPOINT = "web/Common/Tsm.html"

        private val buildingMap =
                mapOf(
                        "金盆岭校区" to "0030000000002502",
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
                        "云塘校区" to "0030000000002501",
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
    }

    // ───────────────────────────────────────────────
    // 电费查询 (Electricity Query)
    // ───────────────────────────────────────────────

    /**
     * 查询宿舍电费余量
     *
     * @param campusName 校区名称（如 "云塘校区"）
     * @param buildingName 楼栋名称（如 "16栋A区"）
     * @param roomId 房间号（如 "101"）
     * @return Flow<Resource<Double>> 剩余电量（度）
     */
    fun getElectricity(campusName: String, buildingName: String, roomId: String) =
            flow {
                emit(Resource.Loading())

                val campusId = buildingMap[campusName]
                val buildingId = buildingMap[buildingName]

                if (campusId.isNullOrBlank() || buildingId.isNullOrBlank()) {
                    emit(Resource.Error("无效的校区或楼栋名称: $campusName / $buildingName"))
                    return@flow
                }

                // 按原 CampusCardHelper 拼装 JSON 请求体
                val jsonData =
                    buildQueryJson(
                        campusId = campusId,
                        campusName = campusName,
                        buildingId = buildingId,
                        roomId = roomId
                    )

                val response =
                    KtorUtils.campusClient.submitForm(
                        url = ENDPOINT,
                        formParameters =
                            Parameters.Companion.build {
                                append("jsondata", jsonData)
                                append("funname", GET_ROOM_INFO)
                                append("json", "true")
                            }
                    )

                val body = response.bodyAsText()
                Log.d(TAG, "getElectricity response: $body")

                if (body.isBlank()) {
                    emit(Resource.Error("响应体为空"))
                    return@flow
                }

                if (body.contains("无法获取房间信息")) {
                    emit(Resource.Error("无法获取房间信息，请检查楼栋或房间号"))
                    return@flow
                }

                val electricity =
                    extractElectricity(body)
                        ?: run {
                            emit(Resource.Error("无法从响应中解析电量数据"))
                            return@flow
                        }

                emit(Resource.Success(electricity))
            }
                    .catch { e ->
                        Log.e(TAG, "getElectricity: 查询电费失败", e)
                        emit(Resource.Error("查询电费失败: ${e.message}"))
                    }

    /** 拼装请求 JSON 字符串，与原 CampusCardHelper.queryElectricity 保持一致 */
    private fun buildQueryJson(
            campusId: String,
            campusName: String,
            buildingId: String,
            roomId: String
    ): String {
        // 手动拼 JSON，避免引入额外序列化依赖
        return """{"query_elec_roominfo":{"aid":"$campusId","account":"0000001","room":{"roomid":"$roomId","room":"$roomId"},"floor":{"floorid":"","floor":""},"area":{"area":"$campusName","areaname":"$campusName"},"building":{"buildingid":"$buildingId","building":""}}}"""
    }

    /** 从响应文本中提取电量数字（与原 extractElectricityFromString 保持一致） */
    private fun extractElectricity(text: String): Double? {
        val patterns =
                listOf(
                        """电量[:：\s]*([0-9]+(?:\.[0-9]+)?)""",
                        """剩余[:：\s]*([0-9]+(?:\.[0-9]+)?)""",
                        """"balance"\s*:\s*([0-9]+(?:\.[0-9]+)?)""",
                        """balance[:：\s]*([0-9]+(?:\.[0-9]+)?)""",
                        """([0-9]+(?:\.[0-9]+)?)\s*(?:度|kwh|kWh)"""
                )

        for (p in patterns) {
            val captured = Regex(p, RegexOption.IGNORE_CASE).find(text)?.groups?.get(1)?.value
            if (!captured.isNullOrEmpty()) {
                return captured.toDoubleOrNull()
            }
        }

        // fallback: 第一个出现的数字
        return Regex("""([0-9]+(?:\.[0-9]+)?)""")
                .find(text)
                ?.groups
                ?.get(1)
                ?.value
                ?.toDoubleOrNull()
    }
}