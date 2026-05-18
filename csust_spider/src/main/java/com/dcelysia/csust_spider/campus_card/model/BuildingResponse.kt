package com.example.csustdataget.CampusCard.model

import com.google.gson.annotations.SerializedName

data class BuildingResponse(
    @SerializedName("query_elec_building")
    val queryElecBuilding: QueryBuildingRequest
)
