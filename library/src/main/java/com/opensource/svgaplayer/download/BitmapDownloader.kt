package com.opensource.svgaplayer.download

import android.graphics.Bitmap
import com.opensource.svgaplayer.bitmap.SVGABitmapUrlDecoder
import com.opensource.svgaplayer.cache.SVGABitmapCache
import com.opensource.svgaplayer.utils.log.LogUtils
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * @Author     :Leo
 * Date        :2024/7/2
 * Description : Bitmap下载器
 */
object BitmapDownloader {
    private val downLoadQueue = ConcurrentLinkedQueue<String>()

    suspend fun downloadBitmap(url: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val key = SVGABitmapCache.createKey(url, reqWidth, reqHeight)
        val cacheData = SVGABitmapCache.INSTANCE.getData(key)
        if (cacheData != null) {
            return cacheData
        }
        if (downLoadQueue.contains(url)) {
            return null
        }
        LogUtils.debug(
            "BitmapDownloader",
            "downloadBitmap url = $url, reqWidth = $reqWidth, reqHeight = $reqHeight"
        )
        downLoadQueue.add(url)
        val bitmap = withTimeoutOrNull(30_000) {
            suspendCoroutine {
                val urlSafe = try {
                    URL(URLDecoder.decode(url, "UTF-8"))
                } catch (e: Exception) {
                    e.printStackTrace()
                    it.resume(null)
                    return@suspendCoroutine
                }
                val bitmap = SVGABitmapUrlDecoder.decodeBitmapFrom(
                    urlSafe,
                    reqWidth,
                    reqHeight
                )
                it.resume(bitmap)
            }
        }
        downLoadQueue.remove(url)
        LogUtils.debug("BitmapDownloader", "downloadBitmap bitmap = $bitmap")
        if (bitmap != null) {
            SVGABitmapCache.INSTANCE.putData(key, bitmap)
        }
        return bitmap
    }
}