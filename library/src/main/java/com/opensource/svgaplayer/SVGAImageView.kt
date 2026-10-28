package com.opensource.svgaplayer

import android.animation.Animator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import com.opensource.svgaplayer.cache.SVGAMemoryCache
import com.opensource.svgaplayer.url.UrlDecoderManager
import com.opensource.svgaplayer.utils.SVGARange
import com.opensource.svgaplayer.utils.SourceUtil
import com.opensource.svgaplayer.utils.log.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.net.URL
import java.net.URLDecoder

/**
 * Created by PonyCui on 2017/3/29.
 * Modified by leo on 2024/7/1.
 */
@SuppressLint("ObsoleteSdkInt", "UNUSED")
open class SVGAImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr), CoroutineScope by MainScope() {

    private val TAG = "SVGAImageView"

    enum class FillMode {
        Backward, //动画结束后显示最后一帧
        Forward, //动画结束后显示第一帧
        Clear, //动画结束后清空画布,并释放内存
    }

    var isAnimating = false
        private set

    var loops = 0

    @Deprecated(
        "It is recommended to use clearAfterDetached, or manually call to SVGAVideoEntity#clear." +
                "If you just consider cleaning up the canvas after playing, you can use FillMode#Clear.",
        level = DeprecationLevel.WARNING
    )
    var clearsAfterStop = false
    var clearsAfterDetached = true
    var clearsLastSourceOnDetached = false
    var fillMode: FillMode = FillMode.Backward
    var callback: SVGACallback? = null

    private var mItemClickAreaListener: SVGAClickAreaListener? = null
    private var mAntiAlias = true
    private var mAutoPlay = true
    private var loadCallback: SVGAViewLoadCallback? = null
    private var mStartFrame = 0
    private var mEndFrame = 0
    private var volume = 1f

    //共享动画时钟中的会话；为 null 表示当前没有在播/暂停中的动画
    private var tickerSession: SVGAAnimationTicker.Session? = null

    private var lastSource: String? = null

    /**
     * 重新加载期间是否保留当前的非 SVGA 占位 drawable（静态图）：
     * parserSource 加载前的预清理只清动画状态与在途任务，静态占位留待新实体
     * 到达后被 setImageDrawable 原地替换。用于"滑动时静态图、停止后切 SVGA"
     * 的列表场景，避免先清空占位导致的闪空白。SVGADrawable 不受此开关影响。
     */
    private var keepPlaceholderOnReload = false

    //IO 线程(parserSource)写、主线程(回调校验/动画启动)读，需保证可见性
    @Volatile
    internal var loadingSource: String? = null
    private var lastConfig: SVGAConfig? = null
    private var loadJob: Job? = null
    private var dynamicBlock: (SVGADynamicEntity.() -> Unit)? = null
    internal var onError: ((SVGAImageView) -> Unit)? = {}

    /**
     * 内部加载协程作用域：attach 重建、detach 取消（区别于对外暴露的 CoroutineScope 委托，后者保持原行为不变）。
     * 旧实现加载协程挂在永不取消的 view 作用域上：detach 后在途 parserSource 会继续执行，
     * 重装 loadCallback 发起"复活解码"，完成的实体经 startAnimation 的 post 挂在已 detach 的 view 上
     * （RecyclerView 池延长持有时间），造成 bitmap 无法释放。
     * detach 后发起的 load 因作用域已取消而被丢弃，由 attach 后的
     * stepToFrame/onLayout 兜底重新加载（lastSource/lastConfig 已在 load 中记录）。
     */
    private var loadScope: CoroutineScope = MainScope()

    /** 加载回调有效性：加载作用域未被取消（view 未 detach）。detach 后旧窗口周期的加载一律失效 */
    internal fun isLoadScopeActive(): Boolean = loadScope.isActive

    init {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            this.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        attrs?.let { loadAttrs(it) }
    }

