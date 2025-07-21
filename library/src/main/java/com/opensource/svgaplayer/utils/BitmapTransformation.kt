package com.opensource.svgaplayer.utils

import android.graphics.Bitmap

/**
 * @Author     :Leo
 * Date        :2024/12/16
 * Description : bitmap 转换
 */
interface BitmapTransformation {

    fun transform(bitmap: Bitmap?, width: Int?, height: Int?): Bitmap?
}