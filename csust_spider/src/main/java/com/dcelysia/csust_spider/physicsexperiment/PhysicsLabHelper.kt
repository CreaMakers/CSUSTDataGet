package com.dcelysia.csust_spider.physicsexperiment

import com.dcelysia.csust_spider.core.RetrofitUtils
import com.dcelysia.csust_spider.education.data.remote.EducationData
import com.dcelysia.csust_spider.physicsexperiment.data.remote.PhysicsLabData
import com.dcelysia.csust_spider.physicsexperiment.data.remote.dto.PhysicsLabIndex
import com.dcelysia.csust_spider.physicsexperiment.data.remote.dto.PhysicsLabScore
import com.dcelysia.csust_spider.physicsexperiment.data.remote.dto.PhysicsLabTask
import com.dcelysia.csust_spider.physicsexperiment.data.remote.error.PhysicsLabError
import com.dcelysia.csust_spider.physicsexperiment.data.remote.repository.PhysicsLabRepository
import com.dcelysia.csust_spider.physicsexperiment.data.remote.service.PhysicsLabAuthService

/**
 * 物理实验教学管理系统（大学物理实验）的对外门面。
 *
 * ## App 侧接入的最小流程
 *
 * ```kotlin
 * try {
 *     PhysicsLabHelper.login()
 *     // 登录成功，接下来可以查询数据
 * } catch (e: PhysicsLabError.NeedManualLogin) {
 *     // 平台密码缺失或不对：弹输入框让用户填，
 *     // 填完调用 PhysicsLabHelper.setPlatformPassword(it) 再 login() 一次；
 *     // 或者拉 WebView 让用户手动登录一次（见下方说明）。
 * } catch (e: PhysicsLabError.VpnLoginFailed) {
 *     // 统一认证都过不了：提示用户重新绑定学号
 * } catch (e: PhysicsLabError.NetworkError) {
 *     // 网络问题
 * }
 * ```
 *
 * ## 关于"手动登录一次"
 *
 * 平台的会话由网关在服务端代持，**不会以 cookie 形式回传**，所以 WebView 方案不需要
 * "把 cookie 取回来"——只要 WebView 用的是同一个网关会话即可：进入 WebView 之前，
 * 把 [RetrofitUtils.totalCookieJar] 里 `vpn.csust.edu.cn` 的 cookie 注入 WebView 的
 * `CookieManager`（可参考 App 里 `MoocCoursePageActivity` 已有的 cookie 桥接做法）。
 *
 * ⚠️ 但平台会话实测寿命很短（不到 45 分钟就过期），**没有平台密码就必须反复手动登录**。
 * 所以推荐引导用户填一次平台密码，由 [setPlatformPassword] 存到本地，之后即可全自动续期。
 */
object PhysicsLabHelper {

    /**
     * 登录物理实验系统：统一认证 → VPN 会话 → 平台会话。
     *
     * 统一认证用的学号/密码取自 [EducationData]（App 绑定学号时已写入）；
     * 平台密码取自 [PhysicsLabData]，**二者可能不同，不会互相顶替**。
     *
     * @throws PhysicsLabError.VpnLoginFailed 统一认证失败或网关会话建立失败
     * @throws PhysicsLabError.NeedManualLogin 平台密码缺失或平台拒绝登录
     * @throws PhysicsLabError.NetworkError 网络异常
     */
    suspend fun login() {
        val account = EducationData.studentId
        val authPassword = EducationData.studentPassword
        if (account.isBlank() || authPassword.isBlank()) {
            throw PhysicsLabError.VpnLoginFailed("尚未绑定学号，无法登录物理实验系统")
        }

        val platformPassword = PhysicsLabData.platformPassword
        if (platformPassword.isBlank()) {
            throw PhysicsLabError.NeedManualLogin("尚未设置物理实验平台密码，请先让用户输入或手动登录一次")
        }

        PhysicsLabAuthService.login(account, authPassword, platformPassword)
    }

    /**
     * 保存用户输入的物理实验平台密码（仅本地 MMKV）。
     *
     * 平台密码与统一认证密码不一定是同一个，所以由用户单独提供一次。
     */
    fun setPlatformPassword(password: String) {
        PhysicsLabData.platformPassword = password
    }

    /** 是否已经有平台密码，供 App 决定要不要弹输入框。 */
    val hasPlatformPassword: Boolean
        get() = PhysicsLabData.hasPlatformPassword

    /** 丢弃已保存的平台密码（切换账号或用户要求清除时调用）。 */
    fun clearPlatformPassword() {
        PhysicsLabData.clear()
    }

    /**
     * 当前 VPN 会话是否仍然有效。
     *
     * 用于页面进入时的"要不要重新登录"判断；查询接口自身在会话失效时会由
     * 拦截器自动重登，通常不需要业务层主动调用。
     */
    suspend fun isLoggedIn(): Boolean = PhysicsLabAuthService.isVpnSessionAlive()

    /**
     * 清除网关会话 cookie（不影响已保存的平台密码）。
     *
     * 切换学号、强制刷新、或用户主动退出时调用。
     */
    suspend fun clearSession() {
        RetrofitUtils.clearPhysicsLabSession()
    }

    // ------------------------------------------------------------------
    // 数据查询
    //
    // 三个方法都直接抛 [PhysicsLabError]，而不是包成 Resource —— 因为
    // NeedManualLogin / VpnLoginFailed / NetworkError 需要被 App 区分对待
    // （前者要弹输入框或 WebView，后者只需提示），包成 Resource.Error(String)
    // 会把类型信息丢掉。这与 AuthService 抛 EduHelperError 的做法一致。
    // ------------------------------------------------------------------

    /**
     * 实验目录（全部项目，实测 72 项）+ 我已选的实验，**一次请求同时得到**。
     *
     * @throws PhysicsLabError.NeedManualLogin 平台会话失效且无法自动续期（通常是缺平台密码）
     * @throws PhysicsLabError.VpnLoginFailed 网关会话失效且重登失败
     * @throws PhysicsLabError.NetworkError 网络异常
     */
    suspend fun getIndex(): PhysicsLabIndex = PhysicsLabRepository.instance.getIndex()

    /**
     * 我的实验课表。
     *
     * @throws PhysicsLabError 同上
     */
    suspend fun getMyExperiments(): List<PhysicsLabTask> =
        PhysicsLabRepository.instance.getMyExperiments()

    /**
     * 我的成绩（未出分时对应字段是 `--`，已出分时是数字字符串）。
     *
     * @throws PhysicsLabError 同上
     */
    suspend fun getScores(): List<PhysicsLabScore> = PhysicsLabRepository.instance.getScores()
}
