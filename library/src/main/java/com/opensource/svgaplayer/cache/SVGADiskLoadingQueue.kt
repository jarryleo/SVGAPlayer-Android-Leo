package com.opensource.svgaplayer.cache

import com.opensource.svgaplayer.SVGAConfig
import com.opensource.svgaplayer.SVGAParser.ParseCompletion
import com.opensource.svgaplayer.SVGAParser.PlayCallback
import com.opensource.svgaplayer.cache.SVGADiskLoadingQueue.joinOrStart
import com.opensource.svgaplayer.cache.SVGADiskLoadingQueue.registerJob
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlin.coroutines.cancellation.CancellationException

/**
 * 同 URL 并发磁盘下载的去重队列，与 [[SVGAMemoryLoadingQueue]] 互补：
 * 内存缓存关闭（默认 isCacheToMemory = false）或未命中时，磁盘下载层仍需去重，
 * 避免两个 [com.opensource.svgaplayer.download.FileDownloader] 并发写同一个 cacheFile
 * 导致内容损坏，以及其中一方取消时 FileDownloader 删除 cacheFile 让并发读者 ENOENT。
 *
 * 锁与 SVGAMemoryLoadingQueue.lock 共用 loadStateLock，统一协调。
 */
object SVGADiskLoadingQueue {

    /**
     * @param proxyJob 等待者持有的代理 Job：等待者取消（view 回收）只把自己移出队列，
     * 不触碰真实下载 Job。若直接把真实 Job 交给等待者，等待者回收会误杀加载者的下载，
     * 而接管重载以 callback = null 发起，加载者既收不到 onComplete 也收不到 onError
     */
    class SVGADiskLoadingItem(
        val callback: ParseCompletion?,
        val playCallback: PlayCallback?,
        val config: SVGAConfig,
        val proxyJob: CompletableJob
    )

    // 等待者列表：cacheKey -> List of waiters（空列表为加载者占位）
    private val loadingMap = HashMap<String, MutableList<SVGADiskLoadingItem>>()
    // 在途下载 Job
    private val inFlightJobs = HashMap<String, Job>()

    /**
     * 加入等待或登记为下载者。
     * - joined=true：当前调用作为等待者入队，返回代理 Job（取消只摘除自己）；
     * - joined=false：当前调用是下载者，需启动下载并 [registerJob]。
     *
     * 下载者分支在锁内放入占位 Job：[registerJob] 前的窗口内并发调用会被判为等待者，
     * 杜绝两个 FileDownloader 并发写同一 cacheFile。
     * 已完成（!isActive）的死 Job 视为不在途直接覆盖，避免残留条目让该 URL 后续加载永久等待。
     * 接管重载场景下 loadingMap 已存在等待者，本方法用 getOrPut 保留旧列表而不覆盖。
     */
    fun joinOrStart(
        cacheKey: String,
        callback: ParseCompletion?,
        playCallback: PlayCallback?,
        config: SVGAConfig
    ): Pair<Boolean, Job?> =
        synchronized(SVGAMemoryLoadingQueue.lock) {
            val existingJob = inFlightJobs[cacheKey]
            if (existingJob != null && existingJob.isActive) {
                val proxyJob = Job()
                val item = SVGADiskLoadingItem(callback, playCallback, config, proxyJob)
                loadingMap.getOrPut(cacheKey) { mutableListOf() }.add(item)
                //等待者主动取消（view 回收）：仅从队列摘除自己，真实下载与加载者不受影响；
                //正常完成路径由 completeAndTake/cancelAndTakeWaiters 统一 complete 代理 Job，不会走到这里
                proxyJob.invokeOnCompletion { cause ->
                    if (cause is CancellationException) {
                        removeWaiter(cacheKey, item)
                    }
                }
                true to proxyJob
            } else {
                //占位 Job：registerJob 覆盖前，并发调用一律判为等待者
                inFlightJobs[cacheKey] = Job()
                loadingMap.getOrPut(cacheKey) { mutableListOf() }
                false to null
            }
        }

    /** 注册启动后的下载 Job（覆盖 [joinOrStart] 放入的占位）。 */
    fun registerJob(cacheKey: String, job: Job) =
        synchronized(SVGAMemoryLoadingQueue.lock) {
            inFlightJobs[cacheKey] = job
        }

    /** 成功完成：移除 in-flight + 取走所有等待者（用于通知）。 */
    fun completeAndTake(cacheKey: String): List<SVGADiskLoadingItem>? =
        synchronized(SVGAMemoryLoadingQueue.lock) {
            inFlightJobs.remove(cacheKey)
            loadingMap.remove(cacheKey)?.onEach { it.proxyJob.complete() }
        }

    /**
     * 仅移除 in-flight，保留等待者。用于缓存文件被并发删除后的下载兜底：
     * 兜底发起方自身（命中缓存分支的外层 Job）仍占着 in-flight，
     * 不清掉则兜底调 startDecodeFromURL 时 joinOrStart 把自己判为等待者，下载永不启动。
     */
    fun clearInFlight(cacheKey: String): Job? =
        synchronized(SVGAMemoryLoadingQueue.lock) {
            inFlightJobs.remove(cacheKey)
        }

    /**
     * 加载者被取消时判断是否需要接管下载：
     * 有等待者 → 仅移除 in-flight 并返回 true（等待者留给接管下载通知）；
     * 无等待者或无在途 → 返回 false。避免无等待者的普通 clear 反而触发一次后台重下。
     */
    fun prepareTakeover(cacheKey: String): Boolean =
        synchronized(SVGAMemoryLoadingQueue.lock) {
            inFlightJobs.remove(cacheKey) != null && !loadingMap[cacheKey].isNullOrEmpty()
        }

    /** 失败/无接管：清空所有状态并返回等待者（用于 onError 通知）。 */
    fun cancelAndTakeWaiters(cacheKey: String): List<SVGADiskLoadingItem>? =
        synchronized(SVGAMemoryLoadingQueue.lock) {
            inFlightJobs.remove(cacheKey)
            loadingMap.remove(cacheKey)?.onEach { it.proxyJob.complete() }
        }

    /** 等待者主动取消：只移除自己；空列表保留（可能是加载者占位，由 prepareTakeover 判断）。 */
    private fun removeWaiter(cacheKey: String, item: SVGADiskLoadingItem) =
        synchronized(SVGAMemoryLoadingQueue.lock) {
            loadingMap[cacheKey]?.remove(item)
        }
}
