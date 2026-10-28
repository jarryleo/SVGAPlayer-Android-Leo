package com.opensource.svgaplayer

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import com.opensource.svgaplayer.bitmap.SVGABitmapByteArrayDecoder
import com.opensource.svgaplayer.bitmap.SVGABitmapFileDecoder
import com.opensource.svgaplayer.cache.SVGAFileCache
import com.opensource.svgaplayer.coroutine.SvgaCoroutineManager
import com.opensource.svgaplayer.entities.SVGAAudioEntity
import com.opensource.svgaplayer.entities.SVGAVideoSpriteEntity
import com.opensource.svgaplayer.proto.AudioEntity
import com.opensource.svgaplayer.proto.MovieEntity
import com.opensource.svgaplayer.proto.MovieParams
import com.opensource.svgaplayer.utils.SVGARect
import com.opensource.svgaplayer.utils.log.LogUtils
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Created by PonyCui on 16/6/18.
 */
class SVGAVideoEntity {

    private val TAG = "SVGAVideoEntity"

    var antiAlias = true
    var movieItem: MovieEntity? = null

    var videoSize = SVGARect(0.0, 0.0, 0.0, 0.0)
        private set

    var FPS = 15
        private set

    var frames: Int = 0
        private set

    internal var spriteList: List<SVGAVideoSpriteEntity> = emptyList()
    internal var audioList: List<SVGAAudioEntity> = emptyList()
    internal var soundPool: SoundPool? = null

    //主体 bitmap 内存大户
    internal var imageMap = HashMap<String, Bitmap>()
    private var mCacheDir: File
    private var mFrameHeight = 0
    private var mFrameWidth = 0

    /** 解码时请求的尺寸（来自 SVGAConfig），磁盘下载队列据此判断等待者能否共享本实体 */
    internal val requestFrameWidth: Int get() = mFrameWidth
    internal val requestFrameHeight: Int get() = mFrameHeight

    //这里可能会持有外部View，如果内存缓存会导致泄漏
    private var mPlayCallback: SVGAParser.PlayCallback? = null
    private val job = SupervisorJob()

    //加载完成回调
    private var mCallback: AtomicReference<PrepareCallback?> = AtomicReference(null)

    /** 音频装配完成标记：置位后再次 prepare（内存缓存命中复用同一实体）直接回调，不重复装配 */
    @Volatile
    private var audioPrepared = false

    /** 内存缓存Key */
    private var mMemoryCacheKey: String? = null

    /** view 绑定计数：>0 表示仍被某个 view 的 drawable 持有，内存缓存驱逐/替换时必须跳过释放 */
    private val refCount = AtomicInteger(0)

    /**
     * 绑定/释放状态锁：串行化 acquireIfUsable 的"可用检查 + 计数 +1"与
     * markReleasedIfUnused 的"无绑定判定 + 标记释放"。不加锁时存在竞态：
     * 驱逐线程判定 refCount==0 决定回收的瞬间，主线程恰好 acquire 并绑定，
     * 回收落在展示中的 drawable 上，下一帧绘制已回收 bitmap 崩溃
     */
    private val lifecycleLock = Any()

    /** bitmap 已释放标记：clear() 后置位，防止已回收实体被再次绑定或写回缓存 */
    @Volatile
    internal var isReleased = false
        private set

    /**
     * 图片解码不完整（构造期异常/OOM 被吞）标记：实体仍可播放，但 imageMap 残缺、
     * 字节记账失真，不应进入内存缓存被复用
     */
    internal var isDecodeIncomplete = false
        private set

    constructor(json: JSONObject, cacheDir: File) : this(json, cacheDir, 0, 0, null)

    constructor(
        json: JSONObject,
        cacheDir: File,
        frameWidth: Int,
        frameHeight: Int,
        memoryCacheKey: String?
    ) {
        mFrameWidth = frameWidth
        mFrameHeight = frameHeight
        mCacheDir = cacheDir
        mMemoryCacheKey = memoryCacheKey
        val movieJsonObject = json.optJSONObject("movie") ?: return
        setupByJson(movieJsonObject)
        try {
            parserImages(json)
        } catch (e: Exception) {
            e.printStackTrace()
            isDecodeIncomplete = true
        } catch (e: OutOfMemoryError) {
            e.printStackTrace()
            isDecodeIncomplete = true
        }
        resetSprites(json)
    }

    private fun setupByJson(movieObject: JSONObject) {
        movieObject.optJSONObject("viewBox")?.let { viewBoxObject ->
            val width = viewBoxObject.optDouble("width", 0.0)
            val height = viewBoxObject.optDouble("height", 0.0)
            videoSize = SVGARect(0.0, 0.0, width, height)
        }
        FPS = movieObject.optInt("fps", 20)
        frames = movieObject.optInt("frames", 0)
    }

