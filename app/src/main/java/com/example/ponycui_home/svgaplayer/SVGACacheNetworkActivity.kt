package com.example.ponycui_home.svgaplayer

import android.app.Activity
import android.os.Bundle
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.loadUrl

class SVGACacheNetworkActivity : Activity() {

    private val list = arrayOfNulls<SVGAImageView>(3)
    private val path = arrayOfNulls<String>(10)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cache)
        list[0] = findViewById(R.id.iv1)
        list[1] = findViewById(R.id.iv2)
        list[2] = findViewById(R.id.iv3)
        path[0] = "https://res.fancyliveapp.com/headwear/1694771685260?imageslim"
        path[1] = "https://res.fancyliveapp.com/headwear/1695095126632?imageslim"
        path[2] = "https://res.fancyliveapp.com/headwear/1695095193469?imageslim"
        path[3] = "https://res.fancyliveapp.com/headwear/1695095244663?imageslim"
        path[4] = "https://res.fancyliveapp.com/headwear/1695095289004?imageslim"
        path[5] = "https://res.fancyliveapp.com/headwear/1695095359896?imageslim"
        path[6] = "https://res.fancyliveapp.com/headwear/1695095408915?imageslim"
        path[7] = "https://res.fancyliveapp.com/headwear/1695095462029?imageslim"
        path[8] = "https://res.fancyliveapp.com/headwear/1695095548666?imageslim"
        path[9] = "https://res.fancyliveapp.com/headwear/1695095567925?imageslim"
        for (index in 0 until 3) {
            list[index]?.loadUrl(path[index] ?: "")
        }

    }

}