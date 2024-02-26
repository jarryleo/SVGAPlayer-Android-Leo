package com.opensource.svgaplayer

import com.opensource.svgaplayer.utils.log.LogUtils
import java.net.URL

/**
 * @Author     :Leo
 * Date        :2024/2/26
 * Description : SVGAParser扩展
 */

fun SVGAImageView.loadUrl(
    url: String,
    useMemoryCache: Boolean = false,
    isOriginal: Boolean = false,
    loopCount: Int = 0,
    callback: SVGAParser.ParseCompletion? = SVGAPlayCallback(this, loopCount),
) {
    if (url.isEmpty()) {
        return
    }
    if (isReplayDrawable(url)) {
        return
    }

    val svgaParser = SVGAParser.shareParser()
    val urlSafe = try {
        URL(url)
    } catch (e: Exception) {
        LogUtils.error("SVGAImageView.loadUrl", e)
        return
    }
    post {
        svgaParser.decodeFromURL(
            urlSafe,
            config = SVGAConfig(
                frameWidth = if (isOriginal) 0 else measuredWidth,
                frameHeight = if (isOriginal) 0 else measuredHeight,
                isCacheToMemory = useMemoryCache
            ),
            callback,
            null
        )
        setTag(R.id.svga_load_tag, url)
    }
}

fun SVGAImageView.loadAssets(
    name: String,
    useMemoryCache: Boolean = false,
    isOriginal: Boolean = false,
    loopCount: Int = 0,
    callback: SVGAParser.ParseCompletion? = SVGAPlayCallback(this, loopCount),
) {
    if (name.isEmpty()) {
        return
    }
    if (isReplayDrawable(name)) {
        return
    }

    post {
        val svgaParser = SVGAParser.shareParser()
        svgaParser.decodeFromAssets(
            name,
            config = SVGAConfig(
                frameWidth = if (isOriginal) 0 else measuredWidth,
                frameHeight = if (isOriginal) 0 else measuredHeight,
                isCacheToMemory = useMemoryCache
            ), callback,
            null
        )
        setTag(R.id.svga_load_tag, name)
    }
}

/** 判断是否重新播放原有资源，true：重新播放 */
private fun SVGAImageView.isReplayDrawable(remoteUrl: String): Boolean {
    //获取上一次加载的地址
    val lastUrl = getTag(R.id.svga_load_tag) ?: false
    //对比两者差异
    if (lastUrl != remoteUrl) return false
    //获取原有drawable
    val drawable = drawable as? SVGADrawable ?: return false
    //存在dynamicItem，因为可能前后两次存在差异，需要重新加载数据
    if (drawable.dynamicItem != null) return false
    //动画是否正在执行
    if (isAnimating) return false
    startAnimation()
    return true
}