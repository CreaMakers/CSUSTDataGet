package com.dcelysia.csust_spider.core.cookie

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.filter
import kotlin.collections.toMutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PersistentCookiesStorage private constructor(private val isDebug: Boolean = true) :
        CookiesStorage {
    companion object {
        val instance by lazy { PersistentCookiesStorage(isDebug = false) }
        private const val TAG = "PersistentCookiesStorage"
    }

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val memoryCache = ConcurrentHashMap<String, List<Cookie>>()
    private val pendingJobs = ConcurrentHashMap<String, Job>()
    private val saveDelayMs = 500L

    private val mmkv = MMKV.mmkvWithID("ktor_cookie_jar", MMKV.MULTI_PROCESS_MODE)

    override suspend fun get(requestUrl: Url): List<Cookie> {
        val host = requestUrl.host
        val now = GMTDate().timestamp
        logD("get() host=$host now=$now")

        val list =
                memoryCache
                        .computeIfAbsent(host) {
                            logD("cache miss for host=$host, loading from disk")
                            loadFromDisk(host)
                        }
                        .also { logD("cache size for host=$host = ${it.size}") }

        val filtered = list.filter { it.expires == null || it.expires!!.timestamp > now }
        logD("get() returning ${filtered.size} cookies for host=$host after expiry filter")
        return filtered
    }

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        val host = requestUrl.host
        val now = GMTDate().timestamp
        logD(
                "addCookie() host=$host cookie=${cookie.name} domain=${cookie.domain} path=${cookie.path} expires=${cookie.expires?.timestamp}"
        )

        memoryCache.compute(host) { _, existing ->
            val base =
                    existing
                            ?.filter { it.expires == null || it.expires!!.timestamp > now }
                            ?.toMutableList()
                            ?: run {
                                val loaded = loadFromDisk(host).toMutableList()
                                logD(
                                        "initialized base from disk for host=$host size=${loaded.size}"
                                )
                                loaded
                            }

            val beforeRemove = base.size
            base.removeAll {
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
            }
            val afterRemove = base.size
            if (beforeRemove != afterRemove) {
                logD("removed ${beforeRemove - afterRemove} conflicting cookies for host=$host")
            }

            if (cookie.expires == null || cookie.expires!!.timestamp > now) {
                base.add(cookie)
                logD("added cookie ${cookie.name} for host=$host, newSize=${base.size}")
            } else {
                logD("cookie ${cookie.name} expired, not added for host=$host")
            }

            base
        }

        scheduleSave(host)
    }

    fun clear() {
        logD("clear() clearing memory cache and disk cookies")
        memoryCache.clear()
        pendingJobs.values.forEach { it.cancel() }
        pendingJobs.clear()
        mmkv.clearAll()
    }

    override fun close() {
        logD("close() cancelling scope and pending jobs")
        scope.cancel()
    }

    // ---------------- private ----------------

    private fun loadFromDisk(host: String): List<Cookie> {
        val json = mmkv.decodeString(host)
        if (json.isNullOrEmpty()) {
            logD("loadFromDisk() host=$host no data on disk")
            return emptyList()
        }
        logD("loadFromDisk() host=$host jsonLength=${json.length}")

        return try {
            val type = object : TypeToken<List<SerializableKtorCookie>>() {}.type
            val list: List<SerializableKtorCookie> = gson.fromJson(json, type)
            val res = list.map { it.toKtorCookie() }
            logD("loadFromDisk() host=$host parsed ${res.size} cookies")
            res
        } catch (t: Throwable) {
            logW("loadFromDisk() host=$host parse error: ${t.message}", t)
            emptyList()
        }
    }

    private fun scheduleSave(host: String) {
        pendingJobs[host]?.let {
            logD("scheduleSave() host=$host cancelling previous pending job")
            it.cancel()
        }
        logD("scheduleSave() host=$host scheduling save after ${saveDelayMs}ms")
        pendingJobs[host] =
                scope.launch {
                    delay(saveDelayMs)
                    persist(host)
                }
    }

    private fun persist(host: String) {
        val now = GMTDate().timestamp
        val list = memoryCache[host]
        if (list == null) {
            logD("persist() host=$host nothing to save (no cache entry)")
            pendingJobs.remove(host)
            return
        }

        val toSave =
                list.filter { it.expires == null || it.expires!!.timestamp > now }.map {
                    it.toSerializable()
                }

        try {
            val json = gson.toJson(toSave)
            mmkv.encode(host, json)
            logD("persist() host=$host saved ${toSave.size} cookies jsonLength=${json.length}")
        } catch (t: Throwable) {
            logW("persist() host=$host save error: ${t.message}", t)
        } finally {
            pendingJobs.remove(host)
        }
    }

    private fun SerializableKtorCookie.toKtorCookie(): Cookie {
        val cookie =
                Cookie(
                        name = name,
                        value = value,
                        domain = domain,
                        path = path,
                        secure = secure,
                        httpOnly = httpOnly,
                        expires = expiresAt?.let { GMTDate(it) }
                )
        logD("toKtorCookie() name=$name domain=$domain path=$path expiresAt=$expiresAt")
        return cookie
    }

    private fun Cookie.toSerializable(): SerializableKtorCookie {
        val s =
                SerializableKtorCookie(
                        name = name,
                        value = value,
                        expiresAt = expires?.timestamp,
                        domain = domain ?: "",
                        path = path ?: "/",
                        secure = secure,
                        httpOnly = httpOnly
                )
        logD(
                "toSerializable() name=${name} domain=${s.domain} path=${s.path} expiresAt=${s.expiresAt}"
        )
        return s
    }
    private fun logD(msg: String) {
        if (isDebug) {
            Log.d(TAG, msg)
        }
    }

    private fun logW(msg: String, t: Throwable? = null) {
        if (isDebug) {
            Log.w(TAG, msg, t)
        }
    }
}
