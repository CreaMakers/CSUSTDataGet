package com.dcelysia.csust_spider.edu.model

import androidx.annotation.Keep

@Keep
enum class DisplayMode(val id: String) {
    BEST_GRADE("0"), // 显示最好成绩
    LATEST_GRADE("1") // 显示最新成绩
}