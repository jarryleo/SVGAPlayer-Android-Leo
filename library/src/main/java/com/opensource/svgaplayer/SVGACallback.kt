package com.opensource.svgaplayer

/**
 * Created by cuiminghui on 2017/3/30.
 */
interface SVGACallback {
    fun onStart() {}
    fun onPause() {}
    fun onResume() {}
    fun onFinished() {}
    fun onRepeat() {}
    fun onCancel() {}
    fun onStep(frame: Int, percentage: Double) {}
}