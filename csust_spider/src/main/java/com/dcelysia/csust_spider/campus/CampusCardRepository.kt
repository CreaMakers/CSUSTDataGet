package com.dcelysia.csust_spider.campus

class CampusCardRepository private constructor() {
    companion object {
        val instance by lazy { CampusCardRepository() }
        const val TAG = "CampusCardRepository"
    }

}