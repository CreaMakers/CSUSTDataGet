package com.dcelysia.csust_spider.physicsexperiment.data.remote.repository

import android.util.Log
import com.dcelysia.csust_spider.core.RetrofitUtils
import com.dcelysia.csust_spider.physicsexperiment.data.remote.PhysicsLabConfig
import com.dcelysia.csust_spider.physicsexperiment.data.remote.api.PhysicsLabApi
import com.dcelysia.csust_spider.physicsexperiment.data.remote.dto.PhysicsLabCatalogItem
import com.dcelysia.csust_spider.physicsexperiment.data.remote.dto.PhysicsLabIndex
import com.dcelysia.csust_spider.physicsexperiment.data.remote.dto.PhysicsLabScore
import com.dcelysia.csust_spider.physicsexperiment.data.remote.dto.PhysicsLabTask
import com.dcelysia.csust_spider.physicsexperiment.data.remote.error.PhysicsLabError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import retrofit2.Response

/**
 * 物理实验平台的数据抓取与解析。
 *
 * ## 数据源（都是 HTML，用 Jsoup 解析）
 *
 * | 方法 | 页面 | 数据位置 |
 * |---|---|---|
 * | [getIndex] | `Index.aspx` | 内嵌 JS 数组 `tree1Data`（全部项目）/ `tree2Data`（我已选） |
 * | [getMyExperiments] | `Student/myalltasklist.aspx` | `table#gvList`（8 列） |
 * | [getScores] | `Student/GeneralCourseScore.aspx` | `table#gvList`（7 列） |
 *
 * ## 会话失效
 *
 * 走 [RetrofitUtils.instancePhysicsLabService]，其 client 挂了
 * [com.dcelysia.csust_spider.core.PhysicsLabRetryInterceptor]：
 * 网关会话失效会自动重新走登录、平台会话失效会只重登平台。本层只负责兜底判断。
 */
class PhysicsLabRepository private constructor() {

    companion object {
        val instance by lazy { PhysicsLabRepository() }

        /** 课表页与成绩页的表格 id（两页结构一致，所以一个解析函数通吃）。 */
        private const val TABLE_ID = "gvList"

        private const val TAG = "PhysicsLabRepository"

        /** 分页控件名，课表页与成绩页都是 `pager1`。 */
        private const val PAGER_TARGET = "pager1"

        /** 网关登录页的落地特征：被弹到这里说明网关会话已经没了。 */
        private const val VPN_LOGIN_MARK = "/enclient"

        /** 分页文本：`每页<font>15</font>条记录,共<font>2</font>页,…` */
        private val TOTAL_PAGES_REGEX = Regex("""共\s*<font[^>]*>\s*(\d+)\s*</font>\s*页""")

        /** `__VIEWSTATE` 的值，回发时必须原样带回。 */
        private val VIEWSTATE_REGEX = Regex(
            """name=["']__VIEWSTATE["'][^>]*?value=["']([^"']*)["']""",
            RegexOption.IGNORE_CASE
        )

        /**
         * 匹配 JS 数组里的一个节点：`{url:"...",text:"...",id:"1"}`。
         *
         * `id` 有两种写法要兼容 —— 首页 HTML 里是 `id:"1"`（字符串），
         * `GetCourse` 接口返回的字面量里是 `id:1`（数字），所以用 `"?(\d+)"?`。
         */
        private val NODE_REGEX = Regex(
            """\{\s*url\s*:\s*"([^"]*)"\s*,\s*text\s*:\s*"([^"]*)"(?:\s*,\s*id\s*:\s*"?(\d+)"?)?"""
        )

        private val COURSE_ID_IN_URL = Regex("""[?&]courseId=(\d+)""")
    }

    private val api by lazy {
        RetrofitUtils.instancePhysicsLabService.create(PhysicsLabApi::class.java)
    }

    // ------------------------------------------------------------------
    // 对外查询
    // ------------------------------------------------------------------

    /** 实验目录（全部项目）+ 我已选的实验，一次请求拿到。 */
    suspend fun getIndex(): PhysicsLabIndex = withContext(Dispatchers.IO) {
        val html = fetch { api.index() }
        // 目录在首页内嵌的 JS 数组里；标记不在，说明拿到的根本不是首页（例如网关页面）
        requireMarker(html, "var tree1Data", "实验目录页")
        PhysicsLabIndex(
            catalog = parseTreeNodes(html, "tree1Data"),
            selected = parseTreeNodes(html, "tree2Data"),
        )
    }

