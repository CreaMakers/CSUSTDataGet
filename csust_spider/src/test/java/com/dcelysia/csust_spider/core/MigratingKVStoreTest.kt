package com.dcelysia.csust_spider.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MigratingKVStoreTest {

    private val testLogger = object : KVLogger {
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, throwable: Throwable?) = Unit
    }

    @Test
    fun getString_fastMiss_legacyHit_shouldBackfillFastKV() {
        val legacy = FakeLegacyKVBackend(mapOf("token" to "legacy-token"))
        val fast = FakeNewKVBackend()
        val kv = MigratingKV("test_store", legacy, fast, testLogger)

        val value = kv.getString("token")

        assertEquals("legacy-token", value)
        assertEquals("legacy-token", fast.map["token"])
        assertEquals(1, kv.migrationHitCount())
    }

    @Test
    fun putString_shouldWriteFastKVOnly() {
        val legacy = FakeLegacyKVBackend()
        val fast = FakeNewKVBackend()
        val kv = MigratingKV("test_store", legacy, fast, testLogger)

        kv.putString("student_id", "20260001")

        assertEquals("20260001", fast.map["student_id"])
        assertFalse(legacy.map.containsKey("student_id"))
    }

    @Test
    fun hasAnyKey_fastEmpty_legacyHasKeys_shouldMigrateLegacyStrings() {
        val legacy = FakeLegacyKVBackend(
            mutableMapOf(
                "a" to "1",
                "b" to "2"
            )
        )
        val fast = FakeNewKVBackend()
        val kv = MigratingKV("test_store", legacy, fast, testLogger)

        val hasAny = kv.hasAnyKey()

        assertTrue(hasAny)
        assertEquals("1", fast.map["a"])
        assertEquals("2", fast.map["b"])
        assertEquals(2, kv.migrationHitCount())
    }
}

private class FakeLegacyKVBackend(
    initial: Map<String, String> = emptyMap()
) : LegacyKVBackend {
    val map = initial.toMutableMap()

    override fun decodeString(key: String): String? = map[key]

    override fun containsKey(key: String): Boolean = map.containsKey(key)

    override fun allKeys(): Array<String> = map.keys.toTypedArray()

    override fun removeValueForKey(key: String) {
        map.remove(key)
    }

    override fun clearAll() {
        map.clear()
    }
}

private class FakeNewKVBackend : NewKVBackend {
    val map = mutableMapOf<String, String>()

    override fun containsKey(key: String): Boolean = map.containsKey(key)

    override fun getString(key: String, defaultValue: String?): String? = map[key] ?: defaultValue

    override fun putString(key: String, value: String): Boolean {
        map[key] = value
        return true
    }

    override fun getAll(): Map<*, *> = map

    override fun removeValueForKey(key: String) {
        map.remove(key)
    }

    override fun clearAll() {
        map.clear()
    }
}


