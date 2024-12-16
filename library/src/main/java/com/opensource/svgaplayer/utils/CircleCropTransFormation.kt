package com.opensource.svgaplayer.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect

/**
 * @Author     :Leo
 * Date        :2024/12/16
 * Description :
 */
class CircleCropTransFormation : BitmapTransformation {
    companion object {
        private const val CIRCLE_CROP_PAINT_FLAGS =
            Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG
        val CIRCLE_CROP_SHAPE_PAINT = Paint(CIRCLE_CROP_PAINT_FLAGS)
        val CIRCLE_CROP_BITMAP_PAINT =
            Paint(CIRCLE_CROP_PAINT_FLAGS).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            }
    }

    override fun transform(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null) return null
        val w = bitmap.width
        val h = bitmap.height
        val d = minOf(w, h)
        val r = d / 2f
        val output = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val srcRect = Rect((w - d) / 2, (h - d) / 2, d, d)
        val destRect = Rect(0, 0, d, d)
        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawCircle(r, r, r, CIRCLE_CROP_SHAPE_PAINT)
        canvas.drawBitmap(bitmap, srcRect, destRect, CIRCLE_CROP_BITMAP_PAINT)
        canvas.setBitmap(null)
        return output
    }
}