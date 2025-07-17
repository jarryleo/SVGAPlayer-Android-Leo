package com.opensource.svgaplayer

import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import com.opensource.svgaplayer.url.UrlDecoderManager
import com.opensource.svgaplayer.utils.SVGARange
import com.opensource.svgaplayer.utils.SourceUtil
import com.opensource.svgaplayer.utils.log.LogUtils
import kotlinx.coroutines.Job
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
) : ImageView(context, attrs, defStyleAttr) {

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
    var fillMode: FillMode = FillMode.Backward
    var callback: SVGACallback? = null

    private var mAnimator: ValueAnimator? = null
    private var mItemClickAreaListener: SVGAClickAreaListener? = null
    private var mAntiAlias = true
    private var mAutoPlay = true
    private val mAnimatorListener by lazy {
        AnimatorListener(WeakReference(this))
    }
    private val mAnimatorUpdateListener by lazy {
        AnimatorUpdateListener(WeakReference(this))
    }
    private var mStartFrame = 0
    private var mEndFrame = 0
    private var volume = 1f

    private var lastSource: String? = null
    private var loadingSource: String? = null
    private var lastConfig: SVGAConfig? = null
    private var loadJob: Job? = null
    private var dynamicBlock: (SVGADynamicEntity.() -> Unit)? = null
    internal var onError: ((SVGAImageView) -> Unit)? = {}

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
        dynamicBlock: (SVGADynamicEntity.() -> Unit)? = null
    ): SVGAImageView {
        this.visibility = View.VISIBLE
        this.dynamicBlock = dynamicBlock
        this.onError = onError
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
            parserSource(source, config)
        } else {
            requestLayout()
        }
        return this
    }

    private fun parserSource(source: String?, config: SVGAConfig? = lastConfig) {
        if (source.isNullOrEmpty()) return
        //设置动画属性
        loops = config?.loopCount ?: loops
        mAutoPlay = config?.autoPlay ?: true
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
        var parser = SVGAParser.shareParser()
        if (parser == null) {
            SVGAParser.init(context.applicationContext)
            parser = SVGAParser.shareParser()
        }
        if (SourceUtil.isUrl(realUrl)) {
            val decode = try {
                URLDecoder.decode(realUrl, "UTF-8")
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
            if (loadingSource == realUrl && loadJob?.isActive == true) {
                return
            }
            loadingSource = realUrl
            clear()
            LogUtils.debug(TAG, "load from url: $realUrl , last source: $lastSource")
            loadJob = parser?.decodeFromURL(
                url,
                config = cfg ?: SVGAConfig(frameWidth = width, frameHeight = height),
                SVGAViewLoadCallback(this)
            )
        } else if (SourceUtil.isFilePath(realUrl)) {
            if (loadingSource == realUrl && loadJob?.isActive == true) {
                return
            }
            loadingSource = realUrl
            clear()
            LogUtils.debug(TAG, "load from file: $realUrl , last source: $lastSource")
            loadJob = parser?.decodeFromFile(
                realUrl,
                config = cfg ?: SVGAConfig(frameWidth = width, frameHeight = height),
                SVGAViewLoadCallback(this)
            )
        } else {
            if (loadingSource == realUrl && loadJob?.isActive == true) {
                return
            }
            loadingSource = realUrl
            clear()
            LogUtils.debug(TAG, "load from assert: $realUrl , last source: $lastSource")
            loadJob = parser?.decodeFromAssets(
                realUrl,
                config = cfg ?: SVGAConfig(frameWidth = width, frameHeight = height),
                SVGAViewLoadCallback(this)
            )
        }
    }


    fun startAnimation(videoItem: SVGAVideoEntity) {
        post {
            stopAnimation()
            videoItem.antiAlias = mAntiAlias
            val dynamicItem = SVGADynamicEntity(context)
            setVideoItem(videoItem, dynamicItem)
            dynamicBlock?.let { dynamicItem.it() }
            getSVGADrawable()?.scaleType = scaleType
            if (mAutoPlay) {
                play(null, false)
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
        val animator = ValueAnimator.ofInt(mStartFrame, mEndFrame)
        animator.interpolator = LinearInterpolator()
        animator.duration =
            ((mEndFrame - mStartFrame + 1) * (1000 / videoItem.FPS) / generateScale()).toLong()
        animator.repeatCount = if (loops <= 0) ValueAnimator.INFINITE else loops - 1
        if (mAnimatorUpdateListener.weakView.get() == null) {
            mAnimatorUpdateListener.weakView = WeakReference(this)
        }
        animator.addUpdateListener(mAnimatorUpdateListener)
        if (mAnimatorListener.weakView.get() == null) {
            mAnimatorListener.weakView = WeakReference(this)
        }
        animator.addListener(mAnimatorListener)
        LogUtils.info(
            TAG, "================ start animation ================ " +
                    "\r\n source: $lastSource" +
                    "\r\n url: $loadingSource" +
                    "\r\n svgaMemorySize: ${getSvgaMemorySizeFormat()}(${getSvgaMemorySize()} Bytes)"
        )
        if (reverse) {
            animator.reverse()
        } else {
            animator.start()
        }
        mAnimator = animator
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

    private fun generateScale(): Double {
        var scale = 1.0
        try {
            val animatorClass = Class.forName("android.animation.ValueAnimator") ?: return scale
            val getMethod = animatorClass.getDeclaredMethod("getDurationScale") ?: return scale
            scale = (getMethod.invoke(animatorClass) as Float).toDouble()
            if (scale == 0.0) {
                val setMethod =
                    animatorClass.getDeclaredMethod("setDurationScale", Float::class.java)
                        ?: return scale
                setMethod.isAccessible = true
                setMethod.invoke(animatorClass, 1.0f)
                scale = 1.0
                LogUtils.info(
                    TAG,
                    "The animation duration scale has been reset to" +
                            " 1.0x, because you closed it on developer options."
                )
            }
        } catch (ignore: Exception) {
            ignore.printStackTrace()
        }
        return scale
    }

    private fun onAnimatorUpdate(animator: ValueAnimator?) {
        val drawable = getSVGADrawable() ?: return
        if (!isVisible()) return
        drawable.currentFrame = animator?.animatedValue as Int
        val percentage =
            (drawable.currentFrame + 1).toDouble() / drawable.videoItem.frames.toDouble()
        callback?.onStep(drawable.currentFrame, percentage)
    }

    open fun onAnimationStart(animation: Animator?) {
        loadingSource = null
        isAnimating = true
        callback?.onStart()
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
            drawable.unloadSound() //播放完一次后释放音频资源
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
        getSVGADrawable()?.cleared = true
        getSVGADrawable()?.clear()
        //清理动态添加的数据
        getSVGADrawable()?.dynamicItem?.clearDynamicObjects()
        // 清除对 drawable 的引用
        setImageDrawable(null)
        if (loadJob?.isActive == true) loadJob?.cancel()
        loadJob = null
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
                pauseAnimation()
            }
        }
    }

    private fun isVisible(): Boolean {
        val visibleRect = Rect()
        getGlobalVisibleRect(visibleRect)
        return visibleRect.width() > 0 && visibleRect.height() > 0
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
        mAnimator?.pause()
        getSVGADrawable()?.pause()
        callback?.onPause()
    }

    open fun resumeAnimation() {
        mAnimator?.resume()
        getSVGADrawable()?.resume()
        callback?.onResume()
    }

    fun stopAnimation() {
        stopAnimation(clear = clearsAfterStop)
    }

    fun stopAnimation(clear: Boolean) {
        mAnimator?.cancel()
        mAnimator?.removeAllListeners()
        mAnimator?.removeAllUpdateListeners()
        mAnimator = null
        getSVGADrawable()?.stop()
        getSVGADrawable()?.cleared = clear
        if (clear) {
            getSVGADrawable()?.clear()
        }
    }

    fun setVideoItem(videoItem: SVGAVideoEntity?) {
        setVideoItem(videoItem, SVGADynamicEntity(context))
    }

    fun setVideoItem(videoItem: SVGAVideoEntity?, dynamicItem: SVGADynamicEntity?) {
        if (videoItem == null) {
            setImageDrawable(null)
        } else {
            val drawable = SVGADrawable(videoItem, dynamicItem)
            dynamicItem?.invalidateCallback = {
                postInvalidate()
            }
            drawable.cleared = true
            setImageDrawable(drawable)
        }
    }

    fun stepToFrame(frame: Int, andPlay: Boolean) {
        stopAnimation(false)
        val drawable = getSVGADrawable()
        if (drawable == null) {
            if (width > 0 && height > 0) {
                lastSource?.let {
                    parserSource(it, lastConfig)
                }
            }
            return
        }
        drawable.currentFrame = frame
        if (andPlay) {
            startAnimation()
            mAnimator?.let {
                it.currentPlayTime = (0.0f.coerceAtLeast(
                    1.0f.coerceAtMost((frame.toFloat() / drawable.videoItem.frames.toFloat()))
                ) * it.duration).toLong()
            }
        }
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
        stepToFrame(0, lastConfig?.autoPlay ?: mAutoPlay)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation(clearsAfterDetached)
        if (clearsAfterDetached) {
            clear()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed && width > 0 && height > 0 && lastSource != null && !isAnimating) {
            parserSource(lastSource, lastConfig)
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

    private class AnimatorListener(var weakView: WeakReference<SVGAImageView>) :
        Animator.AnimatorListener {
        override fun onAnimationRepeat(animation: Animator) {
            val svgaImageView = weakView.get()
            if (svgaImageView == null) {
                animation.removeListener(this)
            } else {
                svgaImageView.callback?.onRepeat()
            }
        }

        override fun onAnimationEnd(animation: Animator) {
            val svgaImageView = weakView.get()
            if (svgaImageView == null) {
                animation.removeListener(this)
            } else {
                svgaImageView.onAnimationEnd(animation)
            }
        }

        override fun onAnimationCancel(animation: Animator) {
            val svgaImageView = weakView.get()
            if (svgaImageView == null) {
                animation.removeListener(this)
            } else {
                svgaImageView.onAnimationCancel(animation)
            }
        }

        override fun onAnimationStart(animation: Animator) {
            val svgaImageView = weakView.get()
            if (svgaImageView == null) {
                animation.removeListener(this)
            } else {
                svgaImageView.onAnimationStart(animation)
            }
        }
    } // end of AnimatorListener


    private class AnimatorUpdateListener(var weakView: WeakReference<SVGAImageView>) :
        ValueAnimator.AnimatorUpdateListener {
        override fun onAnimationUpdate(animation: ValueAnimator) {
            val svgaImageView = weakView.get()
            if (svgaImageView == null) {
                animation.removeUpdateListener(this)
            } else {
                svgaImageView.onAnimatorUpdate(animation)
            }
        }
    } // end of AnimatorUpdateListener
}