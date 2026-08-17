package com.opensource.svgaplayer.cache

import com.opensource.svgaplayer.SVGAParser.ParseCompletion

/**
 * @Author     :Leo
 * Date        :2024/12/3
 * Description : 同一资源并发加载时的等待队列
 */
object SVGAMemoryLoadingQueue {

    class SVGAMemoryLoadingItem(
        val callback: ParseCompletion?,
    )

    /**
     * 队列内部锁。
     * [com.opensource.svgaplayer.SVGAParser] 需要将“读内存缓存 + 入队”与“写内存缓存 + 移除通知”
     * 组合成原子操作，必须直接使用本锁（两者交错会导致个别等待者的回调永远收不到通知）。
     */
    internal val lock = Any()

    private val loadingMap = HashMap<String, MutableList<SVGAMemoryLoadingItem>>()

    fun inQueue(memoryCacheKey: String): Boolean = synchronized(lock) {
        loadingMap.containsKey(memoryCacheKey)
    }

    /**
     * 原子入队。
     * @return true：同资源已有在途加载，已入队等待统一通知；
     *         false：当前是首个到达者，由调用方承担加载并通过直接回调接收结果
     */
    fun enqueue(memoryCacheKey: String, item: SVGAMemoryLoadingItem): Boolean = synchronized(lock) {
        val waiters = loadingMap[memoryCacheKey]
        if (waiters == null) {
            //占位空列表：标记本资源进入在途加载，后续到达者据此入队等待
            loadingMap[memoryCacheKey] = mutableListOf()
            false
        } else {
            waiters.add(item)
            true
        }
    }

    fun removeItem(memoryCacheKey: String): List<SVGAMemoryLoadingItem>? = synchronized(lock) {
        loadingMap.remove(memoryCacheKey)
    }

}
