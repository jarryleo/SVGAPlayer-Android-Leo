package com.opensource.svgaplayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.text.BoringLayout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.opensource.svgaplayer.coroutine.SvgaCoroutineManager
import com.opensource.svgaplayer.download.BitmapDownloader
import com.opensource.svgaplayer.entities.SVGATextEntity
import kotlin.math.roundToInt

/**
 * Created by cuiminghui on 2017/3/30.
 */
class SVGADynamicEntity {

    internal var dynamicHidden: HashMap<String, Boolean> = hashMapOf()

    private var dynamicImage: HashMap<String, Bitmap> = hashMapOf()

    private var dynamicImageUrl: HashMap<String, String> = hashMapOf()

    internal var dynamicText: HashMap<String, String> = hashMapOf()

    internal var dynamicTextPaint: HashMap<String, TextPaint> = hashMapOf()

    internal var dynamicStaticLayoutText: HashMap<String, StaticLayout> = hashMapOf()

    internal var dynamicBoringLayoutText: HashMap<String, BoringLayout> = hashMapOf()

    internal var dynamicDrawer: HashMap<String, (canvas: Canvas, frameIndex: Int) -> Boolean> =
        hashMapOf()

    //点击事件回调map
    internal var mClickMap: HashMap<String, IntArray> = hashMapOf()
    internal var dynamicIClickArea: HashMap<String, IClickAreaListener> = hashMapOf()

    internal var dynamicDrawerSized: HashMap<String, (canvas: Canvas, frameIndex: Int, width: Int, height: Int) -> Boolean> =
        hashMapOf()

    internal var isTextDirty = false

    /** 判断是否由SVGA内部自动释放Bitmap（使用Glide时候如果SVGA内部释放掉Bitmap会造成崩溃） */
    var isAutoRecycleBitmap = true

    fun setHidden(value: Boolean, forKey: String) {
        this.dynamicHidden[forKey] = value
    }

    fun setDynamicImage(bitmap: Bitmap, forKey: String) {
        this.dynamicImage[forKey] = bitmap
    }

    /**
     * 从网络加载图片
     */
    fun setDynamicImage(url: String, forKey: String) {
        dynamicImageUrl[forKey] = url
    }

    fun requestImage(forKey: String, width: Int, height: Int): Bitmap? {
        if (dynamicImage[forKey] != null) {
            return dynamicImage[forKey]
        }
        val url = dynamicImageUrl[forKey]
        if (url != null) {
            SvgaCoroutineManager.launchIo {
                val bitmap = BitmapDownloader.downloadBitmap(url, width, height)
                if (bitmap != null) {
                    dynamicImage[forKey] = bitmap
                }
            }
        }
        return null
    }

    fun setDynamicText(text: String, textPaint: TextPaint, forKey: String) {
        this.isTextDirty = true
        this.dynamicText.put(forKey, text)
        this.dynamicTextPaint.put(forKey, textPaint)
    }

    fun setDynamicText(layoutText: StaticLayout, forKey: String) {
        this.isTextDirty = true
        this.dynamicStaticLayoutText.put(forKey, layoutText)
    }

    fun setDynamicText(layoutText: BoringLayout, forKey: String) {
        this.isTextDirty = true
        BoringLayout.isBoring(layoutText.text, layoutText.paint)?.let {
            this.dynamicBoringLayoutText.put(forKey, layoutText)
        }
    }

    fun setDynamicText(forKey: String, textEntity: SVGATextEntity) {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        textPaint.color = textEntity.textColor
        textPaint.textSize = textEntity.textSize
        val text = textEntity.text
        val width = if (textEntity.ellipsize == TextUtils.TruncateAt.MARQUEE) {
            textPaint.measureText(text).roundToInt()
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
                            it.put(key, intArrayOf(x0, y0, x1, y1))
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
        dynamicIClickArea.put(clickKey, object : IClickAreaListener {
            override fun onResponseArea(key: String, x0: Int, y0: Int, x1: Int, y1: Int) {
                mClickMap.let {
                    if (it.get(key) == null) {
                        it.put(key, intArrayOf(x0, y0, x1, y1))
                    } else {
                        it.get(key)?.let {
                            it[0] = x0
                            it[1] = y0
                            it[2] = x1
                            it[3] = y1
                        }
                    }
                }
            }
        })
    }

    fun setDynamicDrawerSized(
        drawer: (canvas: Canvas, frameIndex: Int, width: Int, height: Int) -> Boolean,
        forKey: String
    ) {
        this.dynamicDrawerSized[forKey] = drawer
    }

    fun clearDynamicObjects() {
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
        this.dynamicText.clear()
        this.dynamicTextPaint.clear()
        this.dynamicStaticLayoutText.clear()
        this.dynamicBoringLayoutText.clear()
        this.dynamicDrawer.clear()
        this.dynamicIClickArea.clear()
        this.mClickMap.clear()
        this.dynamicDrawerSized.clear()
    }
}