package com.opensource.svgaplayer

import java.lang.ref.WeakReference


/** 简易调用 */
open class SVGASimpleCallback : SVGAParser.ParseCompletion {

    override fun onComplete(videoItem: SVGAVideoEntity) {}

    override fun onError() {}
}

/**
 * SVGAImageView 加载回调
 * 1. 弱引用持有 view，回调滞留在解析任务/等待队列中也不会泄漏 view
 * 2. 绑定加载来源 [source]，view 已切换加载其他资源时本回调自动失效，避免播错动画
 * 3. 触发或取消后立即 [release]，不再响应任何回调
 */
class SVGAViewLoadCallback(
    private val source: String,
    private var weakView: WeakReference<SVGAImageView>?
) : SVGASimpleCallback() {

    @Volatile
    private var cancelled = false

    /** 回调是否在途（未触发、未取消），用于判断同一资源是否正在加载 */
    val isPending: Boolean
        get() = !cancelled

    override fun onComplete(videoItem: SVGAVideoEntity) {
        validView()?.startAnimation(videoItem)
        release()
    }

    override fun onError() {
        validView()?.let {
            it.onError?.invoke(it)
        }
        release()
    }

    fun cancel() {
        release()
    }

    /** view 存活、未取消、且 view 当前仍在等待本回调对应的资源时才有效 */
    private fun validView(): SVGAImageView? {
        if (cancelled) return null
        val view = weakView?.get() ?: return null
        if (view.loadingSource != source) return null
        return view
    }

    private fun release() {
        cancelled = true
        weakView?.clear()
        weakView = null
    }
}
