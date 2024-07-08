package com.opensource.svgaplayer.bitmap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL

/**
 * 通过文件解码 Bitmap
 *
 * Create by im_dsd 2020/7/7 17:50
 */
internal object SVGABitmapUrlDecoder : SVGABitmapDecoder<URL>() {
    private const val TIMEOUT = 30_000
    override fun onDecode(data: URL, ops: BitmapFactory.Options): Bitmap? {
        (data.openConnection() as? HttpURLConnection)?.let { connection ->
            try {
                connection.connectTimeout = TIMEOUT
                connection.readTimeout = TIMEOUT
                connection.requestMethod = "GET"
                connection.setRequestProperty("Connection", "close")
                connection.connect()
                connection.inputStream.use { stream ->
                    return BitmapFactory.decodeStream(stream, null, ops)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    connection.disconnect()
                } catch (disconnectException: Throwable) {
                    // ignored here
                }
            }
        }
        return null
    }
}