package com.opensource.svgaplayer

import com.opensource.svgaplayer.utils.log.LogUtils
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
 * 3. view 的加载作用域被取消（detach）后本回调自动失效，避免 detach 后完成解码把实体
 *    经 startAnimation 的 post 挂到不再显示的 view 上
 * 4. 触发或取消后立即 [release]，不再响应任何回调
 */
class SVGAViewLoadCallback(
    private val source: String,
    private var weakView: WeakReference<SVGAImageView>?
) : SVGASimpleCallback() {

    private companion object {
        const val TAG = "SVGAViewLoadCallback"
    }

    @Volatile
    private var cancelled = false

    /** 回调是否在途（未触发、未取消），用于判断同一资源是否正在加载 */
    val isPending: Boolean
        get() = !cancelled

    override fun onComplete(videoItem: SVGAVideoEntity) {
        val view = validView()
        if (view != null) {
            //预占绑定计数：完成通知到主线程真正 setVideoItem 之间，实体只被内存缓存持有
            //（引用计数 0），冷启动批量解码时会被 LRU 驱逐并 clear，绑定侧 acquireIfUsable
            //失败表现为"parser complete 却不播放"；先 acquire 让驱逐的 markReleasedIfUnused 跳过
            if (videoItem.acquireIfUsable()) {
                view.startAnimation(videoItem, preAcquired = true)
            } else {
                LogUtils.info(
                    TAG,
                    "drop: entity released before delivery, source = $source"
                )
            }
        }
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

    /** view 存活、加载作用域未取消（未 detach）、未取消回调、且 view 当前仍在等待本回调对应的资源时才有效 */
    private fun validView(): SVGAImageView? {
        if (cancelled) return null
        val view = weakView?.get()
        if (view == null) {
            LogUtils.info(TAG, "drop: view recycled, source = $source")
            return null
        }
        //作用域已取消：本回调来自已结束的窗口周期，view 需要的动画由 attach 后的兜底加载驱动
        if (!view.isLoadScopeActive()) {
            LogUtils.info(TAG, "drop: view detached (load scope dead), source = $source")
            return null
        }
        if (view.loadingSource != source) {
            LogUtils.info(
                TAG,
                "drop: source changed to ${view.loadingSource}, expect $source"
            )
            return null
        }
        return view
    }

    private fun release() {
        cancelled = true
        weakView?.clear()
        weakView = null
    }
}
