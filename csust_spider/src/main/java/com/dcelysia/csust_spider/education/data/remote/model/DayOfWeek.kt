package com.dcelysia.csust_spider.education.data.remote.model

/**
 * 星期枚举。
 *
 * @property value 教务系统接口中使用的星期数值，范围为 1-7
 */
enum class DayOfWeek(val value: Int) {
    MONDAY(1),
    TUESDAY(2),
    WEDNESDAY(3),
    THURSDAY(4),
    FRIDAY(5),
    SATURDAY(6),
    SUNDAY(7)
}
