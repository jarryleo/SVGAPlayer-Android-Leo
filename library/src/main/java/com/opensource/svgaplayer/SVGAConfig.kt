package com.opensource.svgaplayer

/**
 * @Description svgA加载配置
 * @Author lyd
 * @Time 2023/9/20 10:24
 */

data class SVGAConfig(
    val frameWidth: Int = 0,      //加载到内存中的宽度，0：使用原图
    val frameHeight: Int = 0,     //加载到内存中的高度，0：使用原图
    val isCacheToMemory: Boolean = false //是否缓存到内存中，默认不缓存
)