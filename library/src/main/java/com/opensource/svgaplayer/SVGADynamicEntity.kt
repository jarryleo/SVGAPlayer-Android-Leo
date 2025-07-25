package com.opensource.svgaplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.text.BoringLayout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.opensource.svgaplayer.bitmap.SVGABitmapFileDecoder
import com.opensource.svgaplayer.bitmap.SVGABitmapInputStreamDecoder
import com.opensource.svgaplayer.bitmap.SVGABitmapResDecoder
import com.opensource.svgaplayer.coroutine.SvgaCoroutineManager
import com.opensource.svgaplayer.download.BitmapDownloader
import com.opensource.svgaplayer.entities.SVGATextEntity
import com.opensource.svgaplayer.url.UrlDecoderManager
import com.opensource.svgaplayer.utils.BitmapTransformation
import com.opensource.svgaplayer.utils.SourceUtil
import com.opensource.svgaplayer.utils.roundToIntSafe
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import java.util.concurrent.ConcurrentHashMap

/**
 * Created by cuiminghui on 2017/3/30.
 */
class SVGADynamicEntity(val context: Context) {
    internal var invalidateCallback: () -> Unit = {}

    internal var dynamicHidden: ConcurrentHashMap<String, Boolean> = ConcurrentHashMap()

    private var dynamicImage: ConcurrentHashMap<String, Bitmap> = ConcurrentHashMap()

    private var dynamicImageUrl: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    private var dynamicImageResId: ConcurrentHashMap<String, Int> = ConcurrentHashMap()

    private var dynamicBitmapTransformation: ConcurrentHashMap<String, BitmapTransformation> =
        ConcurrentHashMap()

    private var dynamicImageJob: ConcurrentHashMap<String, Job> = ConcurrentHashMap()

    internal var dynamicText: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    internal var dynamicTextScale: ConcurrentHashMap<String, Float> = ConcurrentHashMap()

    internal var dynamicTextPaint: ConcurrentHashMap<String, TextPaint> = ConcurrentHashMap()

    internal var dynamicStaticLayoutText: ConcurrentHashMap<String, StaticLayout> =
        ConcurrentHashMap()

    internal var dynamicBoringLayoutText: ConcurrentHashMap<String, BoringLayout> =
        ConcurrentHashMap()

    internal var dynamicDrawer: ConcurrentHashMap<String, (canvas: Canvas, frameIndex: Int) -> Boolean> =
        ConcurrentHashMap()

    //点击事件回调map
    internal var mClickMap: ConcurrentHashMap<String, IntArray> = ConcurrentHashMap()
    internal var dynamicIClickArea: ConcurrentHashMap<String, IClickAreaListener> =
        ConcurrentHashMap()

    internal var dynamicDrawerSized: ConcurrentHashMap<String, (canvas: Canvas, frameIndex: Int, width: Int, height: Int) -> Boolean> =
        ConcurrentHashMap()

    internal var isTextDirty = false

    /** 判断是否由SVGA内部自动释放Bitmap（使用Glide时候如果SVGA内部释放掉Bitmap会造成崩溃） */
    private var isAutoRecycleBitmap = true

    fun setHidden(value: Boolean, forKey: String) {
        this.dynamicHidden[forKey] = value
    }

    fun setDynamicImage(bitmap: Bitmap, forKey: String) {
        isAutoRecycleBitmap = false //外部设置的bitmap不自动释放
        this.dynamicImage[forKey] = bitmap
    }

    /**
     * 判断key是否是动态图
     */
    fun isDynamicImage(forKey: String): Boolean {
        return dynamicImageUrl.containsKey(forKey)
                || dynamicImageResId.containsKey(forKey)
                || dynamicImage.containsKey(forKey)
    }

    /**
     * 判断是否是动态文本
     */
    fun isDynamicText(forKey: String): Boolean {
        return dynamicText.containsKey(forKey)
                || dynamicStaticLayoutText.containsKey(forKey)
                || dynamicBoringLayoutText.containsKey(forKey)
    }

    /**
     * 判断图片是否已经加载完成
     */
    fun hasValidBitmap(key: String): Boolean {
        return dynamicImage[key]?.isRecycled == false
    }

    /**
     * 从网络加载图片
     */
    @JvmOverloads
    fun setDynamicImage(
        url: String,
        forKey: String,
        bitmapTransformation: BitmapTransformation? = null
    ) {
        dynamicImageUrl[forKey] = url
        bitmapTransformation?.let {
            dynamicBitmapTransformation[forKey] = bitmapTransformation
        }
    }

    /**
     * 从资源id加载图片
     */
    @JvmOverloads
    fun setDynamicImage(
        resId: Int,
        forKey: String,
        bitmapTransformation: BitmapTransformation? = null
    ) {
        dynamicImageResId[forKey] = resId
        bitmapTransformation?.let {
            dynamicBitmapTransformation[forKey] = bitmapTransformation
        }
    }

