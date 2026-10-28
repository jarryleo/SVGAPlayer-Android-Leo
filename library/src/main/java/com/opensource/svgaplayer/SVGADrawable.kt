package com.opensource.svgaplayer

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.opensource.svgaplayer.drawer.SVGACanvasDrawer

class SVGADrawable(
    val videoItem: SVGAVideoEntity,
    val dynamicItem: SVGADynamicEntity?
) : Drawable() {

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

    fun updateDynamicItem(dynamicItem: SVGADynamicEntity){
        drawer.dynamicItem?.clearDynamicObjects()
        drawer.dynamicItem = dynamicItem
    }

    override fun setAlpha(alpha: Int) {

    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int {
        return PixelFormat.TRANSPARENT
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {

    }

    fun setVolume(volume: Float) {
        val fixVolume = volume.coerceIn(0f, 1f)
        drawer.setVolume(fixVolume, fixVolume)
        videoItem.audioList.forEach { audio ->
            audio.playID?.let {
                videoItem.soundPool?.setVolume(it, fixVolume, fixVolume)
            }
        }
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
        //实体的回收统一交给 SVGAImageView.releaseVideoItem（末次释放者负责）：
        //磁盘去重队列会把同一实体派发给多个 view 共享，这里若按旧逻辑对无内存缓存 key 的
        //实体直接 clear，任一 view 先播完/被回收都会把其他还在播的 view 的 bitmap 一起回收
        drawer.clear()
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