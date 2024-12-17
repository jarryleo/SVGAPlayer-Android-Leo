package com.example.ponycui_home.svgaplayer

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
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

    private fun loadAnimation() {
        val name = "suofang.svga"
        animationView.load(name) {
            val nick = "阿斯顿撒感觉"
            setDynamicText(
                "nick",
                SVGATextEntity(nick)
            )
            setDynamicImage(
                "https://github.com/PonyCui/resources/blob/master/svga_replace_avatar.png?raw=true",
                "sender_avatar"
            )
        }
    }
}
