package com.dcelysia.csust_spider.physicsexperiment.data.remote.dto

/**
 * 首页一次请求能拿到的全部数据。
 *
 * `Index.aspx` 的 HTML 里内嵌了两棵 JS 数据树，一次请求即可同时得到：
 * - [catalog]：`tree1Data`，全部实验项目（实测 72 项）
 * - [selected]：`tree2Data`，我已选的实验
 *
 * 平台没有提供"只返回我已选"的 JSON 接口（`GetCourse` 只返回 [catalog] 那一份），
 * 所以这两个数据集只能从首页 HTML 里取。
 */
data class PhysicsLabIndex(
    val catalog: List<PhysicsLabCatalogItem>,
    val selected: List<PhysicsLabCatalogItem>,
)
