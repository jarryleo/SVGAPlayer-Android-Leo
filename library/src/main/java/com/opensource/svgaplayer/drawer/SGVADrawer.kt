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

    internal data class SVGADrawerSprite(
        val matteKey: String,
        val imageKey: String,
        val frameEntity: SVGAVideoSpriteFrameEntity
    )

    internal fun requestFrameSprites(frameIndex: Int): List<SVGADrawerSprite> {
        return videoItem.spriteList.mapNotNull { sprite ->
            if (frameIndex >= 0 && frameIndex < sprite.frames.size) {
                sprite.imageKey?.let { imageKey ->
                    if (!imageKey.endsWith(".matte") && sprite.frames[frameIndex].alpha <= 0.0) {
                        return@mapNotNull null
                    }
                    return@mapNotNull (spritePool.acquire() ?: SVGADrawerSprite(
                        sprite.matteKey ?: "",
                        imageKey,
                        sprite.frames[frameIndex]
                    ))
                }
            }
            return@mapNotNull null
        }
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
