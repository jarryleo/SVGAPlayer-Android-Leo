package com.opensource.svgaplayer.url

/**
 * @Author     :Leo
 * Date        :2024/7/3
 * Description : url 解码和转换
 */
interface UrlDecoder {
    fun decodeSvgaUrl(url: String, width: Int, height: Int): String
    fun decodeImageUrl(url: String, width: Int, height: Int): String
}

object UrlDecoderManager {
    private var urlDecoder: UrlDecoder = DefaultUrlDecoder()

    fun setUrlDecoder(decoder: UrlDecoder) {
        urlDecoder = decoder
    }

    fun getUrlDecoder(): UrlDecoder {
        return urlDecoder
    }
}