    constructor(entity: MovieEntity, cacheDir: File) : this(entity, cacheDir, 0, 0, null)

    constructor(
        entity: MovieEntity,
        cacheDir: File,
        frameWidth: Int,
        frameHeight: Int,
        memoryCacheKey: String?
    ) {
        this.mFrameWidth = frameWidth
        this.mFrameHeight = frameHeight
        this.mCacheDir = cacheDir
        this.mMemoryCacheKey = memoryCacheKey
        this.movieItem = entity
        entity.params?.let(this::setupByMovie)
        try {
            parserImages(entity)
        } catch (e: Exception) {
            e.printStackTrace()
            isDecodeIncomplete = true
        } catch (e: OutOfMemoryError) {
            e.printStackTrace()
            isDecodeIncomplete = true
        }
        resetSprites(entity)
    }

    private fun setupByMovie(movieParams: MovieParams) {
        val width = (movieParams.viewBoxWidth ?: 0.0f).toDouble()
        val height = (movieParams.viewBoxHeight ?: 0.0f).toDouble()
        videoSize = SVGARect(0.0, 0.0, width, height)
        FPS = movieParams.fps ?: 20
        frames = movieParams.frames ?: 0
    }

    private fun prepareLoadSuccessCallback() {
        //原子"取并清"：声音加载完成回调与3秒兜底延迟可能产生双完成信号，保证回调只触发一次
        mCallback.getAndSet(null)?.invoke()
    }

    internal fun prepare(callback: () -> Unit, playCallback: SVGAParser.PlayCallback?) {
        //已装配过（内存缓存命中复用同一实体）：直接回调。重复 setupAudios 会新建 SoundPool
        //覆盖旧的（旧实例未 release，native 资源延迟释放），且带音频动画每次命中都要重等 3 秒兜底
        if (audioPrepared) {
            callback.invoke()
            return
        }
        //判断空位与注册必须原子：并发命中内存缓存时，两个调用交错 get/set 会互相覆盖回调，
        //被覆盖的一方永远收不到完成通知（列表批量加载同一动画时表现为个别不播放）
        if (!mCallback.compareAndSet(null, callback)) {
            //上一个回调未执行完毕，当前回调直接执行
            callback.invoke()
            return
        }
        mPlayCallback = playCallback
        val item = movieItem
        if (item == null) {
            LogUtils.debug(TAG, "movieItem = null")
            //标记须在消费回调前置位：否则窗口内并发的 prepare 会经 CAS 成功再次装配
            audioPrepared = true
            prepareLoadSuccessCallback()
        } else {
            SvgaCoroutineManager.launchIo(childJob = job) {
                setupAudios(item) {
                    audioPrepared = true
                    //音频装配是 movieItem 的最后一个消费方，用完即丢源实体：
                    //movieItem.images 持有整个 SVGA 的压缩图片/音频字节（可能上百 MB），
                    //实体入内存缓存后保留它等于在 bitmap 记账之外无上限驻留源文件数据
                    movieItem = null
                    prepareLoadSuccessCallback()
                }
            }
        }
    }

    private fun parserImages(json: JSONObject) {
        val imgJson = json.optJSONObject("images") ?: return
        imgJson.keys().forEach { imgKey ->
            val filePath = generateBitmapFilePath(imgJson[imgKey].toString(), imgKey)
            if (filePath.isEmpty()) {
                //引用的图片文件缺失（解压未完成/目录残缺）：不能就此中止全部图片解析
                //（原实现的裸 return 会让首个缺图连带其余图片都不解析），
                //并标记解码不完整，阻止 imageMap 残缺的实体作为成功结果进入内存缓存被长期复用
                isDecodeIncomplete = true
                return@forEach
            }
            val bitmapKey = imgKey.replace(".matte", "")
            val bitmap = createBitmap(filePath)
            if (bitmap != null) {
                imageMap[bitmapKey] = bitmap
            } else {
                //文件存在但解码失败（含解码器吞掉的异常/OOM）：同样按残缺处理，禁止入缓存复用
                isDecodeIncomplete = true
            }
        }
    }

    private fun generateBitmapFilePath(imgName: String, imgKey: String): String {
        val path = mCacheDir.absolutePath + "/" + imgName
        val path1 = "$path.png"
        val path2 = mCacheDir.absolutePath + "/" + imgKey + ".png"

        return when {
            File(path).exists() -> path
            File(path1).exists() -> path1
            File(path2).exists() -> path2
            else -> ""
        }
    }

    private fun createBitmap(filePath: String): Bitmap? {
        return SVGABitmapFileDecoder.decodeBitmapFrom(filePath, mFrameWidth, mFrameHeight)
    }

