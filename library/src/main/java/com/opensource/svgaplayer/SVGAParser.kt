package com.opensource.svgaplayer

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.opensource.svgaplayer.cache.SVGACache
import com.opensource.svgaplayer.cache.SVGAMemoryCache
import com.opensource.svgaplayer.coroutine.SvgaCoroutineManager
import com.opensource.svgaplayer.download.FileDownloader
import com.opensource.svgaplayer.proto.MovieEntity
import com.opensource.svgaplayer.utils.log.LogUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.util.concurrent.ThreadPoolExecutor
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream
import kotlin.properties.Delegates

/**
 * Created by PonyCui 16/6/18.
 */
private var fileLock: Any = Any()
private var isUnzipping = false

class SVGAParser private constructor(context: Context) {
    private var mContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    interface ParseCompletion {
        fun onComplete(videoItem: SVGAVideoEntity)
        fun onError()
    }

    interface PlayCallback {
        fun onPlay(file: List<File>)
    }

    private var fileDownloader = FileDownloader()

    companion object {
        const val TAG = "SVGAParser"

        private var mShareParser by Delegates.notNull<SVGAParser>()

        @JvmStatic
        fun setThreadPoolExecutor(executor: ThreadPoolExecutor) {
            SvgaCoroutineManager.setThreadPoolExecutor(executor)
        }

        @JvmStatic
        fun shareParser(): SVGAParser {
            return mShareParser
        }

        fun init(context: Context) {
            mShareParser = SVGAParser(context)
        }
    }

    fun decodeFromAssets(
        name: String,
        callback: ParseCompletion?,
        playCallback: PlayCallback? = null
    ): Job? {
        return decodeFromAssets(name, config = SVGAConfig(), callback, playCallback)
    }

    fun decodeFromAssets(
        name: String,
        config: SVGAConfig,
        callback: ParseCompletion?,
        playCallback: PlayCallback? = null
    ): Job? {
        if (mContext == null) {
            LogUtils.error(TAG, "在配置 SVGAParser context 前, 无法解析 SVGA 文件。")
            return null
        }
        LogUtils.info(TAG, "================ decode $name from assets ================")
        //加载内存缓存数据
        val memoryCacheKey: String? =
            if (config.isCacheToMemory) SVGAMemoryCache.createKey(name, config) else null
        if (decodeFromMemoryCacheKey(memoryCacheKey, config, callback, playCallback, name)) {
            return null
        }
        //加载Assets数据
        return SvgaCoroutineManager.launchIo {
            try {
                mContext?.assets?.open(name)?.let {
                    decodeFromInputStream(
                        it,
                        SVGACache.buildCacheKey("file:///assets/$name"),
                        config,
                        callback,
                        true,
                        playCallback,
                        memoryCacheKey,
                        alias = name
                    )
                }
            } catch (e: Exception) {
                invokeErrorCallback(e, callback, name)
            }
        }
    }

    fun decodeFromURL(
        url: URL,
        callback: ParseCompletion?,
        playCallback: PlayCallback? = null
    ): Job? {
        return decodeFromURL(url, config = SVGAConfig(), callback, playCallback)
    }

    fun decodeFromURL(
        url: URL,
        config: SVGAConfig,
        callback: ParseCompletion?,
        playCallback: PlayCallback? = null
    ): Job? {
        if (mContext == null) {
            LogUtils.error(TAG, "在配置 SVGAParser context 前, 无法解析 SVGA 文件。")
            return null
        }
        val urlPath = url.toString()
        LogUtils.info(TAG, "================ decode from url: $urlPath ================")
        //加载内存缓存数据
        val memoryCacheKey: String? =
            if (config.isCacheToMemory) SVGAMemoryCache.createKey(urlPath, config) else null
        if (decodeFromMemoryCacheKey(memoryCacheKey, config, callback, playCallback, urlPath)) {
            return null
        }
        val cacheKey = SVGACache.buildCacheKey(url)
        val cachedType = SVGACache.getCachedType(cacheKey)
        return if (cachedType != null) { //加载本地缓存数据
            LogUtils.info(TAG, "this url has disk cached")
            SvgaCoroutineManager.launchIo {
                if (cachedType == SVGACache.Type.ZIP) {
                    decodeFromUnzipDirCacheKey(
                        cacheKey,
                        config,
                        callback,
                        memoryCacheKey,
                        alias = urlPath
                    )
                } else {
                    decodeFromSVGAFileCacheKey(
                        cacheKey,
                        config,
                        callback,
                        playCallback,
                        memoryCacheKey,
                        alias = urlPath
                    )
                }
            }
            return null
        } else { //加载网络数据（下载资源）
            LogUtils.info(TAG, "no cached, prepare to download")
            fileDownloader.resume(url, {
                this.decodeFromInputStream(
                    it,
                    cacheKey,
                    config,
                    callback,
                    false,
                    playCallback,
                    memoryCacheKey,
                    alias = urlPath
                )
            }, {
                this.invokeErrorCallback(it, callback, alias = urlPath)
            })
        }
    }

