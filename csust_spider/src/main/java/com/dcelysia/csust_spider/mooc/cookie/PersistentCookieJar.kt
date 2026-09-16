package com.dcelysia.csust_spider.mooc.cookie

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

class PersistentCookieJar private constructor() : CookieJar {
    private val gson = Gson()

    // 内存缓存：存不可变 List，合并时整体替换，避免并发修改
    private val memoryCache = ConcurrentHashMap<String, List<Cookie>>()
    var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val pendingJobs = ConcurrentHashMap<String, Job>()
    private val saveDelayMs = 500L

    companion object {
        private const val TAG = "PersistentCookieJar"
        private const val MMKV_ID = "csust_cookie_jar"
        val instance by lazy { PersistentCookieJar() }

        /**
         * 合并响应 cookie。
         *
         * 同名 cookie 只保留最新一个（不按 path 并存）：教务重新登录后新会话是
         * JSESSIONID(path=/jsxsd)，登录前的旧会话是 JSESSIONID(path=/)，两条都命中
         * /jsxsd/... 请求时会被一起发送，服务端只认第一个，可能取到已失效的旧会话并返回 404 空响应。
         */
        internal fun mergeCookies(
            existing: List<Cookie>,
            incoming: List<Cookie>,
            now: Long
        ): List<Cookie> {
            val base = existing.filter { it.expiresAt > now }.toMutableList()
            incoming.forEach { newCookie ->
                base.removeAll { it.name == newCookie.name && it.domain == newCookie.domain }
                if (newCookie.expiresAt > now) {
                    base.add(newCookie)
                }
            }
            return base.filter { it.expiresAt > now }
        }

        fun initialize(context: Context) {
            try {
                MMKV.initialize(context)
            } catch (t: Throwable) {
                Log.w(TAG, "MMKV.initialize failed", t)
            }
        }
    }

