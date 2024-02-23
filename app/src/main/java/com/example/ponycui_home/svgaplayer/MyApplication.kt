package com.example.ponycui_home.svgaplayer

import android.app.Application
import com.opensource.svgaplayer.SVGAManager

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        SVGAManager.init(this)
    }

}