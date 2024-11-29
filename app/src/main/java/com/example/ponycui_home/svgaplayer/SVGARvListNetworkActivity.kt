package com.example.ponycui_home.svgaplayer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingData
import androidx.recyclerview.widget.RecyclerView
import cn.leo.paging_ktx.ext.buildAdapter
import cn.leo.paging_ktx.tools.StringData
import com.example.ponycui_home.svgaplayer.holder.SvgaHolder

class SVGARvListNetworkActivity : AppCompatActivity() {


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rv_list)
        val recyclerView = findViewById<RecyclerView>(R.id.rvList)
        val list = mutableListOf<StringData>()
        val url = "https://pic.vchat-onlie.com/FmkgjpTSnjA_QaOxu0DuQ2e5pvXg?imageslim"
        repeat(100) {
            list.add(StringData(url))
        }
        recyclerView.buildAdapter {
            addHolder(SvgaHolder())
            setData(lifecycleScope, PagingData.from(list))
        }
    }
}