package com.opensource.svgaplayer.bitmap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 通过文件解码 Bitmap
 *
 * Create by im_dsd 2020/7/7 17:50
 */
internal object SVGABitmapUrlDecoder : SVGABitmapDecoder<URL>() {
    private const val TIMEOUT = 30_000

    // 创建信任所有证书的 TrustManager
    val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    // 创建 SSL Context
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, trustAllCerts, SecureRandom())
    }

    // 创建 HostnameVerifier
    val hostnameVerifier = HostnameVerifier { _, _ -> true }

    override fun onDecode(data: URL, ops: BitmapFactory.Options): Bitmap? {
        (data.openConnection() as? HttpURLConnection)?.let { connection ->
            try {
                // 如果是 HTTPS 连接，设置 SSL 忽略证书验证
                if (connection is HttpsURLConnection) {
                    connection.sslSocketFactory = sslContext.socketFactory
                    connection.hostnameVerifier = hostnameVerifier
                }

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