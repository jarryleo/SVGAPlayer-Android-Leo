package com.opensource.svgaplayer.download

import android.net.http.HttpResponseCache
import com.opensource.svgaplayer.cache.SVGACache
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
            try {
                LogUtils.info(
                    TAG, "================ svga file download start ================ " +
                            "\r\n svga url = $url"
                )
                if (HttpResponseCache.getInstalled() == null) {
                    LogUtils.error(
                        TAG,
                        "在配置 HttpResponseCache 前 SVGAParser 无法缓存."
                                + " 查看 https://github.com/yyued/SVGAPlayer-Android#cache "
                    )
                }

                (url.openConnection() as? HttpURLConnection)?.let {
                    it.connectTimeout = 30 * 1000
                    it.requestMethod = "GET"
                    it.setRequestProperty("Connection", "close")
                    it.connect()
                    //下载到缓存地址
                    val cacheKey = SVGACache.buildCacheKey(url)
                    val cacheFile = SVGACache.buildSvgaFile(cacheKey)
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
                        LogUtils.info(
                            TAG, "================ svga file download success ================"
                        )
                        complete(cacheFile.inputStream())
                    } else {
                        LogUtils.info(
                            TAG, "================ svga file download cancel ================"
                        )
                        failure(CancellationException("download cancel"))
                    }
                }
            } catch (e: Exception) {
                LogUtils.error(TAG, "================ svga file download fail ================")
                LogUtils.error(TAG, "error: ${e.message}")
                e.printStackTrace()
                failure(e)
            }
        }
    }
}