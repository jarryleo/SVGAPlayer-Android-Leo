package com.opensource.svgaplayer.download

import android.net.http.HttpResponseCache
import com.opensource.svgaplayer.SVGAParser
import com.opensource.svgaplayer.cache.SVGACache
import com.opensource.svgaplayer.utils.log.LogUtils
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

open class FileDownloader {

    companion object {
        private const val TAG = "SVGAFileDownloader"
        private const val size = 4096
    }

    /**
     * 下载文件
     * @param url URL
     * @param complete 完成回调
     * @param failure 失败回调
     * @return 取消下载的方法
     */
    open fun resume(
        url: URL,
        complete: (inputStream: InputStream) -> Unit,
        failure: (e: Exception) -> Unit = {}
    ): () -> Unit {
        var cancelled = false
        val cancelBlock = {
            cancelled = true
        }
        SVGAParser.threadPoolExecutor.execute {
            try {
                LogUtils.info(TAG, "================ svga file download start ================")
                if (HttpResponseCache.getInstalled() == null) {
                    LogUtils.error(
                        TAG, "在配置 HttpResponseCache 前 SVGAParser 无法缓存." +
                                " 查看 https://github.com/yyued/SVGAPlayer-Android#cache "
                    )
                }

                (url.openConnection() as? HttpURLConnection)?.let {
                    it.connectTimeout = 20 * 1000
                    it.requestMethod = "GET"
                    it.setRequestProperty("Connection", "close")
                    it.connect()
                    val cacheKey = SVGACache.buildCacheKey(url)
                    val cacheFile = SVGACache.buildSvgaFile(cacheKey)
                    if (!cacheFile.exists()) {
                        cacheFile.createNewFile()
                    }
                    FileOutputStream(cacheFile).use { output ->
                        it.inputStream.use { inputStream ->
                            val buffer = ByteArray(size)
                            var count: Int
                            while (true) {
                                if (cancelled) {
                                    LogUtils.warn(
                                        TAG,
                                        "================ svga file download canceled ================"
                                    )
                                    break
                                }
                                count = inputStream.read(buffer, 0, size)
                                if (count == -1) {
                                    break
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    LogUtils.info(
                        TAG,
                        "================ svga file download success ================"
                    )
                    complete(cacheFile.inputStream())
                }
            } catch (e: Exception) {
                LogUtils.error(TAG, "================ svga file download fail ================")
                LogUtils.error(TAG, "error: ${e.message}")
                e.printStackTrace()
                failure(e)
            }
        }
        return cancelBlock
    }

}