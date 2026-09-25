package com.dcelysia.csust_spider.physicsexperiment.data.remote

/**
 * 物理实验教学管理系统（大学物理实验选课/成绩系统）的接入配置。
 *
 * ## 系统拓扑
 *
 * 平台本体是**校内地址** `http://10.255.65.52/`，校外访问必须经过学校 WebVPN 网关：
 *
 * ```
 * 浏览器看到的地址： https://vpn.csust.edu.cn/http/<token>/Index.aspx
 * 网关回源的地址：   http://10.255.65.52/Index.aspx
 * ```
 *
 * `<token>` 是网关对"目标主机:端口"的编码。**同一主机的所有 URL 共用同一个 token**，
 * 所以只要拿到一次，之后任意页面路径直接拼在它后面即可。
 *
 * ## 会话模型（两层，实测确认）
 *
 * 1. **网关层**：登录统一身份认证(CAS)后，网关下发 `access_token` 等 cookie，
 *    挂在 `vpn.csust.edu.cn` 域下；
 * 2. **平台层**：平台自己的会话**由网关在服务端代持**，不会以 cookie 形式回传给客户端
 *    （实测：`login.aspx` 的响应里没有 `Set-Cookie`，浏览器 cookie 列表也不会新增）。
 *
 * 因此本模块只需要维护网关层的 cookie，平台会话跟着网关会话自动生效。
 */
object PhysicsLabConfig {

    /** 网关主机。VPN 会话 cookie 全部挂在这个域下。 */
    const val VPN_HOST = "vpn.csust.edu.cn"

    /** 网关源站 */
    const val VPN_ORIGIN = "https://$VPN_HOST"

    /**
     * 平台在网关里的路径 token。
     *
     * ⚠️ **这是可配置项：学校调整网关配置后会变，变了就改这里。**
     *
     * 重新获取方式：
     * 1. 浏览器登录 <https://vpn.csust.edu.cn/>；
     * 2. 学校主页 → 机构设置 → 教学院 → 物理与电子科学学院；
     * 3. 页面最下方点「实验预约」，右键该链接 → 复制链接地址，形如：
     *    `https://vpn.csust.edu.cn/http/webvpnee536efb7808aac9b0bc36403333c380/`
     *    其中 `/http/` 与后面第一个 `/` 之间的那一段就是 token。
     */
    var platformToken: String = "webvpnee536efb7808aac9b0bc36403333c380"

    /**
     * 平台在网关下的 baseUrl。
     *
     * 注意必须以 `/` 结尾（Retrofit 的硬性要求），且是 `get()` 动态读取
     * [platformToken]，所以改动 token 后下次创建 Retrofit 实例即可生效。
     */
    val platformBaseUrl: String
        get() = "$VPN_ORIGIN/http/$platformToken/"

    /**
     * 网关自己的 CAS 回调地址，用作统一身份认证的 `service` 参数。
     *
     * 来源：未登录状态下访问平台会 302 到
     * `https://vpn.csust.edu.cn:443/https/webvpn<hash>/authserver/login?service=<本值>`，
     * 该 Location 头就是整个 VPN 登录流程的钥匙。
     */
    const val VPN_CAS_CALLBACK =
        "https://vpn.csust.edu.cn:443/enclient/api/users/auth/cas/callback"

    /**
     * 网关的用户信息接口（JSON），用作"VPN 会话是否有效"的探针。
     *
     * 有效会话返回形如 `{"code":"200","messages":"OK","data":{"username":"...","name":"..."}}`。
     * 用它判断会话比解析 HTML 可靠得多。
     */
    const val VPN_USER_INFO_URL = "$VPN_ORIGIN/enclient/api/users/info"

    /** 大学物理实验在本平台里的课程 ID（取自首页内嵌的 tree1Data）。 */
    const val GENERAL_COURSE_ID = "2"

    /** 同上，课程名。平台的部分接口要求把中文课程名一并带上。 */
    const val GENERAL_COURSE_NAME = "大学物理实验"

    /** 平台登录时固定的用户类型：0=学生，1=老师，2=管理员。 */
    const val USER_TYPE = "0"

    // TODO(二期)：校内直连。平台本体是 http://10.255.65.52/，在校园网内可直连并跳过
    //  CAS 与 VPN 两步；但内网 IP 的 HTTPS 证书不匹配 IP、且 IP 可能漂移，暂不实现。
}
