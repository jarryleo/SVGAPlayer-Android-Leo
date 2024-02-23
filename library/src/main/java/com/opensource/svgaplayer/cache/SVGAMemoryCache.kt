package com.opensource.svgaplayer.cache

import android.os.Build
import android.util.LruCache
import com.opensource.svgaplayer.SVGAConfig
import com.opensource.svgaplayer.SVGAVideoEntity
import java.lang.ref.WeakReference

/**
 * @Description SVGA内存缓存
 * @Author lyd
 * @Time 2023/10/8 10:15
 */
class SVGAMemoryCache(private val cacheLimit: Int = 3) {

    private val lruCache by lazy {
        object : LruCache<String, WeakReference<SVGAVideoEntity>>(cacheLimit) {
            override fun entryRemoved(
                evicted: Boolean,
                key: String?,
                oldValue: WeakReference<SVGAVideoEntity>?,
                newValue: WeakReference<SVGAVideoEntity>?
            ) {
                if (evicted) {
                    oldValue?.get()?.clear()
                    oldValue?.clear()
                }
            }
        }

    }

    fun getData(key: String): SVGAVideoEntity? {
        return lruCache.get(key)?.get()
    }

    fun putData(key: String, entity: SVGAVideoEntity) {
        lruCache.put(key, WeakReference(entity))
    }

    /**
     * 重新设置缓存大小，只有在Android 5.0及以上才有效
     * @param limit Int
     */
    fun resizeCache(limit: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            lruCache.resize(limit)
        }
    }

    companion object {

        val INSTANCE by lazy { SVGAMemoryCache(limitCount) }

        /** 内存缓存个数 */
        var limitCount = 3
            set(value) {
                field = value
                INSTANCE.resizeCache(value)
            }

        /**
         * 拼接缓存Key所需要的字段
         */
        fun createKey(path: String, config: SVGAConfig): String {
            return "key:{path = $path frameWidth = ${config.frameWidth} frameHeight = ${config.frameHeight}}".let {
                SVGACache.buildCacheKey(it)
            }
        }
    }
}
