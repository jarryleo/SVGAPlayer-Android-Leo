package com.opensource.svgaplayer.cache

import com.opensource.svgaplayer.SVGAConfig
import com.opensource.svgaplayer.SVGAVideoEntity
import java.lang.ref.WeakReference

/**
 * @Description SVGA内存缓存
 * @Author lyd
 * @Time 2023/10/8 10:15
 */
class SVGAMemoryCache {

    private val cacheData by lazy {
        LinkedHashMap<String, WeakReference<SVGAVideoEntity>>(limitCount)
    }

    fun getData(key: String): SVGAVideoEntity? {
        return synchronized(this) {
            cacheData[key]?.get()
        }
    }

    fun putData(key: String, entity: SVGAVideoEntity) {
        synchronized(this) {
            cleanEmpty()
            cacheData[key] = WeakReference(entity)
            cleanLimit()
        }
    }

    /**
     * 清除没有被引用的数据
     */
    private fun cleanEmpty() {
        cacheData.firstNull()?.apply {
            cacheData.remove(this)?.clear()
            cleanEmpty()
        }
    }

    /**
     * 清除超出限制的数据
     */
    private fun cleanLimit() {
        if (cacheData.size > limitCount) {
            val key = this.cacheData.entries.iterator().next().key
            cacheData.remove(key)?.clear()
            cleanLimit()
        }
    }

    /**
     * 寻找引用已经被回收的数据
     */
    private inline fun <K, V> Map<out K, WeakReference<V>>.firstNull(): K? {
        for (element in this) {
            if (element.value.get() == null) {
                return element.key
            }
        }
        return null
    }

    companion object {

        val INSTANCE by lazy { SVGAMemoryCache() }

        /** 内存缓存个数 */
        var limitCount = 3


        /**
         * 拼接缓存Key所需要的字段
         */
        fun crateKey(path: String, config: SVGAConfig): String {
            return "key:{path = $path frameWidth = ${config.frameWidth} frameHeight = ${config.frameHeight}}".let {
                SVGACache.buildCacheKey(it)
            }
        }
    }
}