    private fun loadAttrs(attrs: AttributeSet) {
        val typedArray =
            context.theme.obtainStyledAttributes(attrs, R.styleable.SVGAImageView, 0, 0)
        loops = typedArray.getInt(R.styleable.SVGAImageView_loopCount, 0)
        clearsAfterStop = typedArray.getBoolean(R.styleable.SVGAImageView_clearsAfterStop, false)
        clearsAfterDetached =
            typedArray.getBoolean(R.styleable.SVGAImageView_clearsAfterDetached, true)
        clearsLastSourceOnDetached =
            typedArray.getBoolean(R.styleable.SVGAImageView_clearsLastSourceOnDetached, false)
        mAntiAlias = typedArray.getBoolean(R.styleable.SVGAImageView_antiAlias, true)
        mAutoPlay = typedArray.getBoolean(R.styleable.SVGAImageView_autoPlay, true)
        typedArray.getString(R.styleable.SVGAImageView_fillMode)?.let {
            when (it) {
                "0" -> {
                    fillMode = FillMode.Backward
                }

                "1" -> {
                    fillMode = FillMode.Forward
                }

                "2" -> {
                    fillMode = FillMode.Clear
                }
            }
        }
        typedArray.getString(R.styleable.SVGAImageView_source)?.let {
            lastSource = it
        }
        typedArray.recycle()
    }

    @JvmOverloads
    fun load(
        source: String?,
        config: SVGAConfig? = null,
        onError: ((SVGAImageView) -> Unit)? = null,
        keepPlaceholderOnReload: Boolean = false,
        dynamicBlock: (SVGADynamicEntity.() -> Unit)? = null
    ): SVGAImageView {
        this.visibility = View.VISIBLE
        this.dynamicBlock = dynamicBlock
        this.onError = onError
        this.keepPlaceholderOnReload = keepPlaceholderOnReload
        if (isReplayDrawable(source)) {
            return this
        }
        this.lastSource = source
        this.lastConfig = config
        if (source.isNullOrEmpty()) {
            stopAnimation()
            onError?.invoke(this)
            return this
        }
        //已有宽高才加载动画
        if ((width > 0 && height > 0) || config?.isOriginal == true) {
            //与 onLayout/stepToFrame 的触发一样打时间戳：否则 load 后紧随的布局变化会再次触发
            //parserSource，与本次加载在 IO 线程交错形成同 view 双重加载
            markParserSourceTriggered()
            loadScope.launch(Dispatchers.IO) {
                parserSource(source, config)
            }
        } else {
            requestLayout()
        }
        return this
    }

