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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class MediaCache(private val url: String, cachePath: String) : MediaDataSource() {

    private var cacheCallback: CacheCallback? = null
    private var mediaDataSource: MediaDataSource = HttpMediaDataSource(url)
    private val mediaExtractor = MediaExtractor()
    private val localCache = LocalCache(cachePath)
    private val playFile = localCache.getPlayFile(url)
    private val randomAccessFile = RandomAccessFile(playFile, "rw")
    private val config = CopyOnWriteArrayList<Range<Long>>()
    private var currentTimeUs: Long = 0L
    private var isMetaComplete = false
    private val handler = Handler(Looper.getMainLooper())
    private val coroutine = CoroutineScope(Dispatchers.IO)

    init {
        localCache.getIndexConfig(url)?.let {
            config.addAll(it)
        }
    }

    fun start() {
        coroutine.launch {
            val proxyServer = ServerSocket(55555)
            var job: Job? = null
            while (true) {
                val client = proxyServer.accept()
                job?.cancel()
                job = coroutine.launch {
                    handle(client)
                }
            }
        }
//        CoroutineScope(Dispatchers.IO).launch {
//            while (true) {
//                runCatching {
//                    updateStatue()
//                    delay(500)
//                }
//            }
//        }
        coroutine.launch {
            try {
                mediaExtractor.setDataSource("http://127.0.0.1:55555")
                isMetaComplete = true
                Log.d(TAG, "meta data load complete")
//               delay(1000)
//               handler.post {
//                   val videoTrackIndex = (0 until mediaExtractor.trackCount).find { i ->
//                       val format = mediaExtractor.getTrackFormat(i)
//                       val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
//                       mime.startsWith("video/")
//                   } ?: 0
//                   mediaExtractor.selectTrack(videoTrackIndex)
//                   seekTo(259722684 )
//
//                   val buffer = ByteBuffer.allocate(65536)
//                   val size = mediaExtractor.readSampleData(buffer, 0)
//                   mediaExtractor.advance()
//                   println("${mediaExtractor.sampleTime}==============$size")
//               }
            } catch (e: Throwable) {
                callOnError(ErrorCode.DOWNLOAD_ERROR)
            }
        }
    }

    fun handle(client: Socket) {
        val reader = client.getInputStream().bufferedReader()
        var rangeStart = 0L
        var rangeEnd = -1L
        var line: String?
        do {
            line = reader.readLine()
            if (line?.startsWith("Range:") == true) {
                val r = line.substringAfter("bytes=").trim().split("-")
                rangeStart = r[0].toLongOrNull() ?: 0
                rangeEnd = if (r.size > 1) r[1].toLongOrNull() ?: -1 else -1
            }
        } while (line?.isNotEmpty() == true)
        if (rangeEnd == -1L) {
            rangeEnd = Long.MAX_VALUE
        }

        for (position in rangeStart..rangeEnd step 2024) {
            val buffer = ByteArray(65536)
            val size = readAt(position, ByteArray(65536), 0, 2024)
            if (size <= 0) break
            val outputStream = client.getOutputStream()
            outputStream.write(buffer, 0, size)
            outputStream.flush()
            println("==============$position")
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
                synchronized(randomAccessFile) {
                    randomAccessFile.seek(position)
                    randomAccessFile.write(buffer, offset, size)
                }
                saveConfig(position, upper)
                size
            } else {
                synchronized(randomAccessFile) {
                    randomAccessFile.seek(position)
                    randomAccessFile.read(buffer, offset, size)
                }
            }
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
        println("==111==========" + mediaExtractor.cachedDuration)
        val playFile = playFile.absolutePath
        val cachedDuration = mediaExtractor.cachedDuration
        if (cachedDuration <= 0) {
            handler.post {
                cacheCallback?.onBufferingLack()
            }
        } else if (cachedDuration > 3_000_000) {
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

        if (isComplete()) {
            handler.post {
                cacheCallback?.onComplete(playFile)
            }
        }
    }

    // 是否加载完成
    private fun isComplete(): Boolean {
        var isComplete = true
        for ((index, range) in config.withIndex()) {
            if (index < config.size - 1 && range.upper < config[index + 1].lower) {
                isComplete = false
                break
            }
        }
        return isComplete
    }

    // 是否是缺失的分片
    private fun isMissingRange(rang: Range<Long>): Boolean {
        return config.indexOfFirst { it.contains(rang) } == -1
    }

    // 保存配置
    private fun saveConfig(lower: Long, upper: Long) {
        val newRange = Range(lower, upper)
        val allRanges = config.toMutableList()
        allRanges.add(newRange)
        allRanges.sortBy { it.lower }

        val merged = mutableListOf<Range<Long>>()
        var current = allRanges[0]

        for (i in 1 until allRanges.size) {
            val next = allRanges[i]
            if (next.lower <= current.upper + 1) {
                current = Range(current.lower, maxOf(current.upper, next.upper))
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)

        config.clear()
        config.addAll(merged)
        Log.d(TAG, "saveConfig: $config")
        localCache.saveIndexConfig(url, config)
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