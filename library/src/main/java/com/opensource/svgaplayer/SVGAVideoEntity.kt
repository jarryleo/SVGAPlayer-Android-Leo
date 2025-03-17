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

    //这里可能会持有外部View，如果内存缓存会导致泄漏
    private var mPlayCallback: SVGAParser.PlayCallback? = null
    private val job = SupervisorJob()

    //加载完成回调
    private var mCallback: AtomicReference<PrepareCallback?> = AtomicReference(null)

    /** 内存缓存Key */
    private var mMemoryCacheKey: String? = null

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
        } catch (e: OutOfMemoryError) {
            e.printStackTrace()
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
        } catch (e: OutOfMemoryError) {
            e.printStackTrace()
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
        SvgaCoroutineManager.launchMain(childJob = job) {
            mCallback.get()?.invoke()
            mCallback.set(null)
        }
    }

    internal fun prepare(callback: () -> Unit, playCallback: SVGAParser.PlayCallback?) {
        mCallback.set(callback)
        mPlayCallback = playCallback
        val item = movieItem
        if (item == null) {
            prepareLoadSuccessCallback()
        } else {
            SvgaCoroutineManager.launchIo(childJob = job) {
                setupAudios(item) {
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
                return
            }
            val bitmapKey = imgKey.replace(".matte", "")
            val bitmap = createBitmap(filePath)
            if (bitmap != null) {
                imageMap[bitmapKey] = bitmap
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
                LogUtils.info(TAG, "createBitmap key = ${entry.key}")
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
        FileOutputStream(audioCache).write(value)
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
    }

    fun getMemoryCacheKey(): String? {
        return mMemoryCacheKey
    }

    /**
     * 获取svga主帧占用内存大小
     */
    fun getMemorySize(): Long {
        return imageMap.values.sumOf {
            it.width * it.height * 4
        }.toLong()
    }
}

typealias PrepareCallback = () -> Unit

