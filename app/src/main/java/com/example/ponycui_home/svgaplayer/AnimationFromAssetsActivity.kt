package com.example.ponycui_home.svgaplayer

import android.app.Activity
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.support.annotation.RequiresApi
import android.text.StaticLayout
import android.text.TextPaint
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
        animationView.setOnClickListener {
            animationView.stepToFrame(currentIndex++, false)
        }
        setLogEnabled(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            loadAnimation()
        }
        setContentView(animationView)
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private fun loadAnimation() {
        val name = "crazy_gift_tips.svga"
        animationView.loadAssets(name,
            useMemoryCache = true,
            isOriginal = true,
            loopCount = 1,
            dynamicCallback = { svgaVideoEntity: SVGAVideoEntity? ->
                val nick = "nick:test"
                val svgaDynamicEntity = SVGADynamicEntity()
                val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
                textPaint.color = Color.WHITE
                textPaint.textSize = 20.dp.toFloat()
                val width = textPaint.measureText(nick, 0, nick.length)
                val builder =
                    StaticLayout.Builder.obtain(
                        nick,
                        0,
                        nick.length,
                        textPaint,
                        width.toInt()
                    )
                val build = builder.build()
                svgaDynamicEntity.setDynamicText(build, "img_15")

                val w = 20.dp
                val h = 5.dp
                val bitmap = NumBitmapCreator.createNumBitmap(this, 99, w, h)
                if (bitmap != null) {
                    svgaDynamicEntity.setDynamicImage(bitmap, "img_10")
                }
                svgaDynamicEntity.setDynamicImage("https://github.com/PonyCui/resources/blob/master/svga_replace_avatar.png?raw=true", "img_60")
                return@loadAssets svgaDynamicEntity
            }
        )
        Log.d("SVGA", "## name $name")
    }
}
