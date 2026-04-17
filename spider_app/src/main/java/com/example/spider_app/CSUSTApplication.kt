package com.example.spider_app

import android.app.Application
import com.dcelysia.csust_spider.core.MigratingKVStore

class CSUSTApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MigratingKVStore.initialize(this)

    }
}