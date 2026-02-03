package com.opensource.svgaplayer.entities

import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.TextUtils

/**
 * @Author     :Leo
 * Date        :2024/7/2
 * Description : SVGA文本配置
 */
data class SVGATextEntity(
    var text: String = "",
    var textSize: Float = 20f,
    var scale: Float = 0.9f,
    var textColor: Int = Color.WHITE,
    var maxLines: Int = 1,
    var typeface: Typeface? = null,
    var alignment: Layout.Alignment = Layout.Alignment.ALIGN_CENTER,
    var ellipsize: TextUtils.TruncateAt = TextUtils.TruncateAt.MARQUEE,
    var spacingAdd: Float = 0f,
    var spacingMultiplier: Float = 0f,
)