    /**
     * 读取解析本地缓存的 svga 文件.
     */
    private fun decodeFromSVGAFileCacheKey(
        cacheKey: String,
        config: SVGAConfig,
        callback: ParseCompletion?,
        playCallback: PlayCallback?,
        memoryCacheKey: String?,
        alias: String? = null
    ) {
        SvgaCoroutineManager.launchIo {
            try {
                LogUtils.info(
                    TAG,
                    "================ decode $alias from svga cachel file to entity ================"
                )
                val svgaFile = SVGACache.buildSvgaFile(cacheKey)
                FileInputStream(svgaFile).use { inputStream ->
                    //检查是否是zip文件
                    val magicCode = ByteArray(4)
                    if (inputStream.markSupported()) {
                        inputStream.mark(4)
                        inputStream.read(magicCode)
                        inputStream.reset()
                    }
                    if (isZipFile(magicCode)) {
                        decodeFromUnzipDirCacheKey(
                            cacheKey,
                            config,
                            callback,
                            memoryCacheKey,
                            alias
                        )
                    } else {
                        LogUtils.info(TAG, "inflate start")
                        val videoItem = SVGAVideoEntity(
                            MovieEntity.ADAPTER.decode(InflaterInputStream(inputStream)),
                            File(cacheKey),
                            config.frameWidth,
                            config.frameHeight,
                            memoryCacheKey
                        )
                        LogUtils.info(TAG, "inflate complete")
                        LogUtils.info(TAG, "SVGAVideoEntity prepare start")
                        videoItem.prepare({
                            LogUtils.info(TAG, "SVGAVideoEntity prepare success")
                            invokeCompleteCallback(videoItem, callback, alias)
                        }, playCallback)
                    }
                }
            } catch (e: java.lang.Exception) {
                invokeErrorCallback(e, callback, alias)
            } finally {
                LogUtils.info(
                    TAG,
                    "================ decode $alias from svga cachel file to entity end ================"
                )
            }
        }
    }

    private fun decodeFromInputStream(
        inputStream: InputStream,
        cacheKey: String,
        config: SVGAConfig,
        callback: ParseCompletion?,
        closeInputStream: Boolean = false,
        playCallback: PlayCallback? = null,
        memoryCacheKey: String?,
        alias: String? = null
    ): Job? {
        if (mContext == null) {
            LogUtils.error(TAG, "在配置 SVGAParser context 前, 无法解析 SVGA 文件。")
            return null
        }
        LogUtils.info(TAG, "================ decode $alias from input stream ================")
        return SvgaCoroutineManager.launchIo {
            try {
                //检查是否是zip文件
                val magicCode = ByteArray(4)
                if (inputStream.markSupported()) {
                    inputStream.mark(4)
                    inputStream.read(magicCode)
                    inputStream.reset()
                }
                if (isZipFile(magicCode)) {
                    LogUtils.info(TAG, "decode from zip file")
                    if (!SVGACache.buildCacheDir(cacheKey).exists() || isUnzipping) {
                        synchronized(fileLock) {
                            if (!SVGACache.buildCacheDir(cacheKey).exists()) {
                                isUnzipping = true
                                LogUtils.info(TAG, "no cached, prepare to unzip")
                                unzip(inputStream, cacheKey)
                                isUnzipping = false
                                LogUtils.info(TAG, "unzip success")
                            }
                        }
                    }
                    decodeFromUnzipDirCacheKey(
                        cacheKey,
                        config,
                        callback,
                        memoryCacheKey,
                        alias
                    )
                } else {
                    val videoItem = SVGAVideoEntity(
                        MovieEntity.ADAPTER.decode(InflaterInputStream(inputStream)),
                        File(cacheKey),
                        config.frameWidth,
                        config.frameHeight,
                        memoryCacheKey
                    )
                    LogUtils.info(TAG, "SVGAVideoEntity prepare start")
                    videoItem.prepare({
                        LogUtils.info(TAG, "SVGAVideoEntity prepare success")
                        invokeCompleteCallback(videoItem, callback, alias)
                    }, playCallback)
                }
            } catch (e: java.lang.Exception) {
                invokeErrorCallback(e, callback, alias)
            } finally {
                if (closeInputStream) {
                    inputStream.close()
                }
                LogUtils.info(
                    TAG,
                    "================ decode $alias from input stream end ================"
                )
            }
        }
    }

