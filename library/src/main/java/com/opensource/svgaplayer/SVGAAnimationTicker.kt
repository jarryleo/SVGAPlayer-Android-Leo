package com.opensource.svgaplayer

import android.view.Choreographer
import com.opensource.svgaplayer.utils.log.LogUtils
import java.lang.ref.WeakReference

/**
 * 全局共享动画时钟。
 *
 * 取代"每个 SVGAImageView 一个 ValueAnimator"的驱动模型：所有正在播放的 SVGA 动画
 * 由同一个 Choreographer 回调统一推进，N 个动画只占用 1 个每帧回调，降低多勋章、
 * 多麦位等大量同屏动画场景下的帧回调派发开销。
 *
 * 时间语义对齐原 ValueAnimator 实现：
 * - 单轮时长 = (endFrame - startFrame + 1) * 1000 / FPS
 * - loops <= 0 视为无限循环；loops > 0 播满 loops 轮后走 onAnimationEnd 结束路径
 * - reverse 播放为 endFrame -> startFrame
 * - 每帧都推进 drawable.currentFrame（不可见也推进），仅 onStep 回调受可见性约束
 *
 * 仅允许主线程调用（与原 ValueAnimator 的使用方式一致）。
 */
object SVGAAnimationTicker {

    private const val TAG = "SVGAAnimationTicker"

    /** 单个动画会话，持有 view 的弱引用 */
    class Session internal constructor(
        internal val viewRef: WeakReference<SVGAImageView>,
        internal val startFrame: Int,
        internal val endFrame: Int,
        internal val loops: Int,
        internal val reverse: Boolean,
        internal val durationMs: Double,
    ) {
        internal var elapsedBaseMs = 0.0
        internal var resumeAtNanos = 0L
        internal var pausedElapsedMs = 0.0
        internal var paused = false
        internal var lastLoopIndex = 0
    }

    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }
    private val sessions = ArrayList<Session>(16)
    private var posted = false

    private val frameCallback = Choreographer.FrameCallback {
        posted = false
        tick()
        if (sessions.isNotEmpty()) {
            post()
        }
    }

    /** 注册并立即开始一个动画会话；同一 view 重复注册时替换旧会话 */
    fun start(
        view: SVGAImageView,
        startFrame: Int,
        endFrame: Int,
        fps: Int,
        loops: Int,
        reverse: Boolean,
    ): Session {
        sessions.removeAll { it.viewRef.get() === view }
        val safeFps = if (fps > 0) fps else 20
        val frameCount = (endFrame - startFrame + 1).coerceAtLeast(1)
        val session = Session(
            viewRef = WeakReference(view),
            startFrame = startFrame,
            endFrame = endFrame,
            loops = loops,
            reverse = reverse,
            durationMs = frameCount * (1000.0 / safeFps),
        )
        session.elapsedBaseMs = 0.0
        session.resumeAtNanos = System.nanoTime()
        sessions.add(session)
        post()
        LogUtils.debug(TAG, "session start, view = ${view.hashCode()}, frames = $startFrame..$endFrame, loops = $loops, reverse = $reverse, sessions = ${sessions.size}")
        return session
    }

    /** 静默停止会话，不触发任何回调 */
    fun stop(session: Session?) {
        if (session == null) return
        sessions.remove(session)
    }

    /** 暂停会话并保留进度 */
    fun pause(session: Session?) {
        if (session == null || session.paused) return
        session.pausedElapsedMs = elapsedMs(session)
        session.paused = true
    }

    /** 恢复暂停的会话 */
    fun resume(session: Session?) {
        if (session == null || !session.paused) return
        session.elapsedBaseMs = session.pausedElapsedMs
        session.resumeAtNanos = System.nanoTime()
        session.paused = false
    }

    /** 跳转到指定帧，对齐原实现对 ValueAnimator.currentPlayTime 的设置方式 */
    fun seekToFrame(session: Session, frame: Int, totalFrames: Int) {
        val fraction = (frame.toFloat() / totalFrames).coerceIn(0f, 1f)
        val targetMs = fraction * session.durationMs
        session.elapsedBaseMs = targetMs
        session.pausedElapsedMs = targetMs
        session.resumeAtNanos = System.nanoTime()
        session.lastLoopIndex = (targetMs / session.durationMs).toInt()
    }

    private fun post() {
        if (posted) return
        posted = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun elapsedMs(session: Session): Double {
        return session.elapsedBaseMs + (System.nanoTime() - session.resumeAtNanos) / 1_000_000.0
    }

    private fun tick() {
        if (sessions.isEmpty()) return
        //快照遍历：onAnimationEnd/onStep 等回调里可能注册或停止其他会话，
        //避免遍历 sessions 期间被回调并发修改
        val snapshot = sessions.toTypedArray()
        for (session in snapshot) {
            //已被 stop 或替换掉的会话不再推进
            if (!sessions.contains(session)) continue
            val view = session.viewRef.get()
            if (view == null || !view.tickerHasDrawable()) {
                sessions.remove(session)
                continue
            }
            if (session.paused) continue
            val position = elapsedMs(session) / session.durationMs
            if (session.loops > 0 && position >= session.loops) {
                //播满设定轮数，走与 ValueAnimator onAnimationEnd 相同的结束路径
                sessions.remove(session)
                view.onAnimationEnd(null)
                continue
            }
            val loopIndex = position.toInt()
            if (loopIndex > session.lastLoopIndex) {
                session.lastLoopIndex = loopIndex
                view.onAnimationRepeat(null)
            }
            val fraction = position - loopIndex
            val span = session.endFrame - session.startFrame
            val frame = if (session.reverse) {
                session.endFrame - (fraction * span).toInt()
            } else {
                session.startFrame + (fraction * span).toInt()
            }
            view.tickerAdvanceFrame(frame)
        }
    }
}
