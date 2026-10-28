package com.opensource.svgaplayer.bitmap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream

/**
 * 通过Asset解码 Bitmap
 *
 * Create by im_dsd 2020/7/7 17:50
 */
internal object SVGABitmapInputStreamDecoder : SVGABitmapDecoder<InputStream>() {

    override fun onDecode(data: InputStream, ops: BitmapFactory.Options): Bitmap? {
        return kotlin.runCatching {
            BitmapFactory.decodeStream(data, null, ops)
        }.getOrNull()
    }
}