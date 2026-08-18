package com.opensource.svgaplayer

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.opensource.svgaplayer.cache.SVGADiskLoadingQueue
import com.opensource.svgaplayer.cache.SVGAFileCache
import com.opensource.svgaplayer.cache.SVGAMemoryCache
import com.opensource.svgaplayer.cache.SVGAMemoryLoadingQueue
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
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.util.concurrent.ThreadPoolExecutor
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Created by PonyCui 16/6/18.
 */
private var fileLock: Any = Any()
private var isUnzipping = false

class SVGAParser private constructor(context: Context) {
    private var mContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 与等待队列共用的锁：保证“读内存缓存 + 入队”与“写内存缓存 + 移除通知”两个复合操作互斥，
     * 否则两者交错会出现个别等待者既收不到 onComplete 也收不到 onError（列表批量加载同一动画时表现为个别不播放）。
     */
    private val loadStateLock get() = SVGAMemoryLoadingQueue.lock

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

        @SuppressLint("StaticFieldLeak")
        private var mShareParser: SVGAParser? = null

        @JvmStatic
        fun setThreadPoolExecutor(executor: ThreadPoolExecutor) {
            SvgaCoroutineManager.setThreadPoolExecutor(executor)
        }

        @JvmStatic
        fun shareParser(): SVGAParser? {
            return mShareParser
        }

