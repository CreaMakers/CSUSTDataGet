package com.dcelysia.csust_spider.core

object MMKVUtil {
    fun hasKey(kv: MigratingKV, key: String): Boolean = kv.containsKey(key)

    // 判断指定 key 对应的 String 是否有实际内容（非空白）
    fun hasNonEmptyString(kv: MigratingKV, key: String): Boolean =
        kv.getString(key)?.isNotBlank() == true

    // 判断 MMKV 内是否有任意键值（无键则为空）
    fun hasAnyKey(kv: MigratingKV): Boolean = kv.hasAnyKey()
}