    /**
     * @deprecated from 2.4.0
     */
    @Deprecated(
        "This method has been deprecated from 2.4.0.",
        ReplaceWith("this.decodeFromAssets(assetsName, callback)")
    )
    fun parse(assetsName: String, config: SVGAConfig, callback: ParseCompletion?) {
        this.decodeFromAssets(assetsName, config, callback, null)
    }

    /**
     * @deprecated from 2.4.0
     */
    @Deprecated(
        "This method has been deprecated from 2.4.0.",
        ReplaceWith("this.decodeFromURL(url, callback)")
    )
    fun parse(url: URL, config: SVGAConfig, callback: ParseCompletion?) {
        this.decodeFromURL(url, config, callback, null)
    }

    private fun invokeCompleteCallback(
        videoItem: SVGAVideoEntity,
        callback: ParseCompletion?,
        alias: String?
    ) {
        handler.post {
            LogUtils.info(TAG, "================ $alias parser complete ================")
            callback?.onComplete(videoItem)
        }
    }

    private fun invokeErrorCallback(
        e: Exception,
        callback: ParseCompletion?,
        alias: String?
    ) {
        e.printStackTrace()
        LogUtils.error(TAG, "================ $alias parser error ================")
        LogUtils.error(TAG, "$alias parse error", e)
        handler.post {
            callback?.onError()
        }
    }

