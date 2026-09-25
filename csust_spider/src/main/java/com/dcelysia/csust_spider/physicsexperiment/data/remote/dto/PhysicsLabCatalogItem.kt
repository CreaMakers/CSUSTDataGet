package com.dcelysia.csust_spider.physicsexperiment.data.remote.dto

/**
 * 实验项目（来自首页 `Index.aspx` 内嵌的 `tree1Data`，实测 72 项）。
 *
 * @param courseId 平台内部的项目编号（注意有空洞，最大 id 不等于条目数）
 * @param name 项目名称，已去掉 "(已选)" 后缀
 * @param campus 校区：`云塘校区` / `金盆岭校区` / 空串（名称里没有校区标识时）
 * @param selected 我是否已选该项目
 */
data class PhysicsLabCatalogItem(
    val courseId: String,
    val name: String,
    val campus: String,
    val selected: Boolean,
)
