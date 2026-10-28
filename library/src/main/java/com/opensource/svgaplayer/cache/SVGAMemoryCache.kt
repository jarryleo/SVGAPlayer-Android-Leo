package com.opensource.svgaplayer.cache

import android.os.Build
import android.os.SystemClock
import android.util.LruCache
import com.opensource.svgaplayer.SVGAConfig
import com.opensource.svgaplayer.SVGAVideoEntity
import com.opensource.svgaplayer.utils.log.LogUtils

/**
 * @Description SVGA内存缓存（强引用 + 按实体 bitmap 字节数记账的 LRU）
 *
 * 旧实现为 LruCache<String, WeakReference<SVGAVideoEntity>>：view clear 后实体仅剩弱引用，
 * 缓存几乎不命中，同一资源被反复解码出多套 bitmap；且 entryRemoved 中的
 * oldValue?.clear() 调用的是 WeakReference.clear()，对 bitmap 释放完全无效。
 *
 * 现改为强引用缓存：总预算按字节计（默认 100MB），超限驱逐最旧条目；
 * 驱逐/替换/清空时仅释放无 view 绑定（引用计数为 0）的实体，展示中的动画不受影响，
 * 由其 view clear 时归还缓存。实体释放语义见 [SVGAVideoEntity.release]。
 *
 * 两类实体不进入/不归还缓存（put 与 returnToCache 口径一致）：
 * 1. 单实体字节数超过缓存总预算：put 后必被立即驱逐并 clear，此时 view 尚未绑定，
 *    表现为大体积 svga 加载成功却不播放；改为不缓存，正常播放仅失去复用；
 * 2. 解码不完整（构造期异常/OOM 被吞）的实体：imageMap 残缺，复用会缺图。
 */
class SVGAMemoryCache(maxBytes: Long = defaultMaxBytes) {

    private val TAG = "SVGAMemoryCache"

    /**
     * 最近一次有新内容写入缓存（putData/returnToCache 成功 put）的时间
     * （SystemClock.elapsedRealtime），0 表示尚无写入。供闲时内存释放策略判定
     * "连续 N 秒无新内容入缓存"；get 命中不算新内容，不刷新时间戳
     */
    @Volatile
    var lastPutAtElapsed: Long = 0L
        private set

    private val lruCache by lazy {
        object : LruCache<String, SVGAVideoEntity>(toCacheSize(maxBytes)) {
            override fun sizeOf(key: String, entity: SVGAVideoEntity): Int {
                //按真实字节数记账；异常兜底为 1，避免 0 尺寸条目永远不占预算也无法被记账驱逐
                return entity.getMemorySize().coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            }

            override fun entryRemoved(
                evicted: Boolean,
                key: String?,
                oldValue: SVGAVideoEntity?,
                newValue: SVGAVideoEntity?
            ) {
                //替换路径（evicted=false）的旧实体同样在此释放，不再泄漏给 GC 兜底；
                //仍有 view 绑定的实体不能回收（否则展示中的动画会空白），仅从缓存摘除，
                //待其末次释放时由 view 重新写回。"判定 + 标记"原子完成，与绑定路径互斥，
                //不会出现判定无绑定后、绑定恰好到达导致回收展示中 bitmap 的交错
                if (oldValue != null && oldValue !== newValue && oldValue.markReleasedIfUnused()) {
                    oldValue.clear()
                }
            }
        }
    }

    fun getData(key: String): SVGAVideoEntity? {
        val entity = lruCache.get(key) ?: return null
        if (entity.isReleased) {
            //防御：已释放实体不应存在于缓存，移除防止复用已回收的 bitmap
            lruCache.remove(key)
            return null
        }
        return entity
    }

