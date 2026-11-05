package com.dcelysia.csust_spider.physicsexperiment.data.remote.dto

/**
 * "项目选择"页（`Student/choosecourse.aspx`）的整体内容。
 *
 * 与 `Index.aspx` 那种"全项目目录"不同，这一页给的是**本学期开设的项目**
 * （实测只有 20 余项、编号会跳号，而不是目录里的 72 项）。
 */
data class PhysicsLabSelectable(
    /** 本学期的项目列表（含已选、可选、以及操作列为 `--` 的项） */
    val courses: List<PhysicsLabSelectableItem>,
    /**
     * 页面上的配额提示**原文**，形如
     * `应选：20项目(64课时)， 已选：19项目(63课时)， 还需要选择1项目(1课时)`
     * （页面标签间的空白会被折叠，这是解析后的实际形态）。
     * 页面上没有这句（例如选课已完成）时为 null。
     */
    val quotaText: String?,
)
