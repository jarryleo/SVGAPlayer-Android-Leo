package com.example.ponycui_home.svgaplayer

import android.app.Activity
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.support.annotation.RequiresApi
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.ViewGroup
import com.opensource.svgaplayer.SVGADynamicEntity
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.SVGAVideoEntity
import com.opensource.svgaplayer.loadAssets
import com.opensource.svgaplayer.utils.log.SVGALogger.setLogEnabled
import kotlin.math.roundToInt

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
        setContentView(animationView, ViewGroup.LayoutParams(400,400) )
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private fun loadAnimation() {
        val name = "custom_head_marquee.svga"
        animationView.loadAssets(name,
            useMemoryCache = true,
            isOriginal = true,
            loopCount = 0,
            dynamicCallback = { svgaVideoEntity: SVGAVideoEntity? ->
                val nick = "FC蓝染00260026"
//                val nick = "nick:test MARQUEE 测试跑马灯"
//                val nick = "خدمة متجر جوجل غير متوفرة"
                val svgaDynamicEntity = SVGADynamicEntity()
                val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
                textPaint.color = Color.WHITE
                textPaint.textSize = 10.dp.toFloat()
                val width = textPaint.measureText(nick).roundToInt()
//                val width = Int.MAX_VALUE
                val builder =
                    StaticLayout.Builder.obtain(
                        nick,
                        0,
                        nick.length,
                        textPaint,
                        width
                    )
                val build = builder
                    .setMaxLines(1)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setEllipsize(TextUtils.TruncateAt.MARQUEE)
//                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build()
                svgaDynamicEntity.setDynamicText(build, "NICK1")
                return@loadAssets svgaDynamicEntity
            }
        )
        Log.d("SVGA", "## name $name")
    }
}
