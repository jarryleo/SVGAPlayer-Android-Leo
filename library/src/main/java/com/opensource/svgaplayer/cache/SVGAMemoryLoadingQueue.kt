package com.opensource.svgaplayer.cache

import com.opensource.svgaplayer.SVGAParser.ParseCompletion
import java.util.concurrent.ConcurrentHashMap

/**
 * @Author     :Leo
 * Date        :2024/12/3
 * Description :
 */
object SVGAMemoryLoadingQueue {

    class SVGAMemoryLoadingItem(
        val callback: ParseCompletion?,
    )

    private val loadingMap = ConcurrentHashMap<String, List<SVGAMemoryLoadingItem>>()

    fun inQueue(memoryCacheKey: String): Boolean {
        return loadingMap.containsKey(memoryCacheKey)
    }

    fun addItem(memoryCacheKey: String, item: SVGAMemoryLoadingItem) {
        val list = loadingMap[memoryCacheKey]?.toMutableList() ?: mutableListOf()
        list.add(item)
        loadingMap[memoryCacheKey] = list
    }

    fun removeItem(memoryCacheKey: String): List<SVGAMemoryLoadingItem>? {
        return loadingMap.remove(memoryCacheKey)
    }

}