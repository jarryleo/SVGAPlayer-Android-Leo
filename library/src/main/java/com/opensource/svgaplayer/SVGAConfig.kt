package com.opensource.svgaplayer

/**
 * @Description svgA加载配置
 * @Author lyd
 * @Time 2023/9/20 10:24
 */

data class SVGAConfig(
    /**
     * 循环次数，若小于等于0为无限循环
     */
    val loopCount: Int = -1,
    /**
     * 加载到内存中的宽度，0：使用原图
     */
    val frameWidth: Int = 0,
    /**
     * 加载到内存中的高度，0：使用原图
     */
    val frameHeight: Int = 0,
    /**
     * 如果是一次性的页面，建议不需要缓存到内存，有磁盘缓存即可
     */
    val isCacheToMemory: Boolean = false, //是否缓存到内存中，默认不缓存。
    /**
     * 是否加载原图，默认按View尺寸加载，节约内存
     */
    val isOriginal: Boolean = false,
)