package com.hik.media.cache

import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import com.hik.media.error.ErrorCode
import com.hik.media.local.LocalCache
import com.hik.media.source.HttpMediaDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class MediaCache(private val url: String, cachePath: String) : MediaDataSource() {

    private var cacheCallback: CacheCallback? = null
    private var mediaDataSource: MediaDataSource = HttpMediaDataSource(url)
    private val mediaExtractor = MediaExtractor()
    private val localCache = LocalCache(cachePath)
    private val coroutine = CoroutineScope(Dispatchers.IO)
    private val randomAccessFile = RandomAccessFile(localCache.getPlayFile(url), "rw")
    private val config = CopyOnWriteArrayList<Range<Long>>()
    private var currentTimeUs: Long = 0L
    private val handler = Handler(Looper.getMainLooper())

    init {
        thread {
            mediaExtractor.setDataSource(this)
        }
        coroutine.launch {
            localCache.getIndexConfig(url)?.let {
                config.addAll(it)
            }
        }
    }

    fun setCacheCallback(cacheCallback: CacheCallback) {
        this.cacheCallback = cacheCallback
    }

    fun setDataSource(dataSource: MediaDataSource) {
        this.mediaDataSource = dataSource
    }

    fun seekTo(timeUs: Long, mode: Int = SEEK_TO_PREVIOUS_SYNC) {
        this.currentTimeUs = timeUs
        updateStatue()
        mediaExtractor.seekTo(timeUs, mode)
    }

    override fun readAt(
        position: Long,
        buffer: ByteArray?,
        offset: Int,
        size: Int
    ): Int {
        try {
            val upper = position + size - 1
            val missingRange = isMissingRange(Range(position, upper))
            val size = if (missingRange) {
                val size = mediaDataSource.readAt(position, buffer, offset, size)
                randomAccessFile.let { raf ->
                    synchronized(raf) {
                        raf.seek(position)
                        raf.write(buffer, offset, size)
                    }
                }
                saveConfig(position, upper)
                size
            } else {
                randomAccessFile.seek(position)
                randomAccessFile.read(buffer, offset, size)
            }
            updateStatue()
            return size
        } catch (e: Throwable) {
            Log.e(TAG, "readAt失败", e)
            callOnError(ErrorCode.DOWNLOAD_ERROR)
        }
        return -1
    }

    override fun getSize(): Long {
        try {
            val size = mediaDataSource.size
            randomAccessFile.setLength(size)
            return size
        } catch (e: Throwable) {
            Log.e(TAG, "getSize失败", e)
            callOnError(ErrorCode.DOWNLOAD_ERROR)
        }
        return -1
    }

    override fun close() {
        try {
            randomAccessFile.close()
            coroutine.cancel()
            return mediaDataSource.close()
        } catch (e: Throwable) {
            Log.e(TAG, "close失败", e)
        }
    }

    // 更新缓冲状态
    private fun updateStatue() {
        val playFile = localCache.getPlayFile(url).absolutePath
        if (mediaExtractor.cachedDuration < 1) {
            handler.post {
                cacheCallback?.onBufferingLack()
            }
        } else if (mediaExtractor.cachedDuration > 3) {
            if (currentTimeUs == 0L) {
                handler.post {
                    cacheCallback?.onReady(playFile)
                }
            } else {
                handler.post {
                    cacheCallback?.onBufferingReady()
                }
            }
        }

        var isComplete = true
        for ((index, range) in config.withIndex()) {
            if (index < config.size - 1 && range.upper < config[index + 1].lower) {
                isComplete = false
                break
            }
        }
        if (isComplete) {
            handler.post {
                cacheCallback?.onComplete(playFile)
            }
        }
    }

    // 是否是缺失的分片
    private fun isMissingRange(rang: Range<Long>): Boolean {
        return config.indexOfFirst { it.contains(rang) } == -1
    }

    // 保存配置
    private fun saveConfig(lower: Long, upper: Long) {
        val range = Range(lower, upper)
        config.removeIf { range.contains(it) }
        val index = config.indexOfFirst { it.upper >= lower }
        config[index] = Range(config[index].lower, upper)
        if (index + 1 < config.size - 1 && config[index + 1].lower <= upper) {
            config[index] = Range(config[index].lower, config[index + 1].upper)
        }
        coroutine.launch {
            localCache.saveIndexConfig(url, config)
        }
    }

    private fun callOnError(code: Int) {
        handler.post {
            cacheCallback?.onError(code)
        }
    }

    companion object {
        const val TAG = "MediaCache"
    }
}