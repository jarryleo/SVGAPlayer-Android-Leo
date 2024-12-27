package com.opensource.svgaplayer.bitmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * 通过Asset解码 Bitmap
 *
 * Create by im_dsd 2020/7/7 17:50
 */
internal class SVGABitmapResDecoder(val context: Context) : SVGABitmapDecoder<Int>() {

    override fun onDecode(data: Int, ops: BitmapFactory.Options): Bitmap? {
        return kotlin.runCatching {
            BitmapFactory.decodeResource(context.resources, data, ops)
        }.getOrNull()
    }
}