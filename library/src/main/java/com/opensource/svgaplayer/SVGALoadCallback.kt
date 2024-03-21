package com.opensource.svgaplayer

import java.lang.ref.WeakReference

typealias SVGADynamicCallback = (SVGAVideoEntity) -> SVGADynamicEntity?

/** 简易调用 */
open class SVGASimpleCallback : SVGAParser.ParseCompletion {
    override fun onComplete(videoItem: SVGAVideoEntity) {}

    override fun onError() {}
}

/** 循环播放
 * @param loopCount 循环次数 0 为无限循环
 * */
open class SVGAPlayCallback(
    view: SVGAImageView?,
    private val loopCount: Int = 0,
    dynamicCallback: SVGADynamicCallback? = null
) : SVGAParser.ParseCompletion {

    private val view = WeakReference(view)
    private val dynamicCallback = WeakReference(dynamicCallback)
    override fun onComplete(videoItem: SVGAVideoEntity) {
        view.get()?.takeIf { it.isAttachedToWindow }?.apply {
            clearsAfterDetached = true
            loops = loopCount
            setVideoItem(videoItem, dynamicCallback.get()?.invoke(videoItem))
            startAnimation()
        }
    }

    override fun onError() {
        view.get()?.takeIf { it.isAttachedToWindow }?.apply {
            clear()
        }
    }
}
