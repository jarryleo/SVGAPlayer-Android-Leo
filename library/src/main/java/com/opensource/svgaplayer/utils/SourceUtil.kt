package com.opensource.svgaplayer.utils

import android.net.Uri
import java.io.File

/**
 * @Author     :Leo
 * Date        :2024/12/27
 * Description :
 */
object SourceUtil {
    fun isUrl(text: String?): Boolean {
        if (text == null) return false
        return try {
            val uri = kotlin.runCatching { Uri.parse(text) }.getOrNull()
            val scheme = uri?.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") {
                return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    fun isFilePath(text: String?): Boolean {
        if (text == null) return false
        val fileExists = kotlin.runCatching { File(text) }.getOrNull()?.exists() ?: false
        if (fileExists) {
            return true
        }
        val uri = kotlin.runCatching { Uri.parse(text) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        return when (scheme) {
            "file" -> true
            "content" -> true
            /*{
                val path = kotlin.runCatching {
                    context.contentResolver.query(uri, null, null, null, null)
                }.getOrNull()?.use {
                    it.getString(it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA))
                }
                path != null && File(path).exists()
            }*/
            else -> false
        }
    }
}