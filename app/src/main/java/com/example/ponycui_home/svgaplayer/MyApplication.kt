package com.example.ponycui_home.svgaplayer

import android.app.Application
import android.net.http.HttpResponseCache
import java.io.File

class MyApplication: Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            val cacheDir = File(cacheDir, "http")
            HttpResponseCache.install(cacheDir, (256 * 1024 * 1024).toLong())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

}