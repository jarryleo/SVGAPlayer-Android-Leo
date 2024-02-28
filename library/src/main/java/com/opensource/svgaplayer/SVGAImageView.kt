package com.opensource.svgaplayer

import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import com.opensource.svgaplayer.utils.SVGARange
import com.opensource.svgaplayer.utils.log.LogUtils
import java.lang.ref.WeakReference
import java.net.URL

/**
 * Created by PonyCui on 2017/3/29.
 */
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
    var fillMode: FillMode = FillMode.Forward
    var callback: SVGACallback? = null

    private var mAnimator: ValueAnimator? = null
    private var mItemClickAreaListener: SVGAClickAreaListener? = null
    private var mAntiAlias = true
    private var mAutoPlay = true
    private val mAnimatorListener = AnimatorListener(this)
    private val mAnimatorUpdateListener = AnimatorUpdateListener(this)
    private var mStartFrame = 0
    private var mEndFrame = 0

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
            parserSource(it)
        }
        typedArray.recycle()
    }

    private fun parserSource(source: String) {
        val refImgView = WeakReference<SVGAImageView>(this)
        val parser = SVGAParser.shareParser()
        if (source.startsWith("http://") || source.startsWith("https://")) {
            parser.decodeFromURL(
                URL(source),
                config = SVGAConfig(),
                createParseCompletion(refImgView)
            )
        } else {
            parser.decodeFromAssets(
                source,
                config = SVGAConfig(),
                createParseCompletion(refImgView)
            )
        }
    }

    private fun createParseCompletion(ref: WeakReference<SVGAImageView>): SVGAParser.ParseCompletion {
        return object : SVGAParser.ParseCompletion {
            override fun onComplete(videoItem: SVGAVideoEntity) {
                ref.get()?.startAnimation(videoItem)
            }

            override fun onError() {}
        }
    }

    private fun startAnimation(videoItem: SVGAVideoEntity) {
        this@SVGAImageView.post {
            videoItem.antiAlias = mAntiAlias
            setVideoItem(videoItem)
            getSVGADrawable()?.scaleType = scaleType
            if (mAutoPlay) {
                startAnimation()
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
        LogUtils.info(TAG, "================ start animation ================")
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
        animator.repeatCount = if (loops <= 0) 99999 else loops - 1
        animator.addUpdateListener(mAnimatorUpdateListener)
        animator.addListener(mAnimatorListener)
        if (reverse) {
            animator.reverse()
        } else {
            animator.start()
        }
        mAnimator = animator
    }

    private fun setupDrawable() {
        val drawable = getSVGADrawable() ?: return
        drawable.cleared = false
        drawable.scaleType = scaleType
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
        drawable.currentFrame = animator?.animatedValue as Int
        val percentage =
            (drawable.currentFrame + 1).toDouble() / drawable.videoItem.frames.toDouble()
        callback?.onStep(drawable.currentFrame, percentage)
    }

    private fun onAnimationEnd(animation: Animator?) {
        isAnimating = false
        stopAnimation()
        val drawable = getSVGADrawable()
        if (drawable != null) {
            when (fillMode) {
                FillMode.Backward -> {
                    drawable.currentFrame = mStartFrame
                }

                FillMode.Forward -> {
                    drawable.currentFrame = mEndFrame
                }

                FillMode.Clear -> {
                    drawable.cleared = true
                }
            }
        }
        callback?.onFinished()
    }

    fun clear() {
        getSVGADrawable()?.cleared = true
        getSVGADrawable()?.clear()
        //清理动态添加的数据
        getSVGADrawable()?.dynamicItem?.clearDynamicObjects()
        // 清除对 drawable 的引用
        setImageDrawable(null)
    }

    private fun isVisible(): Boolean {
        val visibleRect = Rect()
        getGlobalVisibleRect(visibleRect)
        return visibleRect.width() > 0 && visibleRect.height() > 0
    }

    fun pauseAnimation() {
        mAnimator?.pause()
        getSVGADrawable()?.pause()
        callback?.onPause()
    }

    fun resumeAnimation() {
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
        getSVGADrawable()?.stop()
        getSVGADrawable()?.cleared = clear
        if (clear) {
            getSVGADrawable()?.clear()
        }
    }

    fun setVideoItem(videoItem: SVGAVideoEntity?) {
        setVideoItem(videoItem, SVGADynamicEntity())
    }

    fun setVideoItem(videoItem: SVGAVideoEntity?, dynamicItem: SVGADynamicEntity?) {
        if (videoItem == null) {
            setImageDrawable(null)
        } else {
            val drawable = SVGADrawable(videoItem, dynamicItem)
            drawable.cleared = true
            setImageDrawable(drawable)
        }
    }

    fun stepToFrame(frame: Int, andPlay: Boolean) {
        stopAnimation(false)
        val drawable = getSVGADrawable() ?: return
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
                if (event.x >= value[0] && event.x <= value[2] && event.y >= value[1] && event.y <= value[3]) {
                    mItemClickAreaListener?.let {
                        it.onClick(key)
                        return true
                    }
                }
            }
        }


        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation(clearsAfterDetached)
        if (clearsAfterDetached) {
            clear()
        }
    }

    private class AnimatorListener(view: SVGAImageView) : Animator.AnimatorListener {
        private val weakReference = WeakReference<SVGAImageView>(view)

        override fun onAnimationRepeat(animation: Animator?) {
            weakReference.get()?.callback?.onRepeat()
        }

        override fun onAnimationEnd(animation: Animator?) {
            weakReference.get()?.onAnimationEnd(animation)
        }

        override fun onAnimationCancel(animation: Animator?) {
            weakReference.get()?.isAnimating = false
        }

        override fun onAnimationStart(animation: Animator?) {
            weakReference.get()?.isAnimating = true
        }
    } // end of AnimatorListener


    private class AnimatorUpdateListener(view: SVGAImageView) :
        ValueAnimator.AnimatorUpdateListener {
        private val weakReference = WeakReference<SVGAImageView>(view)

        override fun onAnimationUpdate(animation: ValueAnimator?) {
            weakReference.get()?.onAnimatorUpdate(animation)
        }
    } // end of AnimatorUpdateListener
}