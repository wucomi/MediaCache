package com.hik.media

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hik.media.cache.CacheCallback
import com.hik.media.cache.MediaCache
import com.hik.media.databinding.ActivityMainBinding
import com.hik.media.error.ErrorCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), CacheCallback {

    private lateinit var binding: ActivityMainBinding
    private var mediaPlayer: MediaPlayer? = null
    private var surface: Surface? = null
    private var mediaCache: MediaCache? = null

    private var isPlaying = false
    private var isBuffering = false
    private var currentVideoUrl: String? = null
    private var pendingSeekPosition: Int = 0
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            if (isPlaying) {
                progressHandler.postDelayed(this, 500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTextureView()
        setupControls()
    }

    private fun setupTextureView() {
        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surface = Surface(st)
                initMediaPlayer()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                releaseMediaPlayer()
                surface?.release()
                surface = null
                return true
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initMediaPlayer() {
        mediaPlayer = MediaPlayer().apply {
            surface?.let { setSurface(it) }

            setOnPreparedListener {
                binding.progressBar.visibility = View.GONE
                binding.tvStatus.text = "就绪"

                // 设置视频比例
                val videoWidth = it.videoWidth
                val videoHeight = it.videoHeight
                if (videoWidth > 0 && videoHeight > 0) {
                    binding.textureView.setVideoSize(videoWidth, videoHeight)
                }

                start()
                this@MainActivity.isPlaying = true
                binding.btnPause.isEnabled = true
                updatePlayPauseButton()
                startProgressUpdate()
            }

            setOnCompletionListener {
                this@MainActivity.isPlaying = false
                updatePlayPauseButton()
                binding.tvStatus.text = "播放完成"
            }

            setOnErrorListener { _, what, _ ->
                binding.tvStatus.text = "播放错误: $what"
                binding.progressBar.visibility = View.GONE
                true
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    @SuppressLint("SetTextI18n")
    private fun setupControls() {
        binding.btnPlay.setOnClickListener {
            val url = binding.etVideoUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                startPlayback(url)
            }
        }

        binding.btnPause.setOnClickListener {
            if (isPlaying) pausePlayback()
            else resumePlayback()
        }

        binding.btnStop.setOnClickListener {
            stopPlayback()
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {

            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val duration = mediaPlayer?.duration ?: 0
                updateTimeText(progress.toLong(), duration.toLong())

                if (!fromUser) {
                    mediaCache?.seekTo(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                pausePlayback()
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                val position = sb!!.progress
                pendingSeekPosition = position
                // 拖动结束时同步进度并触发缓存检查
                mediaCache?.seekTo(position.toLong())
            }
        })

        binding.etVideoUrl.setText("https://v11-default.365yg.com/ee301c05d81b458e9f43d6dfb6b3758a/69d568b7/video/tos/cn/tos-cn-ve-15/o8IBpLTq0CCgBKGaEeO8DETAISrU8UeLf64A7Q/?a=0&br=2676&bt=2676&btag=80000e00030000&cd=0%7C0%7C0%7C0&ch=0&cquery=106H&cr=0&cs=0&cv=1&dr=0&ds=3&dy_q=1775589461&dy_va_biz_cert=&feature_id=fea919893f650a8c49286568590446ef&ft=k7Fz7VVywIiRZm8Zmo~pK7pswApsm.f_vrKlISd2do0g3cI&l=202604080317411AD9A7908D7EAE049193&lr=unwatermarked&mime_type=video_mp4&net=5&qs=0&rc=OGRpNzU0OjU0NTtoODpmN0BpMzNvbHk5cjR0OTMzNGkzM0A1YS5iYC8tNi8xLjAzMjM0YSNyc2ReMmRjYmhhLS1kLS9zcw%3D%3D")
    }

    private fun startPlayback(url: String) {
        currentVideoUrl = url

        mediaCache?.close()
        val cacheDir = externalCacheDir?.absolutePath ?: cacheDir.absolutePath
        mediaCache = MediaCache(url, cacheDir).apply {
            setCacheCallback(this@MainActivity)
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = "准备中..."

        mediaCache?.start()
    }

    @SuppressLint("SetTextI18n")
    private fun playVideo(path: String) {
        try {
            mediaPlayer?.apply {
                // 确保在调用 reset 前处于可重置状态
                if (isPlaying) {
                    stop()
                }
                reset()
                setDataSource(path)
                prepareAsync()
            }
        } catch (e: Exception) {
            binding.tvStatus.text = "播放失败: ${e.message}"
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun pausePlayback() {
        if (isBuffering) {
            binding.tvStatus.text = "缓冲中..."
            binding.progressBar.visibility = View.VISIBLE
        } else {
            binding.tvStatus.text = "已暂停"
        }
        if (!isPlaying) return
        mediaPlayer?.pause()
        isPlaying = false
        stopProgressUpdate()
        updatePlayPauseButton()
    }

    private fun resumePlayback() {
        if (isBuffering) {
            binding.tvStatus.text = "缓冲中..."
            return
        }
        binding.tvStatus.text = "播放中"
        if (pendingSeekPosition != 0) {
            mediaPlayer?.seekTo(pendingSeekPosition)
            pendingSeekPosition = 0
        }
        if (isPlaying) return
        binding.progressBar.visibility = View.GONE
        mediaPlayer?.start()
        isPlaying = true
        lifecycleScope.launch {
            delay(500)
            startProgressUpdate()
        }
        updatePlayPauseButton()
    }

    @SuppressLint("SetTextI18n")
    private fun stopPlayback() {
        stopProgressUpdate()
        mediaPlayer?.apply {
            if (isPlaying) {
                stop()
            }
        }
        isPlaying = false
        isBuffering = false
        binding.btnPause.isEnabled = false
        updatePlayPauseButton()
        binding.seekBar.progress = 0
        binding.tvProgress.text = "00:00 / 00:00"
        binding.tvStatus.text = "已停止"
    }

    private fun updatePlayPauseButton() {
        binding.btnPause.text = if (isPlaying) "暂停" else "继续"
    }

    @SuppressLint("SetTextI18n")
    private fun updateTimeText(position: Long, duration: Long) {
        binding.tvProgress.text = "${formatTime(position)} / ${formatTime(duration)}"
    }

    private fun updateProgress() {
        val player = mediaPlayer ?: return
        if (!player.isPlaying) return

        val position = player.currentPosition
        val duration = player.duration
        if (duration > 0) {
            binding.seekBar.max = duration
            binding.seekBar.progress = position
        }
    }

    private fun startProgressUpdate() {
        progressHandler.removeCallbacks(progressRunnable)
        progressHandler.post(progressRunnable)
    }

    private fun stopProgressUpdate() {
        progressHandler.removeCallbacks(progressRunnable)
    }

    @SuppressLint("DefaultLocale")
    private fun formatTime(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", minutes, secs)
    }

    // ========== CacheCallback ==========

    override fun onReady(filePath: String) {
        binding.progressBar.visibility = View.GONE
        playVideo(filePath)
    }

    override fun onBufferingLack() {
        isBuffering = true
        pausePlayback()
    }

    override fun onBufferingReady() {
        isBuffering = false
        resumePlayback()
    }

    override fun onComplete(filePath: String) {
        binding.tvStatus.text = "缓存完成"
    }

    @SuppressLint("SetTextI18n")
    override fun onError(errorCode: Int) {
        binding.tvStatus.text = "缓存错误($errorCode): ${ErrorCode.getDescription(errorCode)}"
        binding.progressBar.visibility = View.GONE
    }

    override fun onPause() {
        super.onPause()
        if (isPlaying) pausePlayback()
    }

    override fun onResume() {
        super.onResume()
        resumePlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseMediaPlayer()
        mediaCache?.close()
    }

    private fun releaseMediaPlayer() {
        stopProgressUpdate()
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false
        isBuffering = false
    }
}