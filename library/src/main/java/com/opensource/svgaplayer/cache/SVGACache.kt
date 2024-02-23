package com.opensource.svgaplayer.cache

import android.content.Context
import com.opensource.svgaplayer.SVGAParser
import com.opensource.svgaplayer.utils.log.LogUtils
import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * SVGA 缓存管理（文件缓存）
 */
object SVGACache {
    enum class Type {
        ZIP,
        FILE
    }

    private const val TAG = "SVGACache"
    private var cacheDir: String = "/"
        get() {
            if (field != "/") {
                val dir = File(field)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
            }
            return field
        }


    fun init(context: Context) {
        if (isInitialized()) return
        cacheDir = "${context.cacheDir.absolutePath}/svga/"
        File(cacheDir).takeIf { !it.exists() }?.mkdirs()
    }

    /**
     * 清理缓存
     */
    fun clearCache() {
        if (!isInitialized()) {
            LogUtils.error(TAG, "SVGACache is not init!")
            return
        }
        SVGAParser.threadPoolExecutor.execute {
            clearDir(cacheDir)
            LogUtils.info(TAG, "Clear svga cache done!")
        }
    }

    // 清除目录下的所有文件
    internal fun clearDir(path: String) {
        try {
            val dir = File(path)
            dir.takeIf { it.exists() }?.let { parentDir ->
                parentDir.listFiles()?.forEach { file ->
                    if (!file.exists()) {
                        return@forEach
                    }
                    if (file.isDirectory) {
                        clearDir(file.absolutePath)
                    }
                    file.delete()
                }
            }
        } catch (e: Exception) {
            LogUtils.error(TAG, "Clear svga cache path: $path fail", e)
        }
    }

    fun isInitialized(): Boolean {
        return "/" != cacheDir && File(cacheDir).exists()
    }

    /**
     * 判断缓存是否存在，存在则返回缓存类型
     */
    fun getCachedType(cacheKey: String): Type? {
        if (buildCacheDir(cacheKey).exists()) return Type.ZIP
        if (buildSvgaFile(cacheKey).exists()) return Type.FILE
        return null
    }

    fun buildCacheKey(str: String): String {
        val messageDigest = MessageDigest.getInstance("MD5")
        messageDigest.update(str.toByteArray(charset("UTF-8")))
        val digest = messageDigest.digest()
        var sb = ""
        for (b in digest) {
            sb += String.format("%02x", b)
        }
        return sb
    }

    fun buildCacheKey(url: URL): String = buildCacheKey(url.toString())

    fun buildCacheDir(cacheKey: String): File {
        return File("$cacheDir$cacheKey/")
    }

    fun buildSvgaFile(cacheKey: String): File {
        return File("$cacheDir$cacheKey.svga")
    }

    fun buildAudioFile(audio: String): File {
        return File("$cacheDir$audio.mp3")
    }

}