package com.example.ponycui_home.svgaplayer.holder

import cn.leo.paging_ktx.adapter.ItemHelper
import cn.leo.paging_ktx.simple.SimpleHolder
import cn.leo.paging_ktx.tools.StringData
import com.example.ponycui_home.svgaplayer.R
import com.opensource.svgaplayer.SVGAImageView

/**
 * @Author     :Leo
 * Date        :2024/11/29
 * Description :
 */
class SvgaHolder : SimpleHolder<StringData>(R.layout.item_svga) {
    override fun bindItem(
        item: ItemHelper,
        data: StringData,
        payloads: MutableList<Any>?
    ) {
        item.getViewById<SVGAImageView>(R.id.svga){
            it.load(data.string.toString())
        }
    }
}