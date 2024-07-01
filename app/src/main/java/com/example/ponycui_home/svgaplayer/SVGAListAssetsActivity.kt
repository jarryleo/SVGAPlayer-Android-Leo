package com.example.ponycui_home.svgaplayer

import android.app.Activity
import android.os.Bundle
import com.opensource.svgaplayer.SVGAImageView

class SVGAListAssetsActivity : Activity() {

    private val list = arrayOfNulls<SVGAImageView>(10)
    private val path = arrayOfNulls<String>(10)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        list[0] = findViewById(R.id.iv1)
        list[1] = findViewById(R.id.iv2)
        list[2] = findViewById(R.id.iv3)
        list[3] = findViewById(R.id.iv4)
        list[4] = findViewById(R.id.iv5)
        list[5] = findViewById(R.id.iv6)
        list[6] = findViewById(R.id.iv7)
        list[7] = findViewById(R.id.iv8)
        list[8] = findViewById(R.id.iv9)
        list[9] = findViewById(R.id.iv10)
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
        for (index in 0 until 10) {
            list[index]?.load(path[index])
        }
    }

}