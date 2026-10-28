package com.opensource.svgaplayer

import android.content.Context
import android.net.http.HttpResponseCache
import com.opensource.svgaplayer.cache.SVGABitmapCache
import com.opensource.svgaplayer.cache.SVGAFileCache
import com.opensource.svgaplayer.cache.SVGAMemoryCache
import com.opensource.svgaplayer.download.FileDownloader
import com.opensource.svgaplayer.url.UrlDecoder
import com.opensource.svgaplayer.url.UrlDecoderManager
import com.opensource.svgaplayer.utils.log.ILogger
import com.opensource.svgaplayer.utils.log.SVGALogger
import java.io.File
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.ThreadPoolExecutor

/**
 * @Author     :Leo
 * Date        :2024/2/23
 * Description : SVGAManager 初始化入口
 */
object SVGAManager {

    /** init 时记录，供 clearCache 后重建 http 缓存使用（避免持有外部 Context） */
    private var appContext: Context? = null
    private var httpCacheSize: Long = 256L * 1024 * 1024

    @JvmOverloads
    @JvmStatic
    fun init(
        context: Context,
        memoryCacheMaxBytes: Long = SVGAMemoryCache.defaultMaxBytes,
        httpCacheSize: Long = (256 * 1024 * 1024).toLong(),
        logEnabled: Boolean = BuildConfig.DEBUG,
        loggerProxy: ILogger? = null,
        urlDecoder: UrlDecoder? = null,
        threadPoolExecutor: ThreadPoolExecutor? = null
    ) {
        appContext = context.applicationContext
        this.httpCacheSize = httpCacheSize
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
        //修改实体内存缓存字节预算（按实体 bitmap 真实字节数记账，超限 LRU 驱逐）
        SVGAMemoryCache.defaultMaxBytes = memoryCacheMaxBytes
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
     * svga 预加载
     */
    fun preload(urlList: List<String>, width: Int, height: Int) {
        val urlDecoder = UrlDecoderManager.getUrlDecoder()
        val fileDownloader = FileDownloader()
        val queue = urlList
            .asSequence()
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .map { urlDecoder.decodeSvgaUrl(it, width, height) }
            .map {
                val decode = try {
                    URLDecoder.decode(it, "UTF-8")
                } catch (e: Exception) {
                    it
                }
                val url = try {
                    URL(decode)
                } catch (e: Exception) {
                    null
                }
                url
            }
            .mapNotNull {
                it
            }
            .toMutableList()
        preload(fileDownloader, queue, width, height)
    }

    private fun preload(
        fileDownloader: FileDownloader,
        queue: MutableList<URL>,
        width: Int,
        height: Int
    ) {
        val url = queue.removeFirstOrNull()
        if (url != null) {
            val cacheKey = SVGAFileCache.buildCacheKey(url)
            val cachedType = SVGAFileCache.getCachedType(cacheKey)
            if (cachedType != null) {
                preload(fileDownloader, queue, width, height)
                return
            }
            fileDownloader.resume(url, {
                preload(fileDownloader, queue, width, height)
            }, {
                preload(fileDownloader, queue, width, height)
            })
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
        reinstallHttpCache()
    }

    /**
     * 进程存活期间缓存目录可能被外部整体删除（系统设置清缓存、AppCacheUtils 直删 cacheDir），
     * 已安装的 HttpResponseCache 会指向不存在的目录导致后续 svga 下载持续失败；
     * 这里卸载后重装，保证清缓存后同进程内的下载恢复可用
     */
    private fun reinstallHttpCache() {
        val context = appContext ?: return
        try {
            HttpResponseCache.getInstalled()?.delete()
            HttpResponseCache.install(File(context.cacheDir, "http"), httpCacheSize)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}