    fun putData(key: String, entity: SVGAVideoEntity) {
        if (entity.isReleased) return
        if (entity.isDecodeIncomplete) {
            //解码中断（异常/OOM 被吞）的实体 imageMap 残缺，记账失真且复用会缺图，不入缓存
            LogUtils.info(TAG, "skip cache, decode incomplete: $key")
            return
        }
        if (isOverBudget(entity)) {
            //单个实体超过缓存总预算时不能 put：put 后 trimToSize 会立即驱逐并 clear 它，
            //此刻 view 尚未 acquire（refCount=0），绑定时发现 isReleased 直接放弃显示，
            //表现为"大体积 svga 加载成功却不播放"。不入缓存，实体按无缓存路径正常流转播放
            LogUtils.info(
                TAG,
                "skip cache, entity ${entity.getMemorySize()} over budget ${lruCache.maxSize()}: $key"
            )
            return
        }
        lruCache.put(key, entity)
        lastPutAtElapsed = SystemClock.elapsedRealtime()
    }

    /**
     * view 末次释放实体时归还缓存（put-if-absent 语义，check+put 原子完成）。
     *
     * 不能直接 put：新解码实体写回缓存后、主线程回调尚未执行 acquire 的窗口内，旧实体的
     * 归还 put 会把它顶掉，entryRemoved 随即 clear 掉这个引用计数为 0 的新实体，主线程
     * 绑定时发现 isReleased，表现为偶现加载完成却不播放。因此缓存已有同 key 的其他存活
     * 实体时保留缓存实体，当前实体就地释放。
     */
    fun returnToCache(key: String, entity: SVGAVideoEntity) {
        if (entity.isReleased) return
        var clearEntity = false
        //与 LruCache 内部锁同一监视器：parser 侧锁内的 putData/getData 复合操作不会与本块交错
        synchronized(lruCache) {
            var cached = lruCache.get(key)
            if (cached?.isReleased == true) {
                //防御：与 getData 一致，已释放实体不可复用，摘除后视作空位
                lruCache.remove(key)
                cached = null
            }
            when {
                cached === entity -> Unit //正常流：decode 完成时已写入，view 释放后物归原位

                //可回缓存的条件与 putData 一致：不超总预算且解码完整。
                //超预算实体 put 同样会被立即驱逐（且此刻 refCount 已归 0，驱逐即 clear），
                //解码残缺实体不该经此路径绕过 putData 的过滤
                cached == null && !entity.isDecodeIncomplete && !isOverBudget(entity) ->
                    lruCache.put(key, entity).also {
                        lastPutAtElapsed = SystemClock.elapsedRealtime()
                    }

                else -> clearEntity = true //缓存已有更新的在途实体，或本实体不可回缓存
            }
        }
        //释放可能较重（recycle bitmap / release soundPool），移到锁外执行
        if (clearEntity) {
            entity.clear()
        }
    }

    /** 单个实体字节数是否超过缓存总预算（超过则 put 后必然被立即驱逐） */
    private fun isOverBudget(entity: SVGAVideoEntity): Boolean {
        return entity.getMemorySize() > lruCache.maxSize()
    }

    /** 当前缓存记账字节数（LRU 内部 size），供外部判定缓存是否为空/观测占用 */
    fun sizeBytes(): Int = lruCache.size()

    /**
     * 重新设置缓存字节预算
     * @param maxBytes Long
     */
    fun resizeCache(maxBytes: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            lruCache.resize(toCacheSize(maxBytes))
        }
    }

    fun clear() {
        lruCache.evictAll()
    }

    companion object {

        val INSTANCE by lazy { SVGAMemoryCache(defaultMaxBytes) }

        /** 内存缓存字节预算，默认 100MB */
        var defaultMaxBytes: Long = 100L * 1024 * 1024
            set(value) {
                field = value
                INSTANCE.resizeCache(value)
            }

        /** LruCache 的 maxSize 为 Int，字节预算需收敛到 Int 范围且至少为 1 */
        private fun toCacheSize(maxBytes: Long): Int {
            return maxBytes.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        }

        /**
         * 拼接缓存Key所需要的字段
         */
        fun createKey(path: String, config: SVGAConfig): String {
            return "key:{path = $path frameWidth = ${config.frameWidth} frameHeight = ${config.frameHeight}}".let {
                SVGAFileCache.buildCacheKey(it)
            }
        }
    }
}
