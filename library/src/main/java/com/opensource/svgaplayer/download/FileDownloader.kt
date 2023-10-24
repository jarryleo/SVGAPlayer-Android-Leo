package com.opensource.svgaplayer.download

import android.net.http.HttpResponseCache
import com.opensource.svgaplayer.SVGAParser
import com.opensource.svgaplayer.SVGAParser.Companion.TAG
import com.opensource.svgaplayer.utils.log.LogUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class FileDownloader {

    var noCache = false

    open fun resume(url: URL, complete: (inputStream: InputStream) -> Unit, failure: (e: Exception) -> Unit): () -> Unit {
        var cancelled = false
        val cancelBlock = {
            cancelled = true
        }
        SVGAParser.threadPoolExecutor.execute {
            try {
                LogUtils.info(TAG, "================ svga file download start ================")
                if (HttpResponseCache.getInstalled() == null && !noCache) {
                    LogUtils.error(TAG, "在配置 HttpResponseCache 前 SVGAParser 无法缓存. 查看 https://github.com/yyued/SVGAPlayer-Android#cache ")
                }
                (url.openConnection() as? HttpURLConnection)?.let {
                    it.connectTimeout = 20 * 1000
                    it.requestMethod = "GET"
                    it.setRequestProperty("Connection", "close")
                    it.connect()
                    it.inputStream.use { inputStream ->
                        ByteArrayOutputStream().use { outputStream ->
                            val buffer = ByteArray(4096)
                            var count: Int
                            while (true) {
                                if (cancelled) {
                                    LogUtils.warn(TAG, "================ svga file download canceled ================")
                                    break
                                }
                                count = inputStream.read(buffer, 0, 4096)
                                if (count == -1) {
                                    break
                                }
                                outputStream.write(buffer, 0, count)
                            }
                            if (cancelled) {
                                LogUtils.warn(TAG, "================ svga file download canceled ================")
                                return@execute
                            }
                            ByteArrayInputStream(outputStream.toByteArray()).use {
                                LogUtils.info(TAG, "================ svga file download complete ================")
                                complete(it)
                            }
                        }
                    }
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