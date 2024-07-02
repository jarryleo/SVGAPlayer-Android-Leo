package com.example.ponycui_home.svgaplayer

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.support.annotation.RequiresApi
import android.util.Log
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.entities.SVGATextEntity
import com.opensource.svgaplayer.utils.log.SVGALogger.setLogEnabled

class AnimationFromAssetsActivity : Activity() {
    private lateinit var animationView: SVGAImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        animationView = SVGAImageView(this)
        animationView.setBackgroundColor(Color.BLACK)
        setContentView(animationView)
        /*animationView.setOnClickListener {
            animationView.stepToFrame(currentIndex++, false)
        }*/
        setLogEnabled(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            loadAnimation()
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private fun loadAnimation() {
        val name = "custom_head_marquee.svga"
        animationView.load(name) {
            val nick = "nick:test MARQUEE 测试跑马灯"
//            val nick = "خدمة متجر جوجل غير متوفرة"
            setDynamicText(
                "NICK1",
                SVGATextEntity(nick)
            )
        }
        Log.d("SVGA", "## name $name")
    }
}
