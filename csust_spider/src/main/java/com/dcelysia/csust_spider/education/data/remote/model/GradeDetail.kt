package com.dcelysia.csust_spider.education.data.remote.model

import androidx.annotation.Keep

@Keep
data class GradeDetail(
    val components: List<GradeComponent>, //成绩组成
    val totalGrade: Int //总成绩
)
@Keep
data class GradeDetailResponse(
    val code: String,
    val msg: String,
    val data: GradeDetail? = null
)