    /** 我的实验课表（含翻页）。 */
    suspend fun getMyExperiments(): List<PhysicsLabTask> = withContext(Dispatchers.IO) {
        fetchAllTableRows("实验课表页") {
            api.myAllTaskList(
                generalCourseId = PhysicsLabConfig.GENERAL_COURSE_ID,
                generalCourseName = PhysicsLabConfig.GENERAL_COURSE_NAME,
            )
        }.map { row ->
            PhysicsLabTask(
                courseId = row["项目编号"].orEmpty(),
                courseName = row["项目名称"].orEmpty(),
                batch = row["批次"].orEmpty(),
                teacher = row["教师姓名"].orEmpty(),
                location = row["上课地址"].orEmpty(),
                time = row["上课时间"].orEmpty(),
                hours = row["课时"].orEmpty(),
                weekday = row["星期"].orEmpty(),
            )
        }
    }

    /** 我的成绩（含翻页）。 */
    suspend fun getScores(): List<PhysicsLabScore> = withContext(Dispatchers.IO) {
        fetchAllTableRows("成绩页") { api.myScores() }.map { row ->
            PhysicsLabScore(
                courseCode = row["课程代码"].orEmpty(),
                courseName = row["课程名称"].orEmpty(),
                projectName = row["项目名称"].orEmpty(),
                previewScore = row["预习成绩"].orEmpty(),
                operationScore = row["操作成绩"].orEmpty(),
                reportScore = row["报告成绩"].orEmpty(),
                totalScore = row["总成绩"].orEmpty(),
            )
        }
    }

    // ------------------------------------------------------------------
    // 请求包装
    // ------------------------------------------------------------------

    /**
     * 统一的响应处理。
     *
     * 会话失效理论上已经被拦截器兜住（自动重登 + 重试一次）；
     * 走到这里还不对，说明自动重登也失败了，交给上层提示用户。
     */
    private suspend fun fetch(block: suspend () -> Response<String>): String = checkedHtml(block())

    /**
     * 响应兜底校验。
     *
     * 顺序有意为之：**先判"这不是我们要的页面"，再按 HTTP 码兜底**。网关错误页实测就是
     * `HTTP 500`，若先按状态码判，它会被当成普通网络错误，上层就丢掉"会话失效"这个语义了。
     *
     * 判据覆盖会话失效的三种确定形态：落地 `/enclient`（网关登录页）、网关错误页、平台登录页。
     */
    private fun checkedHtml(response: Response<String>): String {
        val html = response.body().orEmpty()
        val landingUrl = response.raw().request.url.toString()
        if (landingUrl.contains(VPN_LOGIN_MARK)) {
            throw PhysicsLabError.VpnLoginFailed("物理实验网关会话已失效，请重新登录")
        }
        if (PhysicsLabConfig.isGatewayErrorPage(html)) {
            throw PhysicsLabError.VpnLoginFailed("物理实验网关拒绝了本次请求，请重新登录")
        }
        if (PhysicsLabConfig.isPlatformLoginPage(html)) {
            throw PhysicsLabError.NeedManualLogin("物理实验平台会话已失效，请重新登录")
        }
        if (!response.isSuccessful) {
            throw PhysicsLabError.NetworkError("物理实验平台请求失败（HTTP ${response.code()}）")
        }
        if (html.isBlank()) {
            throw PhysicsLabError.NetworkError("物理实验平台响应为空")
        }
        return html
    }

    /**
     * 校验页面上确实有预期的标记。
     *
     * 解析函数对"结构不存在"和"结构在但没有数据"都给空结果，而会话失效时页面上没有预期标记，
     * 于是会被上层读成"这个学生没有数据"。这里把两者明确分开：标记缺失视为会话问题，
     * 标记在但 0 条则是合法的空结果（学生确实还没选实验 / 还没出分）。
     */
    private fun requireMarker(html: String, marker: String, pageName: String) {
        if (!html.contains(marker)) {
            throw PhysicsLabError.VpnLoginFailed("物理实验平台返回的页面不是$pageName，可能是会话已失效")
        }
    }

    /** 表格页的同一道校验，按真实结构判断（`table#gvList` 存不存在）。 */
    private fun requireTablePage(html: String, pageName: String) {
        if (Jsoup.parse(html).selectFirst("table#$TABLE_ID") == null) {
            throw PhysicsLabError.VpnLoginFailed("物理实验平台返回的页面不是$pageName，可能是会话已失效")
        }
    }

