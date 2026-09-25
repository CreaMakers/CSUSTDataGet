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
        PhysicsLabIndex(
            catalog = parseTreeNodes(html, "tree1Data"),
            selected = parseTreeNodes(html, "tree2Data"),
        )
    }

    /** 我的实验课表（含翻页）。 */
    suspend fun getMyExperiments(): List<PhysicsLabTask> = withContext(Dispatchers.IO) {
        fetchAllTableRows {
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
        fetchAllTableRows { api.myScores() }.map { row ->
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
     * 走到这里还是登录页，说明自动重登也失败了，交给上层提示用户。
     */
    private suspend fun fetch(block: suspend () -> Response<String>): String = checkedHtml(block())

    private fun checkedHtml(response: Response<String>): String {
        if (!response.isSuccessful) {
            throw PhysicsLabError.NetworkError("物理实验平台请求失败（HTTP ${response.code()}）")
        }
        val html = response.body().orEmpty()
        if (html.isBlank()) {
            throw PhysicsLabError.NetworkError("物理实验平台响应为空")
        }
        if (isLoginPage(html)) {
            throw PhysicsLabError.NeedManualLogin("物理实验平台会话已失效，请重新登录")
        }
        return html
    }

    /**
     * 抓取一张分页表格的全部行：第 1 页来自 [request]，后续页用 WebForms 回发。
     *
     * 课表页 20 条/页、成绩页 15 条/页，不翻页会**静默少数据**，所以必须取全。
     * 回发用的是上一页 HTML 里的 `__VIEWSTATE`，因此逐页串行。
     */
    private suspend fun fetchAllTableRows(
        request: suspend () -> Response<String>
    ): List<Map<String, String>> {
        val first = request()
        var html = checkedHtml(first)
        val pageUrl = first.raw().request.url.toString()
        val rows = parseTable(html).toMutableList()

        val totalPages = TOTAL_PAGES_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 1
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
            val pageRows = parseTable(html)
            Log.d(TAG, "分页：第 $page/$totalPages 页 ${pageRows.size} 行")
            rows += pageRows
        }
        return rows
    }

    /** 平台自己的登录页长这样（登录框 id 是固定的）。 */
    private fun isLoginPage(html: String): Boolean =
        html.contains("id=\"txtUserName\"") || html.contains("id=\"frmUser\"")

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
