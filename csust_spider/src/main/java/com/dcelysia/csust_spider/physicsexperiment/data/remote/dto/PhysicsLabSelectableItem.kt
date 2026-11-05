package com.dcelysia.csust_spider.physicsexperiment.data.remote.dto

/**
 * "项目选择"页（`Student/choosecourse.aspx`）里的一行，列与页面一一对应：
 * `项目编号 | 项目名称 | 课时 | 操作`。
 *
 * [action] 保持操作列原文（实测三种：`选取` / `退选` / `--`）。
 * 库层**不**把它翻译成布尔值 —— 实测 `--` 并不代表"未选"（已选但不可退的项目也是 `--`），
 * 判断"我选没选"要用 [PhysicsLabIndex.selected]。
 */
data class PhysicsLabSelectableItem(
    /** 项目编号 */
    val courseId: String,
    /** 项目名称（页面原文，可能带"（xx校区）"后缀） */
    val name: String,
    /** 课时（页面原文） */
    val hours: String,
    /** 操作列原文：`选取` / `退选` / `--` */
    val action: String,
)
