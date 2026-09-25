package com.dcelysia.csust_spider.physicsexperiment.data.remote.dto

/**
 * 我的一条实验成绩（来自"我的成绩"页 `Student/GeneralCourseScore.aspx` 的 `table#gvList`）。
 *
 * 页面实测 7 列，样例：
 * ```
 * 0702100025 | 大学物理实验B | D空气热机原理(金盆岭校区） | -- | -- | -- | 0
 * ```
 *
 * 成绩字段保持原文：未出分时页面给的是 `--`，已出分时是数字字符串。
 * 交给上层决定展示与解析方式。
 */
data class PhysicsLabScore(
    /** 课程代码 */
    val courseCode: String,
    /** 课程名称 */
    val courseName: String,
    /** 实验项目名称 */
    val projectName: String,
    /** 预习成绩 */
    val previewScore: String,
    /** 操作成绩 */
    val operationScore: String,
    /** 报告成绩 */
    val reportScore: String,
    /** 总成绩 */
    val totalScore: String,
)
