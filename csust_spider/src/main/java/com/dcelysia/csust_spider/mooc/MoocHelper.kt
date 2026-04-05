package com.dcelysia.csust_spider.mooc

import com.dcelysia.csust_spider.mooc.data.remote.dto.MoocTest
import com.dcelysia.csust_spider.mooc.data.remote.repository.MoocRepository

object MoocHelper {
    private val repository by lazy { MoocRepository.instance }

    /**
     * 获取指定课程的测验列表。
     *
     * 参考 iOS `MoocHelper.getCourseTests`，
     * 这里直接提供简化后的 suspend 方法并返回解析后的测试数据。
     *
     * @param courseId 课程 ID
     * @return 课程测验列表
     */
    suspend fun getCourseTests(courseId: String): List<MoocTest> {
        return repository.getCourseTestsDirect(courseId)
    }

    /**
     * 清理 MOOC 本地会话缓存。
     *
     * 可在切换账号、密码更新或需要强制重新登录 MOOC 时调用。
     */
    suspend fun clearMoocLocalSession() {
        repository.clearMoocLocalSession()
    }
}
