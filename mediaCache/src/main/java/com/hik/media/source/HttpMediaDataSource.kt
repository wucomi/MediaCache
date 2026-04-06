package com.hik.media.source

import android.media.MediaDataSource
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class HttpMediaDataSource(private val url: String) : MediaDataSource() {
    private var connection: HttpURLConnection? = null
    private var inputStream: InputStream? = null
    private var currentPosition: Long = -1
    private var contentLength: Long = -1

    override fun getSize(): Long {
        if (contentLength == -1L) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            contentLength = conn.contentLengthLong
            conn.disconnect()
        }
        return contentLength
    }

    override fun readAt(position: Long, buffer: ByteArray?, offset: Int, size: Int): Int {
        if (buffer == null || size <= 0) return 0

        // 位置不匹配，重建连接
        if (position != currentPosition || inputStream == null) {
            reconnect(position)
        }

        return try {
            val read = inputStream!!.read(buffer, offset, size)
            if (read > 0) {
                currentPosition += read
            }
            read
        } catch (_: IOException) {
            // 连接断开，重试一次
            reconnect(position)
            inputStream!!.read(buffer, offset, size).also {
                if (it > 0) currentPosition += it
            }
        }
    }

    private fun reconnect(position: Long) {
        // 关闭旧连接
        inputStream?.close()
        connection?.disconnect()

        // 建立新连接，Range 定位到指定位置
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Range", "bytes=$position-")
        conn.setRequestProperty("Connection", "keep-alive")

        connection = conn
        inputStream = conn.inputStream
        currentPosition = position
    }

    override fun close() {
        inputStream?.close()
        connection?.disconnect()
    }
}