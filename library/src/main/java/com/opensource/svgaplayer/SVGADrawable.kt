package com.opensource.svgaplayer

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.opensource.svgaplayer.cache.SVGAMemoryCache
import com.opensource.svgaplayer.drawer.SVGACanvasDrawer

class SVGADrawable(
    val videoItem: SVGAVideoEntity,
    val dynamicItem: SVGADynamicEntity?
) :
    Drawable() {

    var cleared = true
        internal set(value) {
            if (field == value) {
                return
            }
            field = value
            invalidateSelf()
        }

    var currentFrame = 0
        internal set(value) {
            if (field == value) {
                return
            }
            field = value
            invalidateSelf()
        }

    var scaleType: ImageView.ScaleType = ImageView.ScaleType.MATRIX

    private val drawer = SVGACanvasDrawer(videoItem, dynamicItem)

    override fun draw(canvas: Canvas) {
        if (cleared) {
            return
        }
        canvas.let {
            drawer.drawFrame(it, currentFrame, scaleType)
        }
    }

    override fun setAlpha(alpha: Int) {

    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int {
        return PixelFormat.TRANSPARENT
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {

    }

    fun resume() {
        videoItem.audioList.forEach { audio ->
            audio.playID?.let {
                videoItem.soundPool?.resume(it)
            }
        }
    }

    fun pause() {
        videoItem.audioList.forEach { audio ->
            audio.playID?.let {
                videoItem.soundPool?.pause(it)
            }
        }
    }

    fun stop() {
        videoItem.audioList.forEach { audio ->
            audio.playID?.let {
                videoItem.soundPool?.stop(it)
            }
        }
    }

    fun clear() {
        unloadSound()
        //判断是否缓存数据
        videoItem.getMemoryCacheKey()?.apply {
            SVGAMemoryCache.INSTANCE.putData(this, videoItem)
        } ?: videoItem.clear()
        //清除绘制缓存
        drawer.clear()
    }

    fun cacheToMemory() {
        videoItem.getMemoryCacheKey()?.apply {
            SVGAMemoryCache.INSTANCE.putData(this, videoItem)
        }
    }

    /**
     * 释放声音资源
     */
    fun unloadSound() {
        videoItem.audioList.forEach { audio ->
            audio.playID?.let {
                videoItem.soundPool?.unload(it)
            }
            audio.playID = null
        }
    }
}