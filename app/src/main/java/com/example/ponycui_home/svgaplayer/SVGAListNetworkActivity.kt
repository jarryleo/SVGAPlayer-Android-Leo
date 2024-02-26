package com.example.ponycui_home.svgaplayer

import android.app.Activity
import android.os.Bundle
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.SVGASoundManager
import com.opensource.svgaplayer.loadUrl

class SVGAListNetworkActivity : Activity() {

    private val list = arrayOfNulls<SVGAImageView>(10)
    private val path = arrayOfNulls<String>(10)

    //    金币榜第1
//https://res.fancyliveapp.com/headwear/1694771685260?imageslim
//金币榜第2
//https://res.fancyliveapp.com/headwear/1695095126632?imageslim
//金币榜第3
//https://res.fancyliveapp.com/headwear/1695095193469?imageslim
//金币榜第4
//https://res.fancyliveapp.com/headwear/1695095244663?imageslim
//金币榜第11
//https://res.fancyliveapp.com/headwear/1695095289004?imageslim
//
//
//次数榜第1
//https://res.fancyliveapp.com/headwear/1695095359896?imageslim
//次数榜第2
//https://res.fancyliveapp.com/headwear/1695095408915?imageslim
//次数榜第3
//https://res.fancyliveapp.com/headwear/1695095462029?imageslim
//次数榜第4
//https://res.fancyliveapp.com/headwear/1695095548666?imageslim
//次数榜第11
//https://res.fancyliveapp.com/headwear/1695095567925?imageslim
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
        SVGASoundManager.init()
        for (index in 0 until 10) {
            list[index]?.loadUrl(path[index] ?: "")
        }
    }
}