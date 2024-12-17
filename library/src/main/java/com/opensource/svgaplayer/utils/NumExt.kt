package com.opensource.svgaplayer.utils

import kotlin.math.roundToInt

/**
 * @Author     :Leo
 * Date        :2024/12/17
 * Description :
 */

fun Float.roundToIntSafe(): Int {
    if (isNaN()) return 0
    return this.roundToInt()
}

fun Double.roundToIntSafe(): Int {
    if (isNaN()) return 0
    return this.roundToInt()
}

fun Int.maxOf(other: Int): Int {
    return if (this > other) this else other
}

fun Float.maxOf(other: Float): Float {
    return if (this > other) this else other
}