    fun requestImage(forKey: String, width: Int, height: Int): Bitmap? {
        val value = dynamicImage[forKey]
        if (value != null) {
            if (!value.isRecycled) {
                return value
            } else {
                dynamicImage.remove(forKey)
            }
        }
        val url = dynamicImageUrl[forKey]
        val resId = dynamicImageResId[forKey]
        if (url != null || resId != null) {
            dynamicImageJob[forKey]?.let {
                if (it.isActive) {
                    return null
                } else {
                    dynamicImageJob.remove(forKey)
                }
            }
            val job = SvgaCoroutineManager.launchIo {
                var bitmap: Bitmap? = null
                if (url != null) {
                    val realUrl =
                        UrlDecoderManager.getUrlDecoder().decodeImageUrl(url, width, height)
                    bitmap = if (SourceUtil.isUrl(realUrl)) {
                        BitmapDownloader.downloadBitmap(realUrl, width, height)
                    } else if (SourceUtil.isFilePath(realUrl)) {
                        SVGABitmapFileDecoder.decodeBitmapFrom(realUrl, width, height)
                    } else {
                        SVGABitmapInputStreamDecoder.decodeBitmapFrom(
                            context.assets.open(realUrl),
                            width,
                            height
                        )
                    }
                } else if (resId != null) {
                    bitmap = SVGABitmapResDecoder(context).decodeBitmapFrom(resId, width, height)
                }

                if (bitmap != null && isActive) {
                    val bitmapTransformation = dynamicBitmapTransformation[forKey]
                    if (bitmapTransformation != null) {
                        val transformationBitmap =
                            bitmapTransformation.transform(bitmap, width, height)
                        dynamicImage[forKey] = transformationBitmap ?: bitmap
                    } else {
                        dynamicImage[forKey] = bitmap
                    }
                    dynamicImageJob.remove(forKey)
                    invalidateCallback.invoke()
                }
            }
            dynamicImageJob[forKey] = job
        }
        return null
    }

    fun setDynamicText(text: String, textPaint: TextPaint, forKey: String) {
        this.isTextDirty = true
        this.dynamicText[forKey] = text
        this.dynamicTextPaint[forKey] = textPaint
    }

    fun setDynamicText(layoutText: StaticLayout, forKey: String) {
        this.isTextDirty = true
        this.dynamicStaticLayoutText[forKey] = layoutText
    }

    fun setDynamicText(layoutText: BoringLayout, forKey: String) {
        this.isTextDirty = true
        BoringLayout.isBoring(layoutText.text, layoutText.paint)?.let {
            this.dynamicBoringLayoutText[forKey] = layoutText
        }
    }

    fun setDynamicText(forKey: String, textEntity: SVGATextEntity) {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        textPaint.color = textEntity.textColor
        textPaint.textSize = textEntity.textSize
        textPaint.typeface = textEntity.typeface
        dynamicTextScale[forKey] = textEntity.scale
        val text = textEntity.text
        val width = if (textEntity.ellipsize == TextUtils.TruncateAt.MARQUEE) {
            textPaint.measureText(text).roundToIntSafe()
        } else {
            Int.MAX_VALUE
        }
        val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(
                text,
                0,
                text.length,
                textPaint,
                width
            )
                .setAlignment(textEntity.alignment)
                .setMaxLines(textEntity.maxLines)
                .setEllipsize(textEntity.ellipsize)
                .build()
        } else {
            StaticLayout(
                text,
                0,
                text.length,
                textPaint,
                width,
                textEntity.alignment,
                textEntity.spacingMultiplier,
                textEntity.spacingAdd,
                false,
                textEntity.ellipsize,
                width
            )
        }
        setDynamicText(layout, forKey)
    }

    fun setDynamicDrawer(drawer: (canvas: Canvas, frameIndex: Int) -> Boolean, forKey: String) {
        this.dynamicDrawer[forKey] = drawer
    }

    fun setClickArea(clickKey: List<String>) {
        for (itemKey in clickKey) {
            dynamicIClickArea[itemKey] = object : IClickAreaListener {
                override fun onResponseArea(key: String, x0: Int, y0: Int, x1: Int, y1: Int) {
                    mClickMap.let {
                        if (it[key] == null) {
                            it[key] = intArrayOf(x0, y0, x1, y1)
                        } else {
                            it[key]?.let { arr ->
                                arr[0] = x0
                                arr[1] = y0
                                arr[2] = x1
                                arr[3] = y1
                            }
                        }
                    }
                }
            }
        }
    }

    fun setClickArea(clickKey: String) {
        dynamicIClickArea[clickKey] = object : IClickAreaListener {
            override fun onResponseArea(key: String, x0: Int, y0: Int, x1: Int, y1: Int) {
                mClickMap.let { clickKey ->
                    if (clickKey[key] == null) {
                        clickKey[key] = intArrayOf(x0, y0, x1, y1)
                    } else {
                        clickKey[key]?.let {
                            it[0] = x0
                            it[1] = y0
                            it[2] = x1
                            it[3] = y1
                        }
                    }
                }
            }
        }
    }

    fun setDynamicDrawerSized(
        drawer: (canvas: Canvas, frameIndex: Int, width: Int, height: Int) -> Boolean,
        forKey: String
    ) {
        this.dynamicDrawerSized[forKey] = drawer
    }

    fun clearDynamicObjects() {
        this.dynamicImageJob.forEach {
            if (it.value.isActive) {
                it.value.cancel()
            }
        }
        this.isTextDirty = true
        this.dynamicHidden.clear()
        if (isAutoRecycleBitmap) {
            this.dynamicImage.filter {
                !it.value.isRecycled
            }.forEach {
                it.value.recycle()
            }
        }
        this.dynamicImage.clear()
        this.dynamicImageUrl.clear()
        this.dynamicImageResId.clear()
        this.dynamicImageJob.clear()
        this.dynamicText.clear()
        this.dynamicTextScale.clear()
        this.dynamicTextPaint.clear()
        this.dynamicStaticLayoutText.clear()
        this.dynamicBoringLayoutText.clear()
        this.dynamicDrawer.clear()
        this.dynamicIClickArea.clear()
        this.mClickMap.clear()
        this.dynamicDrawerSized.clear()
    }

    /**
     * 获取svga动态图占用内存大小
     */
    fun getMemorySize(): Long {
        return dynamicImage.values.sumOf {
            it.width * it.height * 4
        }.toLong()
    }
}