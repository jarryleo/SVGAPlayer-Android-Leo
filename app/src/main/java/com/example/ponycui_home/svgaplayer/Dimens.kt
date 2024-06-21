package com.example.ponycui_home.svgaplayer

import android.content.res.Resources
import kotlin.math.roundToInt

/**
 * @Author     :Leo
 * Date        :2024/6/21
 * Description :
 */

val Int.dp: Int get() = (this * Resources.getSystem().displayMetrics.density).roundToInt()