    private suspend fun parserSource(source: String?, config: SVGAConfig? = lastConfig) {
        if (source.isNullOrEmpty()) return
        //设置动画属性
        loops = config?.loopCount ?: loops
        mAutoPlay = config?.autoPlay ?: mAutoPlay
        var cfg = config
        if (cfg != null && !cfg.isOriginal && cfg.frameWidth == 0 && cfg.frameHeight == 0) {
            cfg = cfg.copy(
                frameWidth = width,
                frameHeight = height
            )
        }
        lastConfig = cfg
        val urlDecoder = UrlDecoderManager.getUrlDecoder()
        val realUrl =
            urlDecoder.decodeSvgaUrl(source, cfg?.frameWidth ?: width, cfg?.frameHeight ?: height)
        //同一资源正在加载中：保留在途回调（加载完成后由它驱动播放），直接返回
        if (loadingSource == realUrl && loadCallback?.isPending == true) {
            LogUtils.info(TAG, "view = ${hashCode()} same source is loading, skip: $realUrl")
            return
        }
        var parser = SVGAParser.shareParser()
        if (parser == null) {
            SVGAParser.init(context.applicationContext)
            parser = SVGAParser.shareParser()
        }
        val callback = SVGAViewLoadCallback(realUrl, WeakReference(this))
        //清理、安装回调、标记来源必须在主线程原子完成：此前 clear 与安装之间有交错窗口，
        //并发 parserSource 会让旧回调逃逸取消、与新回调同时待决，完成投递双双到达时，
        //后者撞上前者触发的 onAnimationStart（loadingSource 已置 null）被整批丢弃
        withContext(Dispatchers.Main) {
            clear(keepPlaceholder = keepPlaceholderOnReload)
            this@SVGAImageView.loadCallback = callback
            loadingSource = realUrl
        }
        if (SourceUtil.isUrl(realUrl)) {
            val decode = try {
                withContext(Dispatchers.IO) {
                    URLDecoder.decode(realUrl, "UTF-8")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                realUrl
            }
            val url = try {
                URL(decode)
            } catch (e: Exception) {
                e.printStackTrace()
                onError?.invoke(this)
                return
            }
            LogUtils.info(
                TAG,
                "view = ${hashCode()} load from url: $realUrl , last source: $lastSource"
            )
            loadJob = parser?.decodeFromURL(
                url,
                config = cfg ?: SVGAConfig(frameWidth = width, frameHeight = height),
                callback
            )
        } else if (SourceUtil.isFilePath(realUrl)) {
            LogUtils.info(
                TAG,
                "view = ${hashCode()} load from file: $realUrl , last source: $lastSource"
            )
            loadJob = parser?.decodeFromFile(
                realUrl,
                config = cfg ?: SVGAConfig(frameWidth = width, frameHeight = height),
                callback
            )
        } else {
            LogUtils.info(
                TAG,
                "view = ${hashCode()} load from assert: $realUrl , last source: $lastSource"
            )
            loadJob = parser?.decodeFromAssets(
                realUrl,
                config = cfg ?: SVGAConfig(frameWidth = width, frameHeight = height),
                callback
            )
        }
    }


    fun startAnimation(videoItem: SVGAVideoEntity) {
        startAnimation(videoItem, preAcquired = false)
    }

    /**
     * @param preAcquired 调用方已完成 [SVGAVideoEntity.acquireIfUsable] 预占（完成回调投递路径），
     *        跳过二次计数，避免与投递窗口内的缓存驱逐竞态
     */
    internal fun startAnimation(videoItem: SVGAVideoEntity, preAcquired: Boolean) {
        post {
            stopAnimation()
            videoItem.antiAlias = mAntiAlias
            val dynamicItem = SVGADynamicEntity(context)
            setVideoItemInternal(videoItem, dynamicItem, preAcquired)
            dynamicBlock?.let { dynamicItem.it() }
            getSVGADrawable()?.scaleType = scaleType
            if (mAutoPlay) {
                play(null, false)
            } else {
                stepToFrame(1, false)
            }
        }
    }

    fun startAnimation() {
        startAnimation(null, false)
    }

    fun startAnimation(range: SVGARange?, reverse: Boolean = false) {
        stopAnimation(false)
        play(range, reverse)
    }

    private fun play(range: SVGARange?, reverse: Boolean) {
        if (isAnimating) return
        val drawable = getSVGADrawable() ?: return
        setupDrawable()
        mStartFrame = 0.coerceAtLeast(range?.location ?: 0)
        val videoItem = drawable.videoItem
        mEndFrame = (videoItem.frames - 1).coerceAtMost(
            ((range?.location ?: 0) + (range?.length ?: Int.MAX_VALUE) - 1)
        )
        //退化区间保护：长度为 0 的 SVGARange 会算出比首帧还小的 endFrame，
        //旧实现 0 时长 animator 立即结束，这里钳到 startFrame 保证帧号始终合法
        mEndFrame = mEndFrame.coerceAtLeast(mStartFrame)
        //注册进全局共享时钟，由统一的 Choreographer 回调推进帧
        tickerSession = SVGAAnimationTicker.start(
            view = this,
            startFrame = mStartFrame,
            endFrame = mEndFrame,
            fps = videoItem.FPS,
            loops = loops,
            reverse = reverse,
        )
        onAnimationStart(null)
        val svgaMemorySize = getSvgaMemorySize()
        if (svgaMemorySize >= 30 * 1024 * 1024L) {
            // svga 动画大于30M 则红色提醒
            LogUtils.error(
                TAG, "================ start animation ================" +
                        "\r\n view: ${hashCode()}" +
                        "\r\n source: $lastSource" +
                        "\r\n url: $loadingSource" +
                        "\r\n svgaMemorySize: ${getSvgaMemorySizeFormat()}($svgaMemorySize Bytes)"
            )
        } else if (svgaMemorySize >= 10 * 1024 * 1024L) {
            // svga 动画大于10M 则黄色提醒
            LogUtils.warn(
                TAG, "================ start animation ================" +
                        "\r\n view: ${hashCode()}" +
                        "\r\n source: $lastSource" +
                        "\r\n url: $loadingSource" +
                        "\r\n svgaMemorySize: ${getSvgaMemorySizeFormat()}($svgaMemorySize Bytes)"
            )
        } else {
            //正常日志
            LogUtils.info(
                TAG, "================ start animation ================" +
                        "\r\n view: ${hashCode()}" +
                        "\r\n source: $lastSource" +
                        "\r\n url: $loadingSource" +
                        "\r\n svgaMemorySize: ${getSvgaMemorySizeFormat()}($svgaMemorySize Bytes)"
            )
        }

    }

    /**
     * 获取svga动画所占用的真实内存
     */
    fun getSvgaMemorySize(): Long {
        val svgaMemorySize = getSVGADrawable()?.videoItem?.getMemorySize() ?: 0
        val dynamicMemorySize = getSVGADrawable()?.dynamicItem?.getMemorySize() ?: 0
        return svgaMemorySize + dynamicMemorySize
    }

    /**
     * 获取svga动画所占用的内存格式化字符串
     */
    fun getSvgaMemorySizeFormat(): String {
        //格式化动画占用内存字符串，显示详细的内存占用情况
        val svgaMemorySize = getSvgaMemorySize()
        return Formatter.formatFileSize(context, svgaMemorySize)
    }

    private fun setupDrawable() {
        val drawable = getSVGADrawable() ?: return
        drawable.cleared = false
        drawable.scaleType = scaleType
        drawable.setVolume(volume)
    }

    private fun getSVGADrawable(): SVGADrawable? {
        return drawable as? SVGADrawable
    }

    /** 共享时钟每帧推进入口：无论可见与否都先推进帧，仅 onStep 回调受可见性约束 */
    internal fun tickerAdvanceFrame(frame: Int) {
        val drawable = getSVGADrawable() ?: return
        drawable.currentFrame = frame
        //没有 onStep 回调的动画（勋章等）无需做可见性判定，
        //原先每帧 getGlobalVisibleRect 的分配 + native 调用对这类场景是纯浪费
        val cb = callback ?: return
        if (!isGlobalVisible()) return
        val percentage =
            (frame + 1).toDouble() / drawable.videoItem.frames.toDouble()
        cb.onStep(frame, percentage)
    }

    /** 会话推进的前提：drawable 已被 clear/stop 时由对应路径摘除会话，这里双保险 */
    internal fun tickerHasDrawable(): Boolean {
        return getSVGADrawable() != null
    }

    //可见性矩形复用成员，避免每帧分配
    private val globalVisibleRect = Rect()

    private fun isGlobalVisible(): Boolean {
        if (!getGlobalVisibleRect(globalVisibleRect)) return false
        return globalVisibleRect.width() > 0 && globalVisibleRect.height() > 0
    }

    open fun onAnimationStart(animation: Animator?) {
        loadingSource = null
        isAnimating = true
        callback?.onStart()
    }

    open fun onAnimationRepeat(animation: Animator?) {
        callback?.onRepeat()
    }

    open fun onAnimationCancel(animation: Animator?) {
        isAnimating = false
        callback?.onCancel()
    }

    open fun onAnimationEnd(animation: Animator?) {
        if (isAnimating) {
            callback?.onFinished()
        }
        isAnimating = false
        stopAnimation()
        val drawable = getSVGADrawable()
        if (drawable != null) {
            when (fillMode) {
                FillMode.Backward -> {
                    drawable.currentFrame = mEndFrame
                }

                FillMode.Forward -> {
                    drawable.currentFrame = mStartFrame
                }

                FillMode.Clear -> {
                    drawable.cleared = true
                }
            }
        }
    }

    fun clear() {
        clear(keepPlaceholder = false)
    }

    /**
     * @param keepPlaceholder 当前 drawable 是静态占位图（非 SVGADrawable）时保留，
     *        待新加载的实体经 startAnimation 替换；SVGADrawable 无论开关与否照常清理
     */
    fun clear(keepPlaceholder: Boolean) {
        //clear 不保证伴随 stopAnimation（如列表不 detach 的 rebind、parserSource 预加载清理），
        //无限循环动画器需在此一并停掉，否则空转成僵尸直到下次成功播放才被回收。
        //共享时钟模式下等价操作：摘除会话
        SVGAAnimationTicker.stop(tickerSession)
        tickerSession = null
        //先取引用：setImageDrawable(null) 之后 getSVGADrawable() 返回 null
        val currentDrawable = getSVGADrawable()
        val currentVideoItem = currentDrawable?.videoItem
        currentDrawable?.cleared = true
        currentDrawable?.clear()
        //清理动态添加的数据
        currentDrawable?.dynamicItem?.clearDynamicObjects()
        // 清除对 drawable 的引用；仅保留静态占位图（加载中的占位由新实体到达时替换）
        if (!(keepPlaceholder && currentDrawable == null)) {
            setImageDrawable(null)
        }
        //归还实体绑定：末次释放者决定去向（无 key 立即回收 / 有 key 归还强字节缓存）
        currentVideoItem?.let(::releaseVideoItem)
        if (loadJob?.isActive == true) loadJob?.cancel()
        loadJob = null
        //挂起的加载回调一并失效，保证 clear 后可以重新加载
        loadCallback?.cancel()
        loadCallback = null
        //复位加载来源，避免下次 bind 出现“看似在加载但 loadCallback 已清空”的脆弱状态
        loadingSource = null
        isAnimating = false
        LogUtils.debug(TAG, "clear : $lastSource")
    }

    fun clearLastSource() {
        LogUtils.debug(TAG, "clear last source: $lastSource")
        lastSource = null
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            if (isAnimating) {
                resumeAnimation()
            }
        } else {
            if (isAnimating) {
                //这里暂停动画不改变动画状态，用于恢复可见后恢复动画
                SVGAAnimationTicker.pause(tickerSession)
                getSVGADrawable()?.pause()
            }
        }
    }

