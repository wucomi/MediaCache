package com.hik.media.local

import android.util.Log
import android.util.Range
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 本地缓存管理
 *
 * 目录结构：
 * /video_cache/{video_hash}/
 * ├── index.json       # 分片配置
 * └── video.mp4        # 播放文件（稀疏文件）
 */
class LocalCache(cachePath: String) {

    companion object {
        private const val TAG = "LocalCache"
        private const val CACHE_DIR = "video_cache"
        private const val INDEX_FILE = "index.json"
        private const val VIDEO_FILE = "video.mp4"
    }

    private val gson: Gson = GsonBuilder().create()

    private val cacheDir = File(cachePath, CACHE_DIR).apply {
        if (!exists()) mkdirs()
    }

    /**
     * 获取磁盘剩余空间
     */
    fun getAvailableSpace(): Long {
        return cacheDir.freeSpace
    }

    /**
     * 检查磁盘空间是否足够
     */
    fun hasEnoughSpace(requiredSpace: Long): Boolean {
        return getAvailableSpace() >= requiredSpace
    }

    /**
     * 获取视频缓存目录
     */
    fun getCacheDir(videoUrl: String): File {
        val id = md5(videoUrl)
        return File(cacheDir, id).apply { mkdirs() }
    }

    /**
     * 获取video.mp4文件路径
     */
    fun getPlayFile(videoUrl: String): File {
        val dir = getCacheDir(videoUrl)
        val file = File(dir, VIDEO_FILE)
        if (!file.exists()) {
            try {
                file.createNewFile()
            } catch (e: Exception) {
                Log.e(TAG, "创建播放文件失败: ${e.message}")
            }
        }
        return file
    }

    /**
     * 创建播放文件
     */
    fun createPlayFile(
        videoUrl: String,
        totalSize: Long
    ): File {
        val file = getPlayFile(videoUrl)
        RandomAccessFile(file, "rw").use { it.setLength(totalSize) }
        return file
    }

    /**
     * 获取index.json文件路径
     */
    fun getIndexFile(videoUrl: String): File {
        return File(getCacheDir(videoUrl), INDEX_FILE)
    }

    /**
     * 获取Index配置
     */
    fun getIndexConfig(videoUrl: String): List<Range<Long>>? {
        val file = getIndexFile(videoUrl)
        synchronized(file) {
            if (!file.exists()) return null
            try {
                return gson.fromJson(
                    file.readText(),
                    object : TypeToken<List<Range<Long>>>() {}.type
                )
            } catch (e: Exception) {
                Log.e(TAG, "读取index.json失败: ${e.message}")
                return null
            }
        }
    }

    /**
     * 保存索引配置
     */
    fun saveIndexConfig(
        videoUrl: String,
        config: List<Range<Long>>
    ): Boolean {
        val file = getIndexFile(videoUrl)
        synchronized(file) {
            try {
                file.writeText("[${config.joinToString()}]")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "保存index.json失败: ${e.message}")
                return false
            }
        }
    }

    /**
     * 删除所有缓存配置
     */
    fun delete(videoUrl: String) {
        val dir = getCacheDir(videoUrl)
        dir.deleteRecursively()
    }

    private fun md5(url: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
