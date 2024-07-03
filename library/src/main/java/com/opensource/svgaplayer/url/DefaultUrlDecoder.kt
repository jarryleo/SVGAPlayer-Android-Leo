package com.opensource.svgaplayer.url

/**
 * @Author     :Leo
 * Date        :2024/7/3
 * Description : 默认的url解码器
 */
class DefaultUrlDecoder : UrlDecoder {
    override fun decodeSvgaUrl(url: String, width: Int, height: Int): String {
        return url
    }

    override fun decodeImageUrl(url: String, width: Int, height: Int): String {
        return url
    }
}