package com.dcelysia.csust_spider.core

import android.content.Context
import com.tencent.mmkv.MMKV
import io.fastkv.FastKV
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * MMKV -> FastKV migration store:
 * - write to FastKV
 * - read FastKV first, fallback to MMKV and backfill FastKV
 */
object MigratingKVStore {
    private const val TAG = "MigratingKVStore"
    private val stores = ConcurrentHashMap<String, MigratingKV>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    internal var logger: KVLogger = AndroidKVLogger

    fun initialize(context: Context) {
        appContext = context.applicationContext
        runCatching { MMKV.initialize(context.applicationContext) }
            .onFailure { logger.w(TAG, "MMKV.initialize failed", it) }
    }

    fun get(storeId: String): MigratingKV {
        val context = checkNotNull(appContext) {
            "MigratingKVStore.initialize(context) must be called before get($storeId)"
        }
        return stores.getOrPut(storeId) {
            MigratingKV(
                storeId = storeId,
                legacyBackend = MMKVBackend(createMMKV(storeId)),
                fastBackend = FastKVBackend(createFastKV(context, storeId)),
                logger = logger
            )
        }
    }

    private fun createMMKV(storeId: String): MMKV {
        return runCatching { MMKV.mmkvWithID(storeId, MMKV.MULTI_PROCESS_MODE) }
            .getOrElse {
                logger.w(TAG, "mmkvWithID failed for $storeId, fallback to default", it)
                MMKV.defaultMMKV()
            }
    }

    private fun createFastKV(context: Context, storeId: String): FastKV {
        return FastKV.Builder(context, storeId).build()
    }
}

internal interface KVLogger {
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
}

private object AndroidKVLogger : KVLogger {
    private val logClass by lazy {
        runCatching { Class.forName("android.util.Log") }.getOrNull()
    }

    override fun i(tag: String, message: String) {
        runCatching {
            val clazz = logClass ?: return
            val method = clazz.getMethod("i", String::class.java, String::class.java)
            method.invoke(null, tag, message)
        }
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        runCatching {
            val clazz = logClass ?: return
            if (throwable == null) {
                val method = clazz.getMethod("w", String::class.java, String::class.java)
                method.invoke(null, tag, message)
            } else {
                val method = clazz.getMethod(
                    "w",
                    String::class.java,
                    String::class.java,
                    Throwable::class.java
                )
                method.invoke(null, tag, message, throwable)
            }
        }
    }
}

internal interface LegacyKVBackend {
    fun decodeString(key: String): String?
    fun containsKey(key: String): Boolean
    fun allKeys(): Array<String>
    fun removeValueForKey(key: String)
    fun clearAll()
}

internal interface NewKVBackend {
    fun containsKey(key: String): Boolean
    fun getString(key: String, defaultValue: String?): String?
    fun putString(key: String, value: String): Boolean
    fun getAll(): Map<*, *>
    fun removeValueForKey(key: String)
    fun clearAll()
}

private class MMKVBackend(private val mmkv: MMKV) : LegacyKVBackend {
    override fun decodeString(key: String): String? = mmkv.decodeString(key)
    override fun containsKey(key: String): Boolean = mmkv.containsKey(key)
    override fun allKeys(): Array<String> = mmkv.allKeys()?.map { it.toString() }?.toTypedArray() ?: emptyArray()
    override fun removeValueForKey(key: String) = mmkv.removeValueForKey(key)
    override fun clearAll() = mmkv.clearAll()
}

private class FastKVBackend(private val fastKV: FastKV) : NewKVBackend {
    override fun containsKey(key: String): Boolean = fastKV.contains(key)

    override fun getString(key: String, defaultValue: String?): String? {
        return runCatching {
            val method = FastKV::class.java.getMethod("getString", String::class.java, String::class.java)
            method.invoke(fastKV, key, defaultValue) as? String
        }.recoverCatching {
            val method = FastKV::class.java.getMethod("getString", String::class.java)
            method.invoke(fastKV, key) as? String
        }.getOrNull() ?: defaultValue
    }

    override fun putString(key: String, value: String): Boolean {
        return runCatching {
            val method = FastKV::class.java.getMethod("putString", String::class.java, String::class.java)
            method.invoke(fastKV, key, value)
            true
        }.getOrDefault(false)
    }

    override fun getAll(): Map<*, *> {
        val all = runCatching {
            val method = FastKV::class.java.getMethod("getAll")
            method.invoke(fastKV)
        }.getOrNull()
        return all as? Map<*, *> ?: emptyMap<String, Any>()
    }

    override fun removeValueForKey(key: String) {
        runCatching {
            val method = FastKV::class.java.getMethod("remove", String::class.java)
            method.invoke(fastKV, key)
        }
    }

    override fun clearAll() {
        runCatching {
            val method = FastKV::class.java.getMethod("clear")
            method.invoke(fastKV)
        }
    }
}

class MigratingKV internal constructor(
    private val storeId: String,
    private val legacyBackend: LegacyKVBackend,
    private val fastBackend: NewKVBackend,
    private val logger: KVLogger = AndroidKVLogger
) {
    private val migrationHitCounter = AtomicInteger(0)
    private val tag = "MigratingKV"

    fun getString(key: String, defaultValue: String? = null): String? {
        if (fastBackend.containsKey(key)) {
            return fastBackend.getString(key, defaultValue)
        }

        val legacyValue = legacyBackend.decodeString(key)
        if (legacyValue != null) {
            recordMigrationHit(key, "getString")
            writeFastOnly(key, legacyValue)
            return legacyValue
        }

        return defaultValue
    }

    fun putString(key: String, value: String) {
        writeFastOnly(key, value)
    }

    fun containsKey(key: String): Boolean {
        if (fastBackend.containsKey(key)) return true

        val legacyExists = legacyBackend.containsKey(key)
        if (legacyExists) {
            recordMigrationHit(key, "containsKey")
            legacyBackend.decodeString(key)?.let { writeFastOnly(key, it) }
        }
        return legacyExists
    }

    fun hasAnyKey(): Boolean {
        if (fastBackend.getAll().isNotEmpty()) return true

        val legacyKeys = legacyBackend.allKeys()
        if (legacyKeys.isEmpty()) return false

        legacyKeys.forEach { key ->
            legacyBackend.decodeString(key)?.let {
                recordMigrationHit(key, "hasAnyKey")
                writeFastOnly(key, it)
            }
        }
        return true
    }

    fun removeValueForKey(key: String) {
        fastBackend.removeValueForKey(key)
        legacyBackend.removeValueForKey(key)
    }

    fun clearAll() {
        fastBackend.clearAll()
        legacyBackend.clearAll()
    }

    @Suppress("unused")
    internal fun migrationHitCount(): Int = migrationHitCounter.get()

    private fun writeFastOnly(key: String, value: String) {
        if (!fastBackend.putString(key, value)) {
            logger.w(tag, "FastKV put failed, store=$storeId, key=$key")
        }
    }

    private fun recordMigrationHit(key: String, from: String) {
        val count = migrationHitCounter.incrementAndGet()
        logger.i(tag, "Migration hit store=$storeId, key=$key, from=$from, count=$count")
    }
}