    /**
     * 设置动画音量
     */
    fun setVolume(volume: Float) {
        val fixVolume = volume.coerceIn(0f, 1f)
        this.volume = fixVolume
        getSVGADrawable()?.setVolume(fixVolume)
    }

    open fun pauseAnimation() {
        SVGAAnimationTicker.pause(tickerSession)
        getSVGADrawable()?.pause()
        callback?.onPause()
        isAnimating = false
    }

    open fun resumeAnimation() {
        getSVGADrawable()?.resume()
        callback?.onResume()
        if (tickerSession == null) {
            play(null, false)
        } else {
            SVGAAnimationTicker.resume(tickerSession)
        }
        isAnimating = true
    }

    fun stopAnimation() {
        stopAnimation(clear = clearsAfterStop)
    }

    fun stopAnimation(clear: Boolean) {
        SVGAAnimationTicker.stop(tickerSession)
        tickerSession = null
        getSVGADrawable()?.stop()
        getSVGADrawable()?.cleared = clear
        if (clear) {
            getSVGADrawable()?.clear()
        }
        isAnimating = false
    }

    fun setVideoItem(videoItem: SVGAVideoEntity?) {
        setVideoItem(videoItem, SVGADynamicEntity(context))
    }

    fun setVideoItem(videoItem: SVGAVideoEntity?, dynamicItem: SVGADynamicEntity?) {
        setVideoItemInternal(videoItem, dynamicItem, preAcquired = false)
    }

