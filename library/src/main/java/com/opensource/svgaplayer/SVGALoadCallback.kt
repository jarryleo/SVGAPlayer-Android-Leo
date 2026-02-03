package com.opensource.svgaplayer

import java.lang.ref.WeakReference


/** 简易调用 */
open class SVGASimpleCallback : SVGAParser.ParseCompletion {

    override fun onComplete(videoItem: SVGAVideoEntity) {}

    override fun onError() {}
}

class SVGAViewLoadCallback(private var weakView: WeakReference<SVGAImageView>?) :
    SVGASimpleCallback() {

    override fun onComplete(videoItem: SVGAVideoEntity) {
        weakView?.get()?.startAnimation(videoItem)
        release()
    }

    override fun onError() {
        weakView?.get()?.let {
            it.onError?.invoke(it)
        }
        release()
    }

    fun cancel() {
        release()
    }


    private fun release() {
        weakView?.clear()
        weakView = null
    }
}