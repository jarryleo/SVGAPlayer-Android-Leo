package com.example.ponycui_home.svgaplayer

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.support.annotation.RequiresApi
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
        val name = "room_big_gift_level3.svga"
        val textSize = 14.dp.toFloat()
        animationView.load(name) {
            val nick1 = "خدمة متجر جوجل غير متوفرة"
            val nick2 = "FC0005"
            setDynamicText(
                "name1",
                SVGATextEntity(
                    nick1,
                    textSize,
                )
            )
            setDynamicText(
                "name2",
                SVGATextEntity(
                    nick2,
                    textSize,
                )
            )
        }
    }
}
