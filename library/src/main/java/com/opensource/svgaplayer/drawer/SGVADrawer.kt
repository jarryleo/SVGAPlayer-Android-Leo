package com.opensource.svgaplayer.drawer

import android.graphics.Canvas
import android.widget.ImageView
import com.opensource.svgaplayer.SVGAVideoEntity
import com.opensource.svgaplayer.entities.SVGAVideoSpriteFrameEntity
import com.opensource.svgaplayer.utils.Pools
import com.opensource.svgaplayer.utils.SVGAScaleInfo
import kotlin.math.max

/**
 * Created by cuiminghui on 2017/3/29.
 */

internal open class SGVADrawer(val videoItem: SVGAVideoEntity) {

    val scaleInfo = SVGAScaleInfo()

    private val spritePool = Pools.SimplePool<SVGADrawerSprite>(max(1, videoItem.spriteList.size))

    /**
     * 每帧复用的 sprite 列表，避免逐帧分配 ArrayList。
     * 返回的是内部复用实例，调用方须在下一次 requestFrameSprites 前消费完毕（drawFrame 内同步用完）
     */
    private val frameSprites = ArrayList<SVGADrawerSprite>(max(1, videoItem.spriteList.size))

    inner class SVGADrawerSprite(
        var _matteKey: String? = null,
        var _imageKey: String? = null,
        var _frameEntity: SVGAVideoSpriteFrameEntity? = null
    ) {
        val matteKey get() = _matteKey
        val imageKey get() = _imageKey
        val frameEntity get() = _frameEntity!!
    }

    internal fun requestFrameSprites(frameIndex: Int): List<SVGADrawerSprite> {
        val sprites = frameSprites
        sprites.clear()
        val spriteList = videoItem.spriteList
        for (index in spriteList.indices) {
            val sprite = spriteList[index]
            val frames = sprite.frames ?: continue
            if (frameIndex < 0 || frameIndex >= frames.size) continue
            val imageKey = sprite.imageKey ?: continue
            if (!imageKey.endsWith(".matte") && frames[frameIndex].alpha <= 0.0) continue
            val drawerSprite = spritePool.acquire() ?: SVGADrawerSprite()
            drawerSprite._matteKey = sprite.matteKey
            drawerSprite._imageKey = sprite.imageKey
            drawerSprite._frameEntity = frames[frameIndex]
            sprites.add(drawerSprite)
        }
        return sprites
    }

    internal fun releaseFrameSprites(sprites: List<SVGADrawerSprite>) {
        sprites.forEach { spritePool.release(it) }
    }

    open fun drawFrame(canvas: Canvas, frameIndex: Int, scaleType: ImageView.ScaleType) {
        scaleInfo.performScaleType(
            canvas.width.toFloat(),
            canvas.height.toFloat(),
            videoItem.videoSize.width.toFloat(),
            videoItem.videoSize.height.toFloat(),
            scaleType
        )
    }

}