    internal fun setVideoItemInternal(
        videoItem: SVGAVideoEntity?,
        dynamicItem: SVGADynamicEntity?,
        preAcquired: Boolean
    ) {
        //先归还当前实体的绑定，保证引用计数与 view 的实际持有关系一致
        getSVGADrawable()?.videoItem?.let(::releaseVideoItem)
        //"可用检查 + acquire"原子完成：已被释放（bitmap 已回收）的实体不可再绑定，
        //同时杜绝与驱逐路径"判定无绑定 → 标记回收"的交错（见 SVGAVideoEntity.acquireIfUsable）
        if (videoItem == null || !(preAcquired || videoItem.acquireIfUsable())) {
            LogUtils.info(
                TAG,
                "setVideoItem drop: entity released before bind, source = $lastSource"
            )
            setImageDrawable(null)
            return
        }
        val drawable = SVGADrawable(videoItem, dynamicItem)
        dynamicItem?.invalidateCallback = {
            postInvalidate()
        }
        drawable.cleared = true
        setImageDrawable(drawable)
    }

    /**
     * 归还实体绑定（引用计数 -1）。
     * 末次释放者决定实体去向：无内存缓存 key 的立即回收（历史行为）；
     * 有 key 的归还强字节缓存复用（put-if-absent，缓存已有更新的在途实体时不覆盖），
     * 由 [SVGAMemoryCache] 超限驱逐时统一释放。
     */
    private fun releaseVideoItem(videoItem: SVGAVideoEntity) {
        if (!videoItem.release()) return
        val cacheKey = videoItem.getMemoryCacheKey()
        if (cacheKey.isNullOrEmpty()) {
            videoItem.clear()
        } else {
            SVGAMemoryCache.INSTANCE.returnToCache(cacheKey, videoItem)
        }
    }

