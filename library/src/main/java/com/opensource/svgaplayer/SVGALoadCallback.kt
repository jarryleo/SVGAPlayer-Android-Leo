package com.opensource.svgaplayer

import android.view.View


/** 简易调用 */
open class SVGASimpleCallback : SVGAParser.ParseCompletion {

    override fun onComplete(videoItem: SVGAVideoEntity) {}

    override fun onError() {}
}

class SVGAViewLoadCallback(private var svgaImageView: SVGAImageView?) : SVGASimpleCallback(),
    View.OnAttachStateChangeListener {

    init {
        svgaImageView?.addOnAttachStateChangeListener(this)
    }

    override fun onComplete(videoItem: SVGAVideoEntity) {
        svgaImageView?.startAnimation(videoItem)
        svgaImageView = null
    }

    override fun onError() {
        svgaImageView?.let {
            it.onError?.invoke(it)
        }
        svgaImageView = null
    }

    override fun onViewAttachedToWindow(v: View) {
    }

    override fun onViewDetachedFromWindow(v: View) {
        if (v == svgaImageView) {
            svgaImageView?.removeOnAttachStateChangeListener(this)
            svgaImageView = null
        }
    }
}