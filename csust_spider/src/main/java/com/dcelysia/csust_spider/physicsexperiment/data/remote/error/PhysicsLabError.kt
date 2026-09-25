package com.dcelysia.csust_spider.physicsexperiment.data.remote.error

/**
 * 物理实验模块的错误类型。
 *
 * 与 [com.dcelysia.csust_spider.education.data.remote.error.EduHelperError] 保持同样的风格：
 * 由底层抛出，上层（App）按类型决定交互 —— 尤其是
 * [NeedManualLogin] 需要弹输入框或拉 WebView，而 [VpnLoginFailed] 只需要提示重新绑定学号。
 */
sealed class PhysicsLabError(message: String) : Exception(message) {

    /** 统一认证/VPN 这一层就失败了：账号密码不对、需要验证码、网关异常等。 */
    class VpnLoginFailed(message: String) : PhysicsLabError(message)

    /**
     * 平台层登录不成功：通常是平台密码与统一认证不同、或用户还没提供平台密码。
     *
     * App 收到这个错误应当引导用户输入平台密码，或让用户在 WebView 里手动登录一次。
     */
    class NeedManualLogin(message: String) : PhysicsLabError(message)

    /** 网络或响应异常。 */
    class NetworkError(message: String) : PhysicsLabError(message)
}
