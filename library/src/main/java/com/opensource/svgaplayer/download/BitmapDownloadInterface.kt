package com.opensource.svgaplayer.download

import android.content.Context
import android.graphics.Bitmap

/**
 * bitmap下载接口
 * 可以自定义实现，用第三方图片框架，默认本库自带
 */
/**
 * @Author     :Leo
 * Date        :2026/3/3
 * Description :
 */
interface BitmapDownloadInterface {
    suspend fun downloadBitmap(
        context: Context,
        url: String,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap?
}