    private val mmkv by lazy {
        try {
            MMKV.mmkvWithID(MMKV_ID, MMKV.MULTI_PROCESS_MODE)
        } catch (t: Throwable) {
            Log.w(TAG, "mmkvWithID failed, fallback to default", t)
            MMKV.defaultMMKV()
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        Log.d(TAG, "saveFromResponse: Saving cookies for host: $host")
        val now = System.currentTimeMillis()

        Log.d(TAG, "saveFromResponse: Incoming cookies count=${cookies.size} for host=$host")
        cookies.forEach { Log.d(TAG, "saveFromResponse: incoming: ${formatCookie(it)}") }

        memoryCache.compute(host) { _, existing ->
            val base = existing ?: run {
                val json = mmkv.decodeString(host)
                if (json != null) {
                    parseCookiesFromJson(host, json, now).also { loaded ->
                        Log.d(TAG, "saveFromResponse: Loaded ${loaded.size} cookies from MMKV for host: $host")
                        loaded.forEach { Log.d(TAG, "saveFromResponse: loaded from MMKV: ${formatCookie(it)}") }
                    }
                } else {
                    Log.d(TAG, "saveFromResponse: No cookies found in MMKV for host: $host")
                    emptyList()
                }
            }

            val result = mergeCookies(base, cookies, now)
            Log.d(TAG, "saveFromResponse: After merge cookies count=${result.size} for host=$host")
            result.forEach { Log.d(TAG, "saveFromResponse: merged: ${formatCookie(it)}") }
            result
        }

        jobSave(host)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        Log.d(TAG, "loadForRequest: Loading cookies for host: $host")
        val now = System.currentTimeMillis()

        val list = memoryCache.computeIfAbsent(host) {
            val json = mmkv.decodeString(host)
            Log.d(TAG, "loadForRequest: Reading from MMKV for host: $host, json: $json")
            if (json != null) {
                parseCookiesFromJson(host, json, now).also { cookies ->
                    Log.d(TAG, "loadForRequest: Loaded ${cookies.size} cookies from MMKV for host: $host")
                    cookies.forEach { Log.d(TAG, "loadForRequest: loaded: ${formatCookie(it)}") }
                }
            } else {
                Log.d(TAG, "loadForRequest: No cookies found in MMKV for host: $host")
                emptyList()
            }
        }

        val validList = dedupeByName(list.filter { it.expiresAt > now })
        Log.d(TAG, "loadForRequest: Returning ${validList.size} valid cookies for host: $host")
        validList.forEach { Log.d(TAG, "loadForRequest: returning: ${formatCookie(it)}") }
        return validList
    }

    /**
     * 同一 host 下同名 cookie 只保留最后一个。
     *
     * 历史数据里可能残留 JSESSIONID(path=/) + JSESSIONID(path=/jsxsd) 两条，
     * 一起发送会让服务端取到错误会话，这里做一次兜底清理并回写。
     */
    private fun dedupeByName(cookies: List<Cookie>): List<Cookie> {
        if (cookies.size < 2) return cookies
        val result = LinkedHashMap<String, Cookie>()
        cookies.forEach { result[it.name] = it }
        if (result.size == cookies.size) return cookies
        Log.d(TAG, "loadForRequest: 清理重复 cookie，${cookies.size} -> ${result.size}")
        return result.values.toList()
    }

    /**
     * 从 JSON 解析 cookie，跳过无效条目（字段缺失/null/已过期），不会因单条 cookie 损坏导致崩溃
     */
    private fun parseCookiesFromJson(host: String, json: String, now: Long): List<Cookie> {
        return runCatching {
            val type = object : TypeToken<List<SerializableCookie>>() {}.type
            val serializableCookies: List<SerializableCookie>? = gson.fromJson(json, type)
            if (serializableCookies.isNullOrEmpty()) {
                mmkv.removeValueForKey(host)
                return emptyList()
            }
            serializableCookies.mapNotNull { sc ->
                runCatching { sc.toOkHttpCookieOrNull() }
                    .onFailure { Log.w(TAG, "parseCookiesFromJson: skip malformed cookie for host=$host", it) }
                    .getOrNull()
            }.filter { it.expiresAt > now }
        }.onFailure {
            Log.e(TAG, "parseCookiesFromJson: failed to parse cookies for host=$host, clearing cache", it)
            mmkv.removeValueForKey(host)
        }.getOrElse { emptyList() }
    }

    private fun jobSave(host: String) {
        pendingJobs[host]?.let { job ->
            if (!job.isCompleted && !job.isCancelled) {
                Log.d(TAG, "scheduleSave: cancelling existing job for host=$host")
                job.cancel()
            }
        }
        val job = scope.launch {
            delay(saveDelayMs)
            persistHost(host)
        }
        pendingJobs[host] = job
    }

    @Synchronized
    private fun persistHost(host: String) {
        val list = memoryCache[host] ?: return
        Log.d(TAG, "persistHost: Persisting ${list.size} cookies for host: $host")
        val now = System.currentTimeMillis()
        val toSave = list.filter { it.expiresAt > now }.map {
            SerializableCookie(
                name = it.name,
                value = it.value,
                expiresAt = it.expiresAt,
                domain = it.domain,
                path = it.path,
                secure = it.secure,
                httpOnly = it.httpOnly,
                hostOnly = it.hostOnly
            )
        }
        Log.d(TAG, "persistHost: Saving ${toSave.size} valid cookies to MMKV for host: $host")
        toSave.forEach { sc ->
            sc.toOkHttpCookieOrNull()?.let { Log.d(TAG, "persistHost: saving: ${formatCookie(it)}") }
        }
        mmkv.encode(host, gson.toJson(toSave))
        pendingJobs.remove(host)
    }

    fun clear() {
        Log.d(TAG, "clear: Clearing all cookies and cancelling jobs")
        pendingJobs.values.forEach { it.cancel() }
        pendingJobs.clear()
        memoryCache.forEach { (host, list) ->
            Log.d(TAG, "clear: clearing host=$host, cookies=${list.size}")
            list.forEach { Log.d(TAG, "clear: clearing cookie: ${formatCookie(it)}") }
        }
        memoryCache.clear()
        mmkv.clearAll()
        Log.d(TAG, "clear: Cleared MMKV and memory cache")
    }

    @Synchronized
    fun clearHosts(hosts: Set<String>) {
        hosts.forEach { host ->
            pendingJobs.remove(host)?.cancel()
            memoryCache.remove(host)
            mmkv.removeValueForKey(host)
        }
    }

    fun destroy() {
        scope.cancel()
    }

    private fun formatCookie(cookie: Cookie): String {
        return "name=${cookie.name}, value=${cookie.value}, domain=${cookie.domain}, " +
                "path=${cookie.path}, expiresAt=${cookie.expiresAt}, secure=${cookie.secure}, " +
                "httpOnly=${cookie.httpOnly}, hostOnly=${cookie.hostOnly}"
    }
}