    fun stepToFrame(frame: Int, andPlay: Boolean) {
        stopAnimation(false)
        val drawable = getSVGADrawable()
        if (drawable == null) {
            if (width > 0 && height > 0) {
                lastSource?.let {
                    markParserSourceTriggered()
                    loadScope.launch(Dispatchers.IO) {
                        parserSource(it, lastConfig)
                    }
                }
            }
            return
        }
        drawable.currentFrame = frame
        if (andPlay) {
            startAnimation()
            //对齐原实现对 ValueAnimator.currentPlayTime 的设置：把播放头拨到指定帧
            tickerSession?.let {
                SVGAAnimationTicker.seekToFrame(it, frame, drawable.videoItem.frames)
            }
        }
    }

    //最近一次触发 parserSource 的时间戳（主线程维护），供 onLayout 抑制“onAttachedToWindow → onLayout”双发
    private var lastParserSourceTriggerAt = 0L

    private fun markParserSourceTriggered() {
        lastParserSourceTriggerAt = android.os.SystemClock.uptimeMillis()
    }

    fun stepToPercentage(percentage: Double, andPlay: Boolean) {
        val drawable = drawable as? SVGADrawable ?: return
        var frame = (drawable.videoItem.frames * percentage).toInt()
        if (frame >= drawable.videoItem.frames && frame > 0) {
            frame = drawable.videoItem.frames - 1
        }
        stepToFrame(frame, andPlay)
    }

    fun setOnAnimKeyClickListener(clickListener: SVGAClickAreaListener) {
        mItemClickAreaListener = clickListener
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action != MotionEvent.ACTION_DOWN) {
            return super.onTouchEvent(event)
        }
        val drawable = getSVGADrawable() ?: return super.onTouchEvent(event)
        drawable.dynamicItem?.mClickMap?.apply {
            for ((key, value) in this) {
                if (event.x >= value[0]
                    && event.x <= value[2]
                    && event.y >= value[1]
                    && event.y <= value[3]
                ) {
                    mItemClickAreaListener?.let {
                        it.onClick(key)
                        return true
                    }
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        //重建被上一次 detach 取消的加载作用域，保证本窗口周期内的加载协程可用
        loadScope = MainScope()
        stepToFrame(0, lastConfig?.autoPlay ?: mAutoPlay)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        //先取消加载作用域再清理：在途 parserSource 在下一个挂起点（withContext(Main){clear()}）终止，
        //已排队但未执行的 clear 块也会被取消，杜绝 detach 后重装 loadCallback 的"复活解码"
        loadScope.cancel()
        stopAnimation(clearsAfterDetached)
        if (clearsAfterDetached) {
            clear()
        }
        if (clearsLastSourceOnDetached) {
            clearLastSource()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed && width > 0 && height > 0 && lastSource != null && !isAnimating
            //抑制 onAttachedToWindow(stepToFrame) → onLayout 的重复触发，避免两个 parserSource 并发同 view 时丢 cb
            && android.os.SystemClock.uptimeMillis() - lastParserSourceTriggerAt > 100
        ) {
            markParserSourceTriggered()
            loadScope.launch(Dispatchers.IO) {
                parserSource(lastSource, lastConfig)
            }
        }
    }

    /** 判断是否重新播放原有资源，true：重新播放 */
    private fun isReplayDrawable(source: String?): Boolean {
        //对比上次加载的资源地址
        if (lastSource != source || source.isNullOrEmpty()) return false
        //获取原有drawable
        val drawable = drawable as? SVGADrawable ?: return false
        //被清理的drawable不需要重新加载
        if (drawable.cleared) return false
        //存在dynamicItem，因为可能前后两次存在差异，需要重新加载数据
        if (drawable.dynamicItem != null && dynamicBlock != null) {
            val dynamicItem = SVGADynamicEntity(context)
            dynamicBlock?.let { dynamicItem.it() }
            drawable.updateDynamicItem(dynamicItem)
        }
        //动画是否正在执行
        if (!isAnimating) {
            startAnimation()
        }
        return true
    }

    fun getLastSource(): String? {
        return lastSource
    }
}