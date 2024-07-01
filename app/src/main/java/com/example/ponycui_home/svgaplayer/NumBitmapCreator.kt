package com.example.ponycui_home.svgaplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * @Author     :Leo
 * Date        :2024/6/20
 * Description : 数字转图片组合成bitmap
 */
object NumBitmapCreator {
    private val numRes = arrayOf(
        R.drawable.ic_crazy_num_0,
        R.drawable.ic_crazy_num_1,
        R.drawable.ic_crazy_num_2,
        R.drawable.ic_crazy_num_3,
        R.drawable.ic_crazy_num_4,
        R.drawable.ic_crazy_num_5,
        R.drawable.ic_crazy_num_6,
        R.drawable.ic_crazy_num_7,
        R.drawable.ic_crazy_num_8,
        R.drawable.ic_crazy_num_9,
    )

    /**
     * 把数字转换成bitmap，用numRes图片组合
     */
    fun createNumBitmap(
        context: Context,
        num: Int,
        width: Int? = null,
        height: Int? = null
    ): Bitmap? {
        val numStr = num.toString()
        val bitmapList = mutableListOf<Bitmap>()
        for (c in numStr) {
            val index = c - '0'
            if (index in numRes.indices) {
                val bitmap = loadBitmap(context, numRes[index])
                bitmapList.add(bitmap)
            }
        }
        return createBitmap(bitmapList, width, height)
    }

    private fun loadBitmap(context: Context, resId: Int): Bitmap {
        return BitmapFactory.decodeResource(context.resources, resId)
    }

    private fun createBitmap(
        bitmapList: List<Bitmap>,
        w: Int? = null,
        h: Int? = null
    ): Bitmap? {
        if (bitmapList.isEmpty()) {
            return null
        }
        val width = bitmapList.sumOf { it.width }
        val height = bitmapList.maxByOrNull { it.height }?.height ?: return null
        val bw = maxOf(w ?: width, width)
        val bh = maxOf(h ?: height, height)
        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        var left = (bw - width) / 2
        val top = (bh - height) / 2
        for (b in bitmapList) {
            val pixels = IntArray(b.width * b.height)
            b.getPixels(pixels, 0, b.width, 0, 0, b.width, b.height)
            bitmap.setPixels(pixels, 0, b.width, left, top, b.width, b.height)
            left += b.width
            if (!b.isRecycled){
                b.recycle()
            }
        }
        return bitmap
    }

}