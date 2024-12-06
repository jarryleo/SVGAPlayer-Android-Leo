package com.opensource.svgaplayer.entities

import com.opensource.svgaplayer.proto.SpriteEntity
import org.json.JSONObject

/**
 * Created by cuiminghui on 2016/10/17.
 */
internal class SVGAVideoSpriteEntity {

    val imageKey: String?

    val matteKey: String?

    var frames: List<SVGAVideoSpriteFrameEntity>?

    constructor(obj: JSONObject) {
        this.imageKey = obj.optString("imageKey")
        this.matteKey = obj.optString("matteKey")
        val mutableFrames: MutableList<SVGAVideoSpriteFrameEntity> = mutableListOf()
        obj.optJSONArray("frames")?.let {
            for (i in 0 until it.length()) {
                it.optJSONObject(i)?.let { frame ->
                    val frameItem = SVGAVideoSpriteFrameEntity(frame)
                    frameItem.shapes?.firstOrNull()?.let { shape ->
                        if (shape.isKeep && mutableFrames.size > 0) {
                            frameItem.shapes = mutableFrames.last().shapes
                        }
                    }
                    mutableFrames.add(frameItem)
                }
            }
        }
        frames = mutableFrames.toList()
    }

    constructor(obj: SpriteEntity) {
        this.imageKey = obj.imageKey
        this.matteKey = obj.matteKey
        var lastFrame: SVGAVideoSpriteFrameEntity? = null
        frames = obj.frames?.map { frame ->
            val frameItem = SVGAVideoSpriteFrameEntity(frame)
            frameItem.shapes?.firstOrNull()?.let { shape ->
                if (shape.isKeep) {
                    lastFrame?.let { last ->
                        frameItem.shapes = last.shapes
                    }
                }
            }
            lastFrame = frameItem
            return@map frameItem
        } ?: listOf()

    }

    fun clear() {
        frames?.forEach { it.clear() }
        frames = null
    }
}
