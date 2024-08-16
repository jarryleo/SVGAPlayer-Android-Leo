package com.opensource.svgaplayer

import android.content.Context
import android.net.http.HttpResponseCache
import com.opensource.svgaplayer.cache.SVGABitmapCache
import com.opensource.svgaplayer.cache.SVGAFileCache
import com.opensource.svgaplayer.cache.SVGAMemoryCache
import com.opensource.svgaplayer.url.UrlDecoder
import com.opensource.svgaplayer.url.UrlDecoderManager
import com.opensource.svgaplayer.utils.log.ILogger
import com.opensource.svgaplayer.utils.log.SVGALogger
import java.io.File
import java.util.concurrent.ThreadPoolExecutor

/**
 * @Author     :Leo
 * Date        :2024/2/23
 * Description : SVGAManager 初始化入口
 */
object SVGAManager {
    @JvmStatic
    fun init(
        context: Context,
        memoryCacheCount: Int = 16,
        httpCacheSize: Long = (256 * 1024 * 1024).toLong(),
        logEnabled: Boolean = BuildConfig.DEBUG,
        loggerProxy: ILogger? = null,
        urlDecoder: UrlDecoder? = null,
        threadPoolExecutor: ThreadPoolExecutor? = null
    ) {
        try {
            val installed = HttpResponseCache.getInstalled()
            if (installed == null) {
                val cacheDir = File(context.cacheDir, "http")
                HttpResponseCache.install(cacheDir, httpCacheSize)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // 配置cache
        SVGAFileCache.init(context)
        //修改内存缓存大小
        SVGAMemoryCache.limitCount = memoryCacheCount
        SVGABitmapCache.limitCount = memoryCacheCount
        //自定义线程池
        threadPoolExecutor?.let {
            SVGAParser.setThreadPoolExecutor(it)
        }
        //初始化url解码器
        urlDecoder?.let {
            UrlDecoderManager.setUrlDecoder(it)
        }
        //初始化
        SVGAParser.init(context)
        // log
        SVGALogger.setLogEnabled(logEnabled)
        //日志代理
        loggerProxy?.let { logger ->
            SVGALogger.injectSVGALoggerImp(logger)
        }
    }

    /**
     * 低内存时释放缓存
     */
    @JvmStatic
    fun onLowMemory() {
        SVGAMemoryCache.INSTANCE.clear()
        SVGABitmapCache.INSTANCE.clear()
    }

    /**
     * 清除缓存
     */
    @JvmStatic
    fun clearCache() {
        SVGAMemoryCache.INSTANCE.clear()
        SVGABitmapCache.INSTANCE.clear()
        SVGAFileCache.clearCache()
    }
}