    private fun parserImages(obj: MovieEntity) {
        obj.images?.entries?.forEach { entry ->
            val byteArray = entry.value.toByteArray()
            if (byteArray.count() < 4) {
                return@forEach
            }
            val fileTag = byteArray.slice(IntRange(0, 3))
            if (fileTag[0].toInt() == 73 && fileTag[1].toInt() == 68 && fileTag[2].toInt() == 51) {
                return@forEach
            }
            val filePath = generateBitmapFilePath(entry.value.utf8(), entry.key)
            createBitmap(byteArray, filePath)?.let { bitmap ->
                LogUtils.debug(TAG, "createBitmap key = ${entry.key}")
                imageMap[entry.key] = bitmap
            }
        }
    }

    private fun createBitmap(byteArray: ByteArray, filePath: String): Bitmap? {
        val bitmap =
            SVGABitmapByteArrayDecoder.decodeBitmapFrom(byteArray, mFrameWidth, mFrameHeight)
        return bitmap ?: createBitmap(filePath)
    }

    private fun resetSprites(json: JSONObject) {
        val mutableList: MutableList<SVGAVideoSpriteEntity> = mutableListOf()
        json.optJSONArray("sprites")?.let { item ->
            for (i in 0 until item.length()) {
                item.optJSONObject(i)?.let { entryJson ->
                    mutableList.add(SVGAVideoSpriteEntity(entryJson))
                }
            }
        }
        spriteList = mutableList.toList()
    }

    private fun resetSprites(entity: MovieEntity) {
        spriteList = entity.sprites?.map {
            return@map SVGAVideoSpriteEntity(it)
        } ?: listOf()
    }

    private suspend fun setupAudios(entity: MovieEntity, completionBlock: () -> Unit) {
        if (entity.audios.isNullOrEmpty()) {
            completionBlock.invoke()
            return
        }
        setupSoundPool(entity, completionBlock)
        val audiosFileMap = generateAudioFileMap(entity)
        //repair when audioEntity error can not callback
        //如果audiosFileMap为空 soundPool?.load 不会走 导致 setOnLoadCompleteListener 不会回调 导致外层prepare不回调卡住
        if (audiosFileMap.isEmpty()) {
            completionBlock.invoke()
            return
        }
        this.audioList = entity.audios.map { audio ->
            return@map createSvgaAudioEntity(audio, audiosFileMap)
        }
        delay(3000) //如果加载声音回调3秒没有反应，则兜底返回预加载成功，保证动画执行
        completionBlock.invoke()
    }

    private fun createSvgaAudioEntity(
        audio: AudioEntity,
        audiosFileMap: HashMap<String, File>
    ): SVGAAudioEntity {
        val item = SVGAAudioEntity(audio)
        val startTime = (audio.startTime ?: 0).toDouble()
        val totalTime = (audio.totalTime ?: 0).toDouble()
        if (totalTime.toInt() == 0) {
            mPlayCallback = null
            // 除数不能为 0
            return item
        }
        // 直接回调文件,后续播放都不走
        mPlayCallback?.let {
            val fileList: MutableList<File> = ArrayList()
            audiosFileMap.forEach { entity ->
                fileList.add(entity.value)
            }
            it.onPlay(fileList)
            prepareLoadSuccessCallback()
            mPlayCallback = null
            return item
        }

        try {
            audiosFileMap[audio.audioKey]?.let { file ->
                FileInputStream(file).use {
                    val length = it.available().toDouble()
                    val offset = ((startTime / totalTime) * length).toLong()
                    item.soundID = soundPool?.load(it.fd, offset, length.toLong(), 1)
                    LogUtils.debug(
                        "SVGAParser",
                        "audioKey = ${item.audioKey} soundID = ${item.soundID}"
                    )
                }
            }
        } catch (e: Exception) {
            LogUtils.error("SVGAParser", e)
            prepareLoadSuccessCallback()
        }
        return item
    }

    private fun generateAudioFile(audioCache: File, value: ByteArray): File {
        audioCache.createNewFile()
        FileOutputStream(audioCache).use {
            it.write(value)
        }
        return audioCache
    }

    private fun generateAudioFileMap(entity: MovieEntity): HashMap<String, File> {
        val audiosDataMap = generateAudioMap(entity)
        val audiosFileMap = HashMap<String, File>()
        if (audiosDataMap.count() > 0) {
            audiosDataMap.forEach {
                val audioCache = SVGAFileCache.buildAudioFile(it.key)
                audiosFileMap[it.key] =
                    audioCache.takeIf { file -> file.exists() } ?: generateAudioFile(
                        audioCache,
                        it.value
                    )
            }
        }
        return audiosFileMap
    }

