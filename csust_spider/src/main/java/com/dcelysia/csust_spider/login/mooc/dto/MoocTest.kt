package com.dcelysia.csust_spider.login.mooc.dto

data class MoocTest(
    val title: String,
    val startTime: String,
    val endTime: String,
    val allowRetake: Int?,
    val timeLimit: Int,
    val isSubmitted: Boolean
)