    /**
     * 从解压缓存中加载
     */
    private fun decodeFromUnzipDirCacheKey(
        cacheKey: String,
        config: SVGAConfig,
        callback: ParseCompletion?,
        memoryCacheKey: String?,
        alias: String?
    ): Job? {
        LogUtils.info(TAG, "================ decode $alias from cache ================")
        LogUtils.debug(TAG, "decodeFromCacheKey called with cacheKey : $cacheKey")
        if (mContext == null) {
            LogUtils.error(TAG, "在配置 SVGAParser context 前, 无法解析 SVGA 文件。")
            return null
        }
        return SvgaCoroutineManager.launchIo {
            try {
                val cacheDir = SVGACache.buildCacheDir(cacheKey)
                File(cacheDir, "movie.binary").takeIf { it.isFile }?.let { binaryFile ->
                    try {
                        LogUtils.info(TAG, "binary change to entity")
                        FileInputStream(binaryFile).use {
                            LogUtils.info(TAG, "binary change to entity success")
                            invokeCompleteCallback(
                                SVGAVideoEntity(
                                    MovieEntity.ADAPTER.decode(it),
                                    cacheDir,
                                    config.frameWidth,
                                    config.frameHeight,
                                    memoryCacheKey
                                ),
                                callback,
                                alias
                            )
                        }

                    } catch (e: Exception) {
                        LogUtils.error(TAG, "binary change to entity fail", e)
                        cacheDir.delete()
                        binaryFile.delete()
                        throw e
                    }
                }
                File(cacheDir, "movie.spec").takeIf { it.isFile }?.let { jsonFile ->
                    try {
                        LogUtils.info(TAG, "spec change to entity")
                        FileInputStream(jsonFile).use { fileInputStream ->
                            ByteArrayOutputStream().use { byteArrayOutputStream ->
                                val buffer = ByteArray(2048)
                                while (isActive) {
                                    val size = fileInputStream.read(buffer, 0, buffer.size)
                                    if (size == -1) {
                                        break
                                    }
                                    byteArrayOutputStream.write(buffer, 0, size)
                                }
                                byteArrayOutputStream.toString().let {
                                    JSONObject(it).let { json ->
                                        LogUtils.info(TAG, "spec change to entity success")
                                        invokeCompleteCallback(
                                            SVGAVideoEntity(
                                                json,
                                                cacheDir,
                                                config.frameWidth,
                                                config.frameHeight,
                                                memoryCacheKey
                                            ),
                                            callback,
                                            alias
                                        )
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        LogUtils.error(TAG, "$alias movie.spec change to entity fail", e)
                        cacheDir.delete()
                        jsonFile.delete()
                        throw e
                    }
                }
            } catch (e: Exception) {
                invokeErrorCallback(e, callback, alias)
            }
        }
    }

    /**
     * 加载内存缓存
     * @return true内部已将数据通过接口返回，不用再加载
     */
    private fun decodeFromMemoryCacheKey(
        memoryCacheKey: String?,
        config: SVGAConfig,
        callback: ParseCompletion?,
        playCallback: PlayCallback?,
        alias: String?
    ): Boolean {
        return if (config.isCacheToMemory && !memoryCacheKey.isNullOrEmpty()) { //加载内存缓存
            //缓存数据为空
            val entity = SVGAMemoryCache.INSTANCE.getData(memoryCacheKey) ?: return false
            entity.prepare({
                LogUtils.info(TAG, "decodeFromMemoryCacheKey prepare success")
                this.invokeCompleteCallback(entity, callback, alias = alias)
            }, playCallback)
            true
        } else {
            false
        }
    }

    // 是否是 zip 文件
    private fun isZipFile(bytes: ByteArray): Boolean {
        return bytes.size >= 4
                && bytes[0].toInt() == 80
                && bytes[1].toInt() == 75
                && bytes[2].toInt() == 3
                && bytes[3].toInt() == 4
    }

    // 解压
    private fun unzip(inputStream: InputStream, cacheKey: String) {
        LogUtils.info(TAG, "================ unzip prepare ================")
        val cacheDir = SVGACache.buildCacheDir(cacheKey)
        cacheDir.mkdirs()
        try {
            BufferedInputStream(inputStream).use {
                ZipInputStream(it).use { zipInputStream ->
                    while (true) {
                        val zipItem = zipInputStream.nextEntry ?: break
                        if (zipItem.name.contains("../")) {
                            // 解压路径存在路径穿越问题，直接过滤
                            continue
                        }
                        if (zipItem.name.contains("/")) {
                            continue
                        }
                        val file = File(cacheDir, zipItem.name)
                        ensureUnzipSafety(file, cacheDir.absolutePath)
                        FileOutputStream(file).use { fileOutputStream ->
                            val buff = ByteArray(2048)
                            while (true) {
                                val readBytes = zipInputStream.read(buff)
                                if (readBytes <= 0) {
                                    break
                                }
                                fileOutputStream.write(buff, 0, readBytes)
                            }
                        }
                        LogUtils.error(TAG, "================ unzip complete ================")
                        zipInputStream.closeEntry()
                    }
                }
            }
            //解压完成，删除下载缓存
            val downloadCacheFile = SVGACache.buildSvgaFile(cacheKey)
            if (downloadCacheFile.exists()) {
                downloadCacheFile.delete()
            }
        } catch (e: Exception) {
            LogUtils.error(TAG, "================ unzip error ================")
            LogUtils.error(TAG, "error", e)
            SVGACache.clearDir(cacheDir.absolutePath)
            cacheDir.delete()
            throw e
        }
    }

    // 检查 zip 路径穿透
    private fun ensureUnzipSafety(outputFile: File, dstDirPath: String) {
        val dstDirCanonicalPath = File(dstDirPath).canonicalPath
        val outputFileCanonicalPath = outputFile.canonicalPath
        if (!outputFileCanonicalPath.startsWith(dstDirCanonicalPath)) {
            throw IOException("Found Zip Path Traversal Vulnerability with $dstDirCanonicalPath")
        }
    }
}
