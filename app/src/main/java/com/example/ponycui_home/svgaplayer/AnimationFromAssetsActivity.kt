package com.example.ponycui_home.svgaplayer

import android.app.Activity
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.support.annotation.RequiresApi
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import com.opensource.svgaplayer.SVGADynamicEntity
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.SVGAVideoEntity
import com.opensource.svgaplayer.loadAssets
import com.opensource.svgaplayer.utils.log.SVGALogger.setLogEnabled

class AnimationFromAssetsActivity : Activity() {
    private var currentIndex: Int = 0
    private lateinit var animationView: SVGAImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        animationView = SVGAImageView(this)
        animationView.setBackgroundColor(Color.BLACK)
        /*animationView.setOnClickListener {
            animationView.stepToFrame(currentIndex++, false)
        }*/
        setLogEnabled(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            loadAnimation()
        }
        setContentView(animationView)
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private fun loadAnimation() {
        val name = "custom_head_marquee.svga"
        animationView.loadAssets(name,
            useMemoryCache = true,
            isOriginal = true,
            loopCount = 0,
            dynamicCallback = { svgaVideoEntity: SVGAVideoEntity? ->
                val nick = "nick:test MARQUEE 测试跑马灯"
                val svgaDynamicEntity = SVGADynamicEntity()
                val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
                textPaint.color = Color.WHITE
                textPaint.textSize = 12.dp.toFloat()
                val width = textPaint.measureText(nick, 0, nick.length)
                val builder =
                    StaticLayout.Builder.obtain(
                        nick,
                        0,
                        nick.length,
                        textPaint,
                        width.toInt()
                    )
                val build = builder
                    .setMaxLines(1)
                    .setEllipsize(TextUtils.TruncateAt.MARQUEE)
                    .build()
                svgaDynamicEntity.setDynamicText(build, "NICK1")
                return@loadAssets svgaDynamicEntity
            }
        )
        Log.d("SVGA", "## name $name")
    }
}