    private fun generateAudioMap(entity: MovieEntity): HashMap<String, ByteArray> {
        val audiosDataMap = HashMap<String, ByteArray>()
        entity.images?.entries?.forEach {
            val imageKey = it.key
            val byteArray = it.value.toByteArray()
            if (byteArray.count() < 4) {
                return@forEach
            }
            val fileTag = byteArray.slice(IntRange(0, 3))
            if (fileTag[0].toInt() == 73 && fileTag[1].toInt() == 68 && fileTag[2].toInt() == 51) {
                audiosDataMap[imageKey] = byteArray
            } else if (fileTag[0].toInt() == -1 && fileTag[1].toInt() == -5 && fileTag[2].toInt() == -108) {
                audiosDataMap[imageKey] = byteArray
            }
        }
        return audiosDataMap
    }

    private fun setupSoundPool(entity: MovieEntity, completionBlock: () -> Unit) {
        var soundLoaded = 0
        soundPool = generateSoundPool(entity)
        LogUtils.info("SVGAParser", "pool_start")
        if (soundPool == null) {
            LogUtils.info("SVGAParser", "pool_null")
            completionBlock()
            return
        }
        //这里不一定会回调，导致动画不会播放，需要优化
        soundPool?.setOnLoadCompleteListener { _, _, _ ->
            LogUtils.info("SVGAParser", "pool_complete")
            soundLoaded++
            if (soundLoaded >= entity.audios.count()) {
                completionBlock()
            }
        }
    }

    private fun generateSoundPool(entity: MovieEntity): SoundPool? {
        return try {
            if (Build.VERSION.SDK_INT >= 21) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
                SoundPool.Builder().setAudioAttributes(attributes)
                    .setMaxStreams(12.coerceAtMost(entity.audios.count()))
                    .build()
            } else {
                SoundPool(12.coerceAtMost(entity.audios.count()), AudioManager.STREAM_MUSIC, 0)
            }
        } catch (e: Exception) {
            LogUtils.error(TAG, e)
            null
        }
    }

    fun clear() {
        LogUtils.debug(TAG, "clear size = ${getMemorySize()}")
        isReleased = true
        job.cancel()
        soundPool?.release()
        soundPool = null
        audioList = emptyList()
        spriteList.forEach {
            it.clear()
        }
        spriteList = emptyList()
        imageMap.filter {
            !it.value.isRecycled
        }.forEach {
            it.value.recycle()
        }
        imageMap.clear()
        mCallback.set(null)
        movieItem = null
    }

    /**
     * view 绑定本实体时调用（setVideoItem）："可用性检查 + 引用计数 +1"原子完成。
     * @return false：实体已释放（bitmap 已回收），不可绑定，调用方应放弃展示
     */
    internal fun acquireIfUsable(): Boolean {
        synchronized(lifecycleLock) {
            if (isReleased) return false
            refCount.incrementAndGet()
            return true
        }
    }

    /**
     * view 归还本实体时调用（clear）。
     * @return true：本次为末次释放，实体已无任何 view 持有，由调用方决定回收或归还缓存
     */
    internal fun release(): Boolean = refCount.decrementAndGet() <= 0

    /**
     * 驱逐路径专用："无 view 绑定判定 + isReleased 标记"原子完成，与 [acquireIfUsable] 互斥。
     * 标记成功后迟到的 acquireIfUsable 会因 isReleased 而失败，不再绑定到即将回收的实体上。
     * @return true：实体无绑定且未释放，调用方可安全执行 clear() 回收 bitmap
     */
    internal fun markReleasedIfUnused(): Boolean {
        synchronized(lifecycleLock) {
            if (isReleased || refCount.get() > 0) return false
            isReleased = true
            return true
        }
    }

    fun getMemoryCacheKey(): String? {
        return mMemoryCacheKey
    }

    /**
     * 获取svga主帧占用内存大小
     */
    fun getMemorySize(): Long {
        //解码 bitmap：allocationByteCount 为解码后的真实字节数（RGB_565 每像素 2 字节），
        //原 width*height*4 按 ARGB_8888 计算高估一倍
        val bitmapBytes = imageMap.values.sumOf { bitmap ->
            runCatching { bitmap.allocationByteCount.toLong() }.getOrDefault(0L)
        }
        //proto 源压缩字节：音频装配完成后会被丢弃（置 null），此口径仅覆盖装配完成前的
        //在途窗口，防止超预算判定与缓存记账漏算这段时间内的真实占用
        val sourceBytes = movieItem?.images?.values?.sumOf { it.size.toLong() } ?: 0L
        return bitmapBytes + sourceBytes
    }
}

typealias PrepareCallback = () -> Unit

