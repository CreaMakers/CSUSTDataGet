package com.dcelysia.csust_spider.physicsexperiment.data.remote.dto

/**
 * 我的一条实验安排（来自"大学物理实验课表"页 `Student/myalltasklist.aspx` 的 `table#gvList`）。
 *
 * 页面实测 8 列，样例：
 * ```
 * 86 | 虚拟仿真实验项目1（金盆岭校区） | 00889 | 虚拟仿真教师0 |
 * 金盆岭校区虚拟仿真实验平台（金盆岭） 线上 | 2026-09-25 13:30 - 15:45 | 6 | 第3周 星期五
 * ```
 *
 * 字段全部保持页面原文（字符串），时间与周次由上层自行解析，
 * 避免库层替业务层做格式化决策。
 */
data class PhysicsLabTask(
    /** 项目编号 */
    val courseId: String,
    /** 项目名称 */
    val courseName: String,
    /** 批次号 */
    val batch: String,
    /** 教师姓名 */
    val teacher: String,
    /** 上课地址（线上项目会带"线上"字样） */
    val location: String,
    /** 上课时间，形如 `2026-09-25 13:30 - 15:45` */
    val time: String,
    /** 课时 */
    val hours: String,
    /** 周次与星期，形如 `第3周 星期五` */
    val weekday: String,
)