    /**
     * 抓取一张分页表格的全部行：第 1 页来自 [request]，后续页用 WebForms 回发。
     *
     * 课表页 20 条/页、成绩页 15 条/页，不翻页会**静默少数据**，所以必须取全。
     * 回发用的是上一页 HTML 里的 `__VIEWSTATE`，因此逐页串行。
     *
     * @param pageName 只用于报错文案，说明"本该看到哪一张页面"。
     */
    private suspend fun fetchAllTableRows(
        pageName: String,
        request: suspend () -> Response<String>
    ): List<Map<String, String>> {
        val first = request()
        var html = checkedHtml(first)
        // 解析前先确认这确实是目标表格页：表结构缺失时解析函数只会返回空列表，
        // 那会把会话失效伪装成"这个学生没有数据"。
        requireTablePage(html, pageName)
        val pageUrl = first.raw().request.url.toString()
        val rows = parseTable(html).toMutableList()

        val pages = TOTAL_PAGES_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull()
        if (pages == null && html.contains(PAGER_TARGET)) {
            // 有分页控件却读不出页数，多半是平台改了文案：这里会按单页处理并少数据，
            // 所以留一条告警，避免将来变成静默失败。
            Log.w(TAG, "分页文案未识别，按单页处理，数据可能不全")
        }
        val totalPages = pages ?: 1
        if (totalPages <= 1) return rows
        Log.d(TAG, "分页：共 $totalPages 页，开始回发取全")

        for (page in 2..totalPages) {
            val posted = api.postback(
                url = pageUrl,
                referer = pageUrl,
                eventTarget = PAGER_TARGET,
                eventArgument = page.toString(),
                viewState = VIEWSTATE_REGEX.find(html)?.groupValues?.get(1).orEmpty()
            )
            html = checkedHtml(posted)
            // 回发被弹回别的页面（会话失效 / 平台改版）时同样要拦下来，
            // 否则这一页会贡献 0 行，最后拼出一份"看起来正常但缺数据"的结果。
            requireTablePage(html, pageName)
            val pageRows = parseTable(html)
            Log.d(TAG, "分页：第 $page/$totalPages 页 ${pageRows.size} 行")
            rows += pageRows
        }
        return rows
    }


    // ------------------------------------------------------------------
    // 首页内嵌 JS 数据树
    // ------------------------------------------------------------------

    /**
     * 从 HTML 里截出 `var <name> = [ ... ]` 的数组字面量。
     *
     * 用**括号配对扫描**而不是正则：数据里含中文、括号、引号，正则的贪婪匹配会翻车。
     */
    private fun extractJsArray(html: String, varName: String): String? {
        val at = html.indexOf("var $varName")
        if (at < 0) return null
        val start = html.indexOf('[', at)
        if (start < 0) return null

        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (i in start until html.length) {
            val ch = html[i]
            val q = quote
            if (q != null) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == q -> quote = null
                }
                continue
            }
            when (ch) {
                '"', '\'' -> quote = ch
                '[', '{' -> depth++
                ']', '}' -> {
                    depth--
                    if (depth == 0) return html.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** 解析 JS 数组里的实验节点；顺序与页面一致。 */
    private fun parseTreeNodes(html: String, varName: String): List<PhysicsLabCatalogItem> {
        val segment = extractJsArray(html, varName) ?: return emptyList()
        return NODE_REGEX.findAll(segment).map { match ->
            val url = match.groupValues[1]
            val rawText = match.groupValues[2]
            val idInNode = match.groupValues[3]
            PhysicsLabCatalogItem(
                courseId = idInNode.ifBlank {
                    COURSE_ID_IN_URL.find(url)?.groupValues?.get(1).orEmpty()
                },
                name = rawText.replace("(已选)", "").trim(),
                campus = campusOf(rawText),
                selected = rawText.contains("(已选)"),
            )
        }.toList()
    }

    private fun campusOf(text: String): String = when {
        text.contains("云塘") -> "云塘校区"
        text.contains("金盆岭") -> "金盆岭校区"
        else -> ""
    }

    // ------------------------------------------------------------------
    // 通用表格解析
    // ------------------------------------------------------------------

    /**
     * 把 `table#gvList` 解析成 `表头 → 单元格` 的映射列表。
     *
     * 按**表头名**取值而不是按列下标：平台将来调整列顺序也不会解析错。
     * 两页（课表 / 成绩）共用这个函数。
     */
    private fun parseTable(html: String): List<Map<String, String>> {
        val table = Jsoup.parse(html).selectFirst("table#$TABLE_ID") ?: return emptyList()
        val rows = table.select("tr")
        val headerRowIndex = rows.indexOfFirst { it.selectFirst("th") != null }
        if (headerRowIndex < 0) return emptyList()

        // Jsoup 的 text() 已经处理了 &nbsp; 与标签剥离
        val headers = rows[headerRowIndex].select("th, td").map { it.text().trim() }

        return rows.drop(headerRowIndex + 1).mapNotNull { row ->
            val cells = row.select("td, th").map { it.text().trim() }
            if (cells.isEmpty() || cells.all { it.isEmpty() }) return@mapNotNull null
            headers.mapIndexedNotNull { index, header ->
                if (header.isEmpty()) null else header to cells.getOrElse(index) { "" }
            }.toMap()
        }
    }
}
