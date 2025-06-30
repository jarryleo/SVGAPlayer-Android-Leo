package com.opensource.svgaplayer.download

import android.net.http.HttpResponseCache
import com.opensource.svgaplayer.cache.SVGAFileCache
import com.opensource.svgaplayer.coroutine.SvgaCoroutineManager
import com.opensource.svgaplayer.utils.log.LogUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

open class FileDownloader {

    companion object {
        private const val TAG = "SVGAFileDownloader"
        private const val SIZE = 4096
        private const val TIMEOUT = 30_000
    }

    /**
     * 下载文件
     * @param url URL
     * @param complete 完成回调
     * @param failure 失败回调
     * @return 协程 job ，可取消下载任务
     */
    open fun resume(
        url: URL, complete: (inputStream: InputStream) -> Unit, failure: (e: Exception) -> Unit = {}
    ): Job {
        return SvgaCoroutineManager.launchIo {
            //下载到缓存地址
            val cacheKey = SVGAFileCache.buildCacheKey(url)
            val cacheFile = SVGAFileCache.buildCacheFile(cacheKey)
            try {
                LogUtils.info(TAG) { "================ file download start ================ \r\n url = $url" }
                if (HttpResponseCache.getInstalled() == null) {
                    LogUtils.error(TAG) { "在配置 HttpResponseCache 前 SVGAParser 无法缓存. 查看 https://github.com/yyued/SVGAPlayer-Android#cache " }
                }

                (url.openConnection() as? HttpURLConnection)?.let {
                    it.connectTimeout = TIMEOUT
                    it.readTimeout = TIMEOUT
                    it.requestMethod = "GET"
                    it.setRequestProperty("Connection", "close")
                    it.connect()
                    if (!cacheFile.exists()) {
                        cacheFile.createNewFile()
                    }
                    FileOutputStream(cacheFile).use { output ->
                        it.inputStream.use { inputStream ->
                            val buffer = ByteArray(SIZE)
                            var count: Int
                            while (isActive) {
                                count = inputStream.read(buffer, 0, SIZE)
                                if (count == -1) {
                                    break
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    if (isActive) {
                        LogUtils.info(TAG) { "================ file download success ================" }
                        complete(cacheFile.inputStream())
                    } else {
                        LogUtils.info(TAG) { "================ file download cancel ================" }
                        cacheFile.delete()
                        failure(CancellationException("download cancel"))
                    }
                }
            } catch (e: Exception) {
                cacheFile.delete()
                LogUtils.error(TAG) { "================ file download fail ================" }
                LogUtils.error(TAG) { "error: ${e.message}" }
                e.printStackTrace()
                failure(e)
            }
        }
    }
}