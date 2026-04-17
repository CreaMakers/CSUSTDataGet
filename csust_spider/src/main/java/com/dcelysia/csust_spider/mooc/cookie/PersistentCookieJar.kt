package com.dcelysia.csust_spider.mooc.cookie

import android.content.Context
import android.util.Log
import com.dcelysia.csust_spider.core.MigratingKVStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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

        fun initialize(context: Context) {
            MigratingKVStore.initialize(context)
        }
    }

    private val kvStore by lazy { MigratingKVStore.get(MMKV_ID) }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        Log.d(TAG, "saveFromResponse: Saving cookies for host: $host")
        val now = System.currentTimeMillis()

        Log.d(TAG, "saveFromResponse: Incoming cookies count=${cookies.size} for host=$host")
        cookies.forEach { Log.d(TAG, "saveFromResponse: incoming: ${formatCookie(it)}") }

        memoryCache.compute(host) { _, existing ->
            val base = existing?.filter { it.expiresAt > now }?.toMutableList()
                ?: run {
                    val json = kvStore.getString(host)
                    if (json != null) {
                        parseCookiesFromJson(host, json, now).also { loaded ->
                            Log.d(TAG, "saveFromResponse: Loaded ${loaded.size} cookies from KV for host: $host")
                            loaded.forEach { Log.d(TAG, "saveFromResponse: loaded from KV: ${formatCookie(it)}") }
                        }.toMutableList()
                    } else {
                        Log.d(TAG, "saveFromResponse: No cookies found in KV for host: $host")
                        mutableListOf()
                    }
                }

            cookies.forEach { newCookie ->
                base.removeAll {
                    it.name == newCookie.name && it.domain == newCookie.domain && it.path == newCookie.path
                }
                if (newCookie.expiresAt > now) {
                    base.add(newCookie)
                }
            }

            val result = base.filter { it.expiresAt > now }
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
            val json = kvStore.getString(host)
            Log.d(TAG, "loadForRequest: Reading from KV for host: $host, json: $json")
            if (json != null) {
                parseCookiesFromJson(host, json, now).also { cookies ->
                    Log.d(TAG, "loadForRequest: Loaded ${cookies.size} cookies from KV for host: $host")
                    cookies.forEach { Log.d(TAG, "loadForRequest: loaded: ${formatCookie(it)}") }
                }
            } else {
                Log.d(TAG, "loadForRequest: No cookies found in KV for host: $host")
                emptyList()
            }
        }

        val validList = list.filter { it.expiresAt > now }
        Log.d(TAG, "loadForRequest: Returning ${validList.size} valid cookies for host: $host")
        validList.forEach { Log.d(TAG, "loadForRequest: returning: ${formatCookie(it)}") }
        return validList
    }

    /**
     * 从 JSON 解析 cookie，跳过无效条目（字段缺失/null/已过期），不会因单条 cookie 损坏导致崩溃
     */
    private fun parseCookiesFromJson(host: String, json: String, now: Long): List<Cookie> {
        return runCatching {
            val type = object : TypeToken<List<SerializableCookie>>() {}.type
            val serializableCookies: List<SerializableCookie>? = gson.fromJson(json, type)
            if (serializableCookies.isNullOrEmpty()) {
                kvStore.removeValueForKey(host)
                return emptyList()
            }
            serializableCookies.mapNotNull { sc ->
                runCatching { sc.toOkHttpCookieOrNull() }
                    .onFailure { Log.w(TAG, "parseCookiesFromJson: skip malformed cookie for host=$host", it) }
                    .getOrNull()
            }.filter { it.expiresAt > now }
        }.onFailure {
            Log.e(TAG, "parseCookiesFromJson: failed to parse cookies for host=$host, clearing cache", it)
            kvStore.removeValueForKey(host)
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
        Log.d(TAG, "persistHost: Saving ${toSave.size} valid cookies to KV for host: $host")
        toSave.forEach { sc ->
            sc.toOkHttpCookieOrNull()?.let { Log.d(TAG, "persistHost: saving: ${formatCookie(it)}") }
        }
        kvStore.putString(host, gson.toJson(toSave))
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
        kvStore.clearAll()
        Log.d(TAG, "clear: Cleared KV store and memory cache")
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