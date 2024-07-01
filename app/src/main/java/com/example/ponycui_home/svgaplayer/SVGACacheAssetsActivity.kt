package com.example.ponycui_home.svgaplayer

import android.app.Activity
import android.os.Bundle
import com.opensource.svgaplayer.SVGAImageView

class SVGACacheAssetsActivity : Activity() {

    private val list = arrayOfNulls<SVGAImageView>(3)
    private val path = arrayOfNulls<String>(10)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cache)
        list[0] = findViewById(R.id.iv1)
        list[1] = findViewById(R.id.iv2)
        list[2] = findViewById(R.id.iv3)
        path[0] = "golds1.svga"
        path[1] = "golds2.svga"
        path[2] = "golds3.svga"
        path[3] = "golds4.svga"
        path[4] = "golds5.svga"
        path[5] = "times1.svga"
        path[6] = "times2.svga"
        path[7] = "times3.svga"
        path[8] = "times4.svga"
        path[9] = "times5.svga"
        for (index in 0 until 3) {
//            list[index].loadAssets(path[(System.currentTimeMillis().toInt()%10)])
            list[index]?.load(path[index])
        }
    }
}