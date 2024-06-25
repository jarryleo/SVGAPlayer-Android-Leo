package com.opensource.svgaplayer

import com.opensource.svgaplayer.utils.log.LogUtils
import java.net.URL

/**
 * @Author     :Leo
 * Date        :2024/2/26
 * Description : SVGAParser扩展
 */

@JvmOverloads
fun SVGAImageView.loadUrl(
    url: String,
    useMemoryCache: Boolean = false,
    isOriginal: Boolean = false,
    loopCount: Int = 0,
    dynamicCallback: SVGADynamicCallback = { null },
    callback: SVGAParser.ParseCompletion? = SVGAPlayCallback(this, loopCount, dynamicCallback),
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
    getViewSize { width, height ->
        svgaParser.decodeFromURL(
            urlSafe,
            config = SVGAConfig(
                frameWidth = if (isOriginal) 0 else width,
                frameHeight = if (isOriginal) 0 else height,
                isCacheToMemory = useMemoryCache
            ),
            callback,
            null
        )
        setTag(R.id.svga_load_tag, url)
    }
}

@JvmOverloads
fun SVGAImageView.loadAssets(
    name: String,
    useMemoryCache: Boolean = false,
    isOriginal: Boolean = false,
    loopCount: Int = 0,
    dynamicCallback: SVGADynamicCallback = { null },
    callback: SVGAParser.ParseCompletion? = SVGAPlayCallback(this, loopCount, dynamicCallback),
) {
    if (name.isEmpty()) {
        return
    }
    if (isReplayDrawable(name)) {
        return
    }

    getViewSize { width, height ->
        val svgaParser = SVGAParser.shareParser()
        svgaParser.decodeFromAssets(
            name,
            config = SVGAConfig(
                frameWidth = if (isOriginal) 0 else width,
                frameHeight = if (isOriginal) 0 else height,
                isCacheToMemory = useMemoryCache
            ), callback,
            null
        )
        setTag(R.id.svga_load_tag, name)
    }
}

private fun SVGAImageView.getViewSize(callback: (Int, Int) -> Unit) {
    var width = getTargetWidth()
    var height = getTargetHeight()
    if (width > 0 && height > 0) {
        callback.invoke(width, height)
        return
    }
    post {
        width = getTargetWidth()
        height = getTargetHeight()
        if (width > 0 && height > 0) {
            callback.invoke(width, height)
        } else {
            callback.invoke(0, 0)
        }
    }
}

private fun SVGAImageView.getTargetHeight(): Int {
    val verticalPadding = paddingTop + paddingBottom
    val layoutParamSize = layoutParams?.height ?: 0
    return getTargetDimen(height, layoutParamSize, verticalPadding)
}

private fun SVGAImageView.getTargetWidth(): Int {
    val horizontalPadding = paddingLeft + paddingRight
    val layoutParamSize = layoutParams?.width ?: 0
    return getTargetDimen(width, layoutParamSize, horizontalPadding)
}


private fun SVGAImageView.getTargetDimen(viewSize: Int, paramSize: Int, paddingSize: Int): Int {
    val adjustedParamSize = paramSize - paddingSize
    if (adjustedParamSize > 0) {
        return adjustedParamSize
    }
    val adjustedViewSize = viewSize - paddingSize
    if (adjustedViewSize > 0) {
        return adjustedViewSize
    }
    return 0
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