package com.opensource.svgaplayer.cache

import com.opensource.svgaplayer.BuildConfig
import com.opensource.svgaplayer.SVGAConfig
import java.net.URL

/**
 * @Description SVGA内存缓存
 * @Author lyd
 * @Time 2023/10/8 10:15
 */
class SVGAMemoryCache {

    fun createKey(url: URL, config: SVGAConfig) {

    }

}

data class MemoryCacheKey(val url: URL, val frameWidth: Int = 0, val frameHeight: Int = 0) {

    /**
     * 拼接缓存Key所需要的字段
     */
    fun toKey(): String {
        return "key:{url = $url frameWidth = $frameWidth frameHeight = $frameHeight}".let {
            SVGACache.buildCacheKey(it)
        }
    }

}

interface IMemoryCache {
    fun setCacheKey(key: MemoryCacheKey)

    fun getCacheKey(): MemoryCacheKey?
}