package com.dcelysia.csust_spider.physicsexperiment.data.remote

import com.tencent.mmkv.MMKV

/**
 * 物理实验平台的独立凭据。
 *
 * ## 为什么单独存一份密码
 *
 * 物理实验平台有自己独立的「忘记密码」入口，**它的密码不一定等于统一认证密码**，
 * 所以不能直接复用
 * [com.dcelysia.csust_spider.education.data.remote.EducationData.studentPassword]。
 *
 * 平台密码为空时，本模块无法自动登录平台，会抛出
 * [com.dcelysia.csust_spider.physicsexperiment.data.remote.error.PhysicsLabError.NeedManualLogin]，
 * 由 App 引导用户输入一次平台密码、或让用户手动登录一次。
 *
 * ## 存储
 *
 * 只存本地 MMKV（`physics_lab_cache`），不上传任何服务端 —— 与项目对用户
 * "密码仅保存在本地" 的承诺一致。
 */
object PhysicsLabData {

    private const val MMKV_ID = "physics_lab_cache"
    private const val KEY_PLATFORM_PASSWORD = "platform_password"

    private val mmkv by lazy { MMKV.mmkvWithID(MMKV_ID) }

    /** 平台自己的密码；为空表示用户尚未提供。 */
    var platformPassword: String
        get() = mmkv.getString(KEY_PLATFORM_PASSWORD, "") ?: ""
        set(value) {
            mmkv.putString(KEY_PLATFORM_PASSWORD, value)
        }

    /** 是否已经有平台密码，供 App 决定要不要弹输入框。 */
    val hasPlatformPassword: Boolean
        get() = platformPassword.isNotBlank()

    /** 仅清平台密码（切换账号或用户主动清除时调用）。 */
    fun clear() {
        platformPassword = ""
    }
}