        fun init(context: Context) {
            if (mShareParser == null) {
                mShareParser = SVGAParser(context)
            }
        }
    }

    fun decodeFromFile(
        path: String,
        config: SVGAConfig,
        callback: ParseCompletion?,
        playCallback: PlayCallback? = null
    ): Job? {
        if (mContext == null) {
            LogUtils.error(TAG, "在配置 SVGAParser context 前, 无法解析 SVGA 文件。")
            return null
        }
        LogUtils.info(TAG, "================ decode $path from file ================")
        //加载内存缓存数据
        val memoryCacheKey: String? =
            if (config.isCacheToMemory) SVGAMemoryCache.createKey(path, config) else null
        if (decodeFromMemoryCacheKey(memoryCacheKey, config, callback, playCallback, path)) {
            return null
        }
        //加载文件数据
        return startDecodeFromFile(path, config, callback, playCallback, memoryCacheKey)
    }

    /**
     * 实际执行文件读取解析（跳过内存缓存与等待队列，供首次加载与取消后的接管重载共用）。
     * 接管重载时 [callback] 为 null，完成后仅通知等待队列；[isRestart] 阻止取消回调里的递归重启。
     */
    private fun startDecodeFromFile(
        path: String,
        config: SVGAConfig,
        callback: ParseCompletion?,
        playCallback: PlayCallback?,
        memoryCacheKey: String?,
        isRestart: Boolean = false
    ): Job? {
        return SvgaCoroutineManager.launchIo {
            try {
                val cacheKey = SVGAFileCache.buildCacheKey(path)
                var inputStream: InputStream? = null
                val file = kotlin.runCatching { File(path) }.getOrNull()
                if (file?.exists() == true) {
                    if (file.isFile) {
                        inputStream = FileInputStream(file)
                    }
                } else {
                    val uri = kotlin.runCatching { Uri.parse(path) }.getOrNull()
                    val scheme = uri?.scheme?.lowercase()
                    when (scheme) {
                        "http", "https", "file" -> {
                            inputStream = URL(path).openStream()
                        }

                        "content" -> {
                            inputStream = mContext.contentResolver.openInputStream(uri)
                        }
                    }
                }
                inputStream?.let {
                    decodeFromInputStream(
                        it,
                        cacheKey,
                        config,
                        callback,
                        true,
                        playCallback,
                        memoryCacheKey,
                        alias = path
                    )
                } ?: run {
                    notifyQueueError(memoryCacheKey, callback)
                    invokeErrorCallback(Exception("file inputStream is null"), callback, path)
                }
            } catch (e: Exception) {
                notifyQueueError(memoryCacheKey, callback)
                invokeErrorCallback(e, callback, path)
            }
        }.apply {
            invokeOnCompletion { exception ->
                if (exception is CancellationException) {
                    LogUtils.info(TAG, "================ decode $path from file canceled ================")
                    //加载者被取消：仍有等待者则接管重载；接管自身被取消或无等待者时给等待队列补发错误
                    val restarted = !isRestart && restartDecodeIfWaiting(memoryCacheKey, path) {
                        startDecodeFromFile(path, config, null, null, memoryCacheKey, isRestart = true)
                    }
                    if (!restarted) {
                        notifyQueueError(memoryCacheKey, callback)
                    }
                }
            }
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
        //加载内存缓存数据
        val memoryCacheKey: String? =
            if (config.isCacheToMemory) SVGAMemoryCache.createKey(name, config) else null
        LogUtils.info(
            TAG,
            "================ decode $name from assets memoryCacheKey = $memoryCacheKey ================"
        )
        if (decodeFromMemoryCacheKey(memoryCacheKey, config, callback, playCallback, name)) {
            return null
        }
        //加载Assets数据
        return startDecodeFromAssets(name, config, callback, playCallback, memoryCacheKey)
    }

    /**
     * 实际执行 assets 读取解析（跳过内存缓存与等待队列，供首次加载与取消后的接管重载共用）。
     * 接管重载时 [callback] 为 null，完成后仅通知等待队列；[isRestart] 阻止取消回调里的递归重启。
     */
    private fun startDecodeFromAssets(
        name: String,
        config: SVGAConfig,
        callback: ParseCompletion?,
        playCallback: PlayCallback?,
        memoryCacheKey: String?,
        isRestart: Boolean = false
    ): Job? {
        return SvgaCoroutineManager.launchIo {
            try {
                mContext?.assets?.open(name)?.let {
                    decodeFromInputStream(
                        it,
                        SVGAFileCache.buildCacheKey("file:///assets/$name"),
                        config,
                        callback,
                        true,
                        playCallback,
                        memoryCacheKey,
                        alias = name
                    )
                } ?: run {
                    notifyQueueError(memoryCacheKey, callback)
                    invokeErrorCallback(Exception("assets inputStream is null"), callback, name)
                }
            } catch (e: Exception) {
                notifyQueueError(memoryCacheKey, callback)
                invokeErrorCallback(e, callback, name)
            }
        }.apply {
            invokeOnCompletion { exception ->
                if (exception is CancellationException) {
                    LogUtils.info(TAG, "================ decode $name from assets canceled ================")
                    //加载者被取消：仍有等待者则接管重载；接管自身被取消或无等待者时给等待队列补发错误
                    val restarted = !isRestart && restartDecodeIfWaiting(memoryCacheKey, name) {
                        startDecodeFromAssets(name, config, null, null, memoryCacheKey, isRestart = true)
                    }
                    if (!restarted) {
                        notifyQueueError(memoryCacheKey, callback)
                    }
                }
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
        return startDecodeFromURL(url, config, callback, playCallback, memoryCacheKey)
    }

    /**
     * 实际执行磁盘缓存读取 / 下载解析（跳过内存缓存与等待队列，供首次加载与取消后的接管重载共用）。
     * 接管重载时 [callback] 为 null，完成后仅通知等待队列；此时 [isRestart] 为 true，
     * 用于阻止其 invokeOnCompletion 再次触发 restart 递归（避免 App 关闭/作用域级联取消时的协程级联）。
     */
    private fun startDecodeFromURL(
        url: URL,
        config: SVGAConfig,
        callback: ParseCompletion?,
        playCallback: PlayCallback?,
        memoryCacheKey: String?,
        isRestart: Boolean = false
    ): Job? {
        val urlPath = url.toString()
        val cacheKey = SVGAFileCache.buildCacheKey(url)
        //磁盘下载去重：同 URL 已有在途下载则入队等待，避免两个 FileDownloader 并发写同一 cacheFile。
        //等待者拿到的是代理 Job：取消（view 回收）只把自己移出队列，不会误杀加载者的下载
        val (joined, proxyJob) = SVGADiskLoadingQueue.joinOrStart(cacheKey, callback, playCallback, config)
        if (joined) {
            LogUtils.info(TAG, "disk load in flight, join as waiter: $urlPath")
            return proxyJob
        }
        val cachedType = SVGAFileCache.getCachedType(cacheKey)
        val job = if (cachedType != null) { //加载本地缓存数据
            LogUtils.info(TAG, "this url has disk cached")
            SvgaCoroutineManager.launchIo {
                if (cachedType == SVGAFileCache.Type.ZIP) {
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
            }, { e ->
                if (e is CancellationException) {
                    //下载被取消：错误通知交给下方 invokeOnCompletion 统一决策（等待队列可能接管重载），
                    //这里直接补发会把等待队列整体清空误伤其他 view
                    return@resume
                }
                notifyQueueError(memoryCacheKey, callback)
                this.invokeErrorCallback(e, callback, alias = urlPath)
            })
        }
        SVGADiskLoadingQueue.registerJob(cacheKey, job)
        return job.apply {
            invokeOnCompletion { exception ->
                if (exception is CancellationException) {
                    LogUtils.info(
                        TAG, "================ decode from url canceled: $urlPath ================"
                    )
                    //接管重载自身被取消（典型：App 退出、scope 级联）时不再递归重启，
                    //直接清空等待队列并补发错误，避免协程级联导致的对象增长
                    val restarted = !isRestart && restartDecodeIfWaiting(memoryCacheKey, urlPath) {
                        startDecodeFromURL(url, config, null, null, memoryCacheKey, isRestart = true)
                    }
                    if (!restarted) {
                        notifyQueueError(memoryCacheKey, callback)
                    }
                    //磁盘队列接管：有等待者时才清掉 in-flight 并重启下载（等待者仍在 loadingMap，
                    //由接管下载完成时通知）；无等待者的普通 clear 不重启——
                    //否则取消反而触发一次后台全量重下，列表滑动同一 URL 时反复下载且永远下不完
                    val takeover = !isRestart && SVGADiskLoadingQueue.prepareTakeover(cacheKey)
                    if (takeover) {
                        LogUtils.info(
                            TAG, "disk loader canceled, start takeover for waiter(s): $urlPath"
                        )
                        SvgaCoroutineManager.launchIo {
                            startDecodeFromURL(url, config, null, null, memoryCacheKey, isRestart = true)
                        }
                    } else {
                        //接管递归阻断或无等待者：清空队列，给剩余等待者发 onError
                        SVGADiskLoadingQueue.cancelAndTakeWaiters(cacheKey)?.forEach {
                            handler.post { it.callback?.onError() }
                        }
                    }
                }
            }
        }
    }

    /**
     * 加载任务被取消时，若仍有 view 在等待同一资源（列表批量加载同一动画场景），处理等待者：
     * 1. 缓存已被其他路径填充 → 复用缓存实体直接通知等待队列，避免重复下载/解析；
     * 2. 缓存空 + 仍有等待者 → 发起一次无直接回调的接管加载（由等待者接管完成后的统一通知）；
     * 3. 缓存空 + 无等待者 → 返回 false，由调用方按常规路径给直接回调补发错误。
     * [reload] 为具体资源（URL/文件/assets）的接管重载动作，须以 isRestart = true 阻断递归重启。
     */
    private fun restartDecodeIfWaiting(
        memoryCacheKey: String?,
        alias: String?,
        reload: () -> Unit
    ): Boolean {
        if (memoryCacheKey.isNullOrEmpty()) return false
        //与完成路径使用同一把锁原子处理：复用缓存避免重复下载，或确认仍需重启动
        var cachedEntity: SVGAVideoEntity? = null
        var waiters: List<SVGAMemoryLoadingQueue.SVGAMemoryLoadingItem>? = null
        val shouldRestart = synchronized(loadStateLock) {
            cachedEntity = SVGAMemoryCache.INSTANCE.getData(memoryCacheKey)
            if (cachedEntity != null) {
                //缓存已被填充：清空等待队列，复用已就绪实体
                waiters = SVGAMemoryLoadingQueue.removeItem(memoryCacheKey)
                false
            } else {
                SVGAMemoryLoadingQueue.inQueue(memoryCacheKey)
            }
        }
        if (cachedEntity != null) {
            LogUtils.info(
                TAG,
                "cancel handler: cache hit, notify ${waiters?.size ?: 0} waiter(s) from cache: $alias"
            )
            val entity = cachedEntity
            handler.post {
                waiters?.forEach {
                    it.callback?.onComplete(entity)
                }
            }
            return true
        }
        if (!shouldRestart) return false
        LogUtils.info(TAG, "restart decode for waiting queue: $alias")
        SvgaCoroutineManager.launchIo {
            reload()
        }
        return true
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
            val svgaFile = SVGAFileCache.buildCacheFile(cacheKey)
            //二次校验：从 getCachedType 通过到 launchIo 真正打开文件之间，
            //文件可能已被并发的取消/失败路径删除（FileDownloader.kt:81/86），
            //此时直接走下载路径而非报 parser error
            if (!svgaFile.exists()) {
                LogUtils.info(
                    TAG,
                    "================ decode $alias cache file missing before open, fallback to download ================"
                )
                fallbackToDownloadFromCache(cacheKey, alias, config, callback, playCallback, memoryCacheKey)
                return@launchIo
            }
            try {
                LogUtils.info(
                    TAG,
                    "================ decode $alias from svga cache file to entity ================ \n" +
                            "svga cache File = $svgaFile"
                )
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
                        InflaterInputStream(inputStream).use { inflaterInputStream ->
                            val entity = MovieEntity.ADAPTER.decode(inflaterInputStream)
                            val videoItem = SVGAVideoEntity(
                                entity,
                                File(cacheKey),
                                config.frameWidth,
                                config.frameHeight,
                                memoryCacheKey
                            )
                            LogUtils.info(
                                TAG,
                                "inflate complete : width = ${config.frameWidth}, height = ${config.frameHeight}, size = ${videoItem.getMemorySize()}"
                            )
                            LogUtils.info(TAG, "SVGAVideoEntity prepare start")
                            videoItem.prepare({
                                LogUtils.info(TAG, "SVGAVideoEntity prepare success")
                                invokeCompleteCallback(videoItem, callback, alias)
                            }, playCallback)
                        }
                    }
                }
            } catch (e: FileNotFoundException) {
                //检测到检查后被并发路径删除的极端竞态，同样走下载路径
                LogUtils.info(
                    TAG,
                    "================ decode $alias cache file missing at open, fallback to download ================"
                )
                fallbackToDownloadFromCache(cacheKey, alias, config, callback, playCallback, memoryCacheKey)
            } catch (e: Exception) {
                notifyQueueError(memoryCacheKey, callback)
                svgaFile.delete() //解码失败删除文件，否则一直失败
                invokeErrorCallback(e, callback, alias)
            } finally {
                LogUtils.info(
                    TAG,
                    "================ decode $alias from svga cachel file to entity end ================"
                )
            }
        }
    }

    /**
     * [decodeFromSVGAFileCacheKey] 发现缓存文件已被并发路径删除时的兜底：直接重走下载路径。
     * 用 isRestart = true 阻断取消时的递归重启。alias 非 URL 时（理论上不会发生）走错误通知。
     */
    private fun fallbackToDownloadFromCache(
        cacheKey: String,
        alias: String?,
        config: SVGAConfig,
        callback: ParseCompletion?,
        playCallback: PlayCallback?,
        memoryCacheKey: String?
    ) {
        try {
            //兜底发起方自身（命中缓存分支的外层 Job）仍占着 in-flight，不清掉则
            //下方 joinOrStart 把自己判为等待者：下载永不启动、该 URL 之后所有加载永久卡死
            SVGADiskLoadingQueue.clearInFlight(cacheKey)
            startDecodeFromURL(
                URL(alias ?: ""),
                config,
                callback,
                playCallback,
                memoryCacheKey,
                isRestart = true
            )
        } catch (e: Exception) {
            notifyQueueError(memoryCacheKey, callback)
            invokeErrorCallback(e, callback, alias)
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
                    if (!SVGAFileCache.buildCacheDir(cacheKey).exists() || isUnzipping) {
                        synchronized(fileLock) {
                            if (!SVGAFileCache.buildCacheDir(cacheKey).exists()) {
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
                    InflaterInputStream(inputStream).use { inflaterInputStream ->
                        val entity = MovieEntity.ADAPTER.decode(inflaterInputStream)
                        val videoItem = SVGAVideoEntity(
                            entity,
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
                }
            } catch (e: java.lang.Exception) {
                notifyQueueError(memoryCacheKey, callback)
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
        LogUtils.info(TAG, "================ $alias parser complete ================")
        val cacheKey = videoItem.getMemoryCacheKey()
        if (cacheKey.isNullOrEmpty()) {
            handler.post {
                callback?.onComplete(videoItem)
            }
        } else {
            //写缓存与移除等待队列必须原子完成：之前入队的等待者必定被本次通知覆盖，
            //之后到达的调用必定命中缓存走直接回调，不存在两边都漏掉的窗口
            var waiters: List<SVGAMemoryLoadingQueue.SVGAMemoryLoadingItem>? = null
            synchronized(loadStateLock) {
                //存入内存缓存
                SVGAMemoryCache.INSTANCE.putData(cacheKey, videoItem)
                waiters = SVGAMemoryLoadingQueue.removeItem(cacheKey)
            }
            //直接回调与等待队列一并通知。二者角色互斥（加载者不入队、入队者不走直接回调），
            //不能像旧逻辑一样二选一，否则内存缓存命中路径的直接回调会被吞掉
            handler.post {
                callback?.onComplete(videoItem)
                waiters?.forEach {
                    it.callback?.onComplete(videoItem)
                }
            }
        }
        //磁盘下载队列等待者通知：仅 alias 是 URL 且对应 cacheKey 有等待者时生效
        notifyDiskWaiters(alias, videoItem)
    }

    private fun invokeErrorCallback(
        e: Exception,
        callback: ParseCompletion?,
        alias: String?
    ) {
        e.printStackTrace()
        LogUtils.error(TAG, "================ $alias parser error ================")
        //LogUtils.error(TAG, "$alias parse error", e)
        handler.post {
            callback?.onError()
        }
        //磁盘下载队列等待者通知：解析失败时给等待者补发 onError
        notifyDiskWaiters(alias, null)
    }

    /**
     * 通知磁盘下载队列等待者。
     * [entity] 非 null → 解析成功，通知 onComplete；null → 解析失败，通知 onError。
     * alias 非 URL（资产/文件路径）时直接跳过，避免对其他路径产生误伤。
     */
    private fun notifyDiskWaiters(alias: String?, entity: SVGAVideoEntity?) {
        if (alias.isNullOrEmpty()) return
        val url = try {
            URL(alias)
        } catch (e: Exception) {
            return
        }
        val diskCacheKey = SVGAFileCache.buildCacheKey(url)
        val waiters = if (entity != null) {
            SVGADiskLoadingQueue.completeAndTake(diskCacheKey)
        } else {
            SVGADiskLoadingQueue.cancelAndTakeWaiters(diskCacheKey)
        } ?: return
        handler.post {
            if (entity != null) {
                waiters.forEach {
                    if (it.config.frameWidth == entity.requestFrameWidth &&
                        it.config.frameHeight == entity.requestFrameHeight
                    ) {
                        it.callback?.onComplete(entity)
                    } else {
                        //等待者与加载者的解码尺寸不同：共享 entity 会拿到按他人尺寸解码的 bitmap，
                        //改为按自身 config 重走解析（磁盘缓存已就绪，直接命中本地缓存）
                        startDecodeFromURL(
                            url, it.config, it.callback, it.playCallback, null, isRestart = true
                        )
                    }
                }
            } else {
                waiters.forEach { it.callback?.onError() }
            }
        }
    }

    /**
     * 解码失败/取消时移除等待队列，并给排队等待的回调补发 onError，
     * 避免等待方既收不到 onComplete 也收不到 onError 而静默卡死。
     * 直接回调由 invokeErrorCallback 单独通知，这里排除避免双发。
     */
    private fun notifyQueueError(memoryCacheKey: String?, directCallback: ParseCompletion?) {
        memoryCacheKey ?: return
        val itemList = SVGAMemoryLoadingQueue.removeItem(memoryCacheKey) ?: return
        handler.post {
            itemList.forEach { item ->
                if (item.callback !== directCallback) {
                    item.callback?.onError()
                }
            }
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
                val cacheDir = SVGAFileCache.buildCacheDir(cacheKey)
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
                notifyQueueError(memoryCacheKey, callback)
                invokeErrorCallback(e, callback, alias)
            }
        }
    }

    /**
     * 加载内存缓存
     * @return true内部已将数据通过接口返回（缓存命中或已入队等待），不用再加载
     */
    private fun decodeFromMemoryCacheKey(
        memoryCacheKey: String?,
        config: SVGAConfig,
        callback: ParseCompletion?,
        playCallback: PlayCallback?,
        alias: String?
    ): Boolean {
        if (!config.isCacheToMemory || memoryCacheKey.isNullOrEmpty()) { //不使用内存缓存
            return false
        }
        //“读缓存 + 入队”与完成路径的“写缓存 + 移除”使用同一把锁，原子决策：
        //要么命中缓存走直接回调，要么在完成通知发出前成功入队，杜绝两边交错的漏通知
        var cachedEntity: SVGAVideoEntity? = null
        var joinLoading = false
        synchronized(loadStateLock) {
            cachedEntity = SVGAMemoryCache.INSTANCE.getData(memoryCacheKey)
            if (cachedEntity == null) {
                //已有同资源在途加载：入队等待统一通知；否则当前调用是加载者（不入队，走直接回调）
                joinLoading = SVGAMemoryLoadingQueue.enqueue(
                    memoryCacheKey,
                    SVGAMemoryLoadingQueue.SVGAMemoryLoadingItem(callback)
                )
                LogUtils.info(
                    TAG,
                    "decodeFromMemoryCacheKey enqueue $memoryCacheKey, joinLoading = $joinLoading"
                )
            }
        }
        if (cachedEntity != null) {
            LogUtils.info(
                TAG,
                "decodeFromMemoryCacheKey key=$memoryCacheKey"
            )
            cachedEntity?.let { entity ->
                entity.prepare({
                    LogUtils.info(TAG, "decodeFromMemoryCacheKey prepare success")
                    this.invokeCompleteCallback(entity, callback, alias = alias)
                }, playCallback)
            }
            return true
        }
        return joinLoading
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
        val cacheDir = SVGAFileCache.buildCacheDir(cacheKey)
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
            val downloadCacheFile = SVGAFileCache.buildCacheFile(cacheKey)
            if (downloadCacheFile.exists()) {
                downloadCacheFile.delete()
            }
        } catch (e: Exception) {
            LogUtils.error(TAG, "================ unzip error ================")
            LogUtils.error(TAG, "error", e)
            SVGAFileCache.clearDir(cacheDir.absolutePath)
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
