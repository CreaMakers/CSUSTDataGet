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

    /**
     * 网关错误页的页面标记。
     *
     * 实测：**不带会话 cookie** 直接请求 `/http/<token>/Index.aspx`，网关返回 `HTTP 500`
     * 加一张错误页，页面里引用 `ban_error.css`、正文含 `class="error_page"`。
     *
     * 这类页面既不是平台登录页、也解析不出任何数据。不把它识别成"网关侧失败"的话，
     * 上层会把"会话失效"读成"这个学生没有数据"，静默给出空目录 / 空课表 / 空成绩。
     *
     * ⚠️ 判据刻意用**页面标记**而不是"5xx 就算会话失效"：一次上游 502 不代表用户会话有问题，
     * 按状态码判会白白清掉用户会话。
     */
    const val GATEWAY_ERROR_MARK_CSS = "ban_error.css"
    const val GATEWAY_ERROR_MARK_CLASS = "error_page"

    /** 正文是不是网关自己的错误页，供拦截器与数据层共用同一套判据。 */
    fun isGatewayErrorPage(body: String): Boolean =
        body.contains(GATEWAY_ERROR_MARK_CSS) || body.contains(GATEWAY_ERROR_MARK_CLASS)

    /** 平台登录页的登录框 id（页面固定，实测不变）。 */
    const val PLATFORM_LOGIN_MARK_USERNAME = "id=\"txtUserName\""
    const val PLATFORM_LOGIN_MARK_FORM = "id=\"frmUser\""

    /**
     * 正文是不是物理实验平台自己的登录页。
     *
     * 和网关错误页一样，这属于"会话失效"的判据，**拦截器与数据层必须共用同一份实现**：
     * 拦截器用它决定要不要自动重登，数据层用它决定要不要抛"请重新登录"。
     * 两边各写一份的话，一旦平台改版只改了其中一处，就会出现
     * "拦截器认为会话正常、数据层认为要重登"（或反过来）的错位。
     */
    fun isPlatformLoginPage(body: String): Boolean =
        body.contains(PLATFORM_LOGIN_MARK_USERNAME) || body.contains(PLATFORM_LOGIN_MARK_FORM)

    /** 大学物理实验在本平台里的课程 ID（取自首页内嵌的 tree1Data）。 */
    const val GENERAL_COURSE_ID = "2"

    /** 同上，课程名。平台的部分接口要求把中文课程名一并带上。 */
    const val GENERAL_COURSE_NAME = "大学物理实验"

    /** 平台登录时固定的用户类型：0=学生，1=老师，2=管理员。 */
    const val USER_TYPE = "0"
}
