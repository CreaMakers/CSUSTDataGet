package com.dcelysia.csust_spider.education.data.remote

import com.dcelysia.csust_spider.core.MigratingKVStore

object EducationData {
    private val kvStore by lazy { MigratingKVStore.get("education_cache") }
    private const val KEY_STUDENT_ID = "student_id"
    private const val KEY_PASSWORD = "student_password"

    var studentId: String
        get() = kvStore.getString(KEY_STUDENT_ID, "") ?: ""
        set(value) {
            kvStore.putString(KEY_STUDENT_ID, value)
        }

    var studentPassword: String
        get() = kvStore.getString(KEY_PASSWORD, "") ?: ""
        set(value) {
            kvStore.putString(KEY_PASSWORD, value)
        }

    fun clear() {
        studentId = ""
        studentPassword = ""
    }
}