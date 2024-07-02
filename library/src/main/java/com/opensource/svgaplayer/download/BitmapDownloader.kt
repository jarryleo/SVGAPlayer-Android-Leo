package com.opensource.svgaplayer.download

import android.graphics.Bitmap
import com.opensource.svgaplayer.bitmap.SVGABitmapFileDecoder
import com.opensource.svgaplayer.bitmap.SVGABitmapUrlDecoder
import com.opensource.svgaplayer.cache.SVGABitmapCache
import com.opensource.svgaplayer.cache.SVGAFileCache
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
        // 从内存缓存中获取
        val cacheData = SVGABitmapCache.INSTANCE.getData(key)
        if (cacheData != null) {
            return cacheData
        }
        // 从磁盘缓存中获取
        val diskData = getBitmapFromDisk(key, reqWidth, reqHeight)
        if (diskData != null) {
            SVGABitmapCache.INSTANCE.putData(key, diskData)
            return diskData
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
                if (bitmap != null) {
                    cacheBitmapToDisk(key, bitmap)
                }
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

    private fun getBitmapFromDisk(cacheKey: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val cacheFile = SVGAFileCache.buildCacheFile(cacheKey)
        if (!cacheFile.exists()) {
            return null
        }
        return SVGABitmapFileDecoder.decodeBitmapFrom(cacheFile.toString(), reqWidth, reqHeight)
    }

    private fun cacheBitmapToDisk(cacheKey: String, bitmap: Bitmap) {
        val cacheFile = SVGAFileCache.buildCacheFile(cacheKey)
        if (!cacheFile.exists()) {
            cacheFile.createNewFile()
        }
        cacheFile.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}