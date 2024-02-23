package com.opensource.svgaplayer

import android.app.Application
import android.net.http.HttpResponseCache
import com.opensource.svgaplayer.cache.SVGACache
import com.opensource.svgaplayer.cache.SVGAMemoryCache
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
        context: Application,
        memoryCacheCount: Int = 8,
        httpCacheSize: Long = (256 * 1024 * 1024).toLong(),
        logEnabled: Boolean = false,
        loggerProxy: ILogger? = null,
        threadPoolExecutor: ThreadPoolExecutor? = null
    ) {
        try {
            val cacheDir = File(context.cacheDir, "http")
            HttpResponseCache.install(cacheDir, httpCacheSize)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // 配置cache
        SVGACache.init(context)
        //修改内存缓存大小
        SVGAMemoryCache.limitCount = memoryCacheCount
        //自定义线程池
        threadPoolExecutor?.let {
            SVGAParser.setThreadPoolExecutor(it)
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
}