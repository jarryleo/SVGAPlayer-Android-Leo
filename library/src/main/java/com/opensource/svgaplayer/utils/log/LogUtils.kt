package com.opensource.svgaplayer.utils.log

/**
 * 日志输出
 */
internal object LogUtils {
    private const val TAG = "SVGALog"

    fun verbose(tag: String = TAG, callback: () -> String) {
        if (!SVGALogger.isLogEnabled()) {
            return
        }
        SVGALogger.getSVGALogger()?.verbose(tag, callback.invoke())
    }

    fun info(tag: String = TAG, callback: () -> String) {
        if (!SVGALogger.isLogEnabled()) {
            return
        }
        SVGALogger.getSVGALogger()?.info(tag, callback.invoke())
    }

    fun debug(tag: String = TAG, callback: () -> String) {
        if (!SVGALogger.isLogEnabled()) {
            return
        }
        SVGALogger.getSVGALogger()?.debug(tag, callback.invoke())
    }

    fun warn(tag: String = TAG, callback: () -> String) {
        if (!SVGALogger.isLogEnabled()) {
            return
        }
        SVGALogger.getSVGALogger()?.warn(tag, callback.invoke())
    }

    fun error(tag: String = TAG, callback: () -> String) {
        if (!SVGALogger.isLogEnabled()) {
            return
        }
        SVGALogger.getSVGALogger()?.error(tag, callback.invoke(), null)
    }

    fun errorEx(tag: String, callback: () -> Throwable) {
        if (!SVGALogger.isLogEnabled()) {
            return
        }
        val error = callback.invoke()
        SVGALogger.getSVGALogger()?.error(tag, error.message, error)
    }

    fun error(tag: String = TAG, callback: () -> String, error: () -> Throwable) {
        if (!SVGALogger.isLogEnabled()) {
            return
        }
        SVGALogger.getSVGALogger()?.error(tag, callback.invoke(), error.invoke())
    }
}