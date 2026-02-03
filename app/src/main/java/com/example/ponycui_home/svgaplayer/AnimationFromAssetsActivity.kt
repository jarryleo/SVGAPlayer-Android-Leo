package com.example.ponycui_home.svgaplayer

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.entities.SVGATextEntity
import com.opensource.svgaplayer.utils.CircleCropTransFormation
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
        val name = "haki_test.svga"
        animationView.load(name) {
            setDynamicImage(
                "https://res.hakiapp.com/4d084d73ddc771f69ee9b5f00c32141d?imageslim|imageView2/1/w/100/h/100/format/png|roundPic/radius/!50p",
                "avatar-1",
                CircleCropTransFormation()
            )
            setDynamicImage(
                "https://res.hakiapp.com/33acc6c6bb1f20af3725b3ab95ff0cac?imageslim|imageView2/1/w/100/h/100/format/png|roundPic/radius/!50p",
                "avatar-2",
                CircleCropTransFormation()
            )
        }
    }
}
