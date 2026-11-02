
package com.fflivemic.app

import android.app.*
import android.app.Activity
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.app.ActivityManager
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.rtmp.RtmpStream
import kotlin.math.min

/** Adaptive H.264/AAC screen capture and YouTube RTMP pipeline. */
class CaptureService : Service(), ConnectChecker {

    companion object {
        const val ACTION_START = "com.fflivemic.app.START_CAPTURE"
        const val ACTION_STOP = "com.fflivemic.app.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "result_data"
        const val EXTRA_SERVER = "rtmp_server"
        const val EXTRA_KEY = "rtmp_key"

        private const val CHANNEL = "ff_live_stream"
        private const val NOTIFICATION_ID = 304
        private const val TAG = "FFLiveMic"
        private const val AUDIO_RATE = 44100

        // Supported profiles: AUTO, HD, ULTRA
        const val EXTRA_QUALITY = "stream_quality"
        const val QUALITY_AUTO = "AUTO"
        const val QUALITY_HD = "HD"
        const val QUALITY_ULTRA = "ULTRA"
    }

    private data class VideoProfile(
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val label: String
    )

    private var projection: MediaProjection? = null
    private var rtmpStream: RtmpStream? = null
    @Volatile private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopPipeline()
                stopSelf()
            }
            ACTION_START -> {
                if (rtmpStream == null) startPipeline(intent)
            }
        }
        return START_NOT_STICKY
    }

    private fun chooseProfile(requested: String): VideoProfile {
        val metrics = resources.displayMetrics
        val rawW = metrics.widthPixels.coerceAtLeast(2)
        val rawH = metrics.heightPixels.coerceAtLeast(2)
        val portrait = rawH > rawW

        val activityManager =
            getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memoryMb =
            activityManager.memoryClass

        val maxLongSide = when (requested) {
            QUALITY_HD -> 1280
            QUALITY_ULTRA -> 1920
            else -> if (memoryMb >= 6) 1920 else 1280
        }

        val fps = when (requested) {
            QUALITY_HD -> 30
            QUALITY_ULTRA -> 60
            else -> if (memoryMb >= 6) 60 else 30
        }

        val bitrate = when (requested) {
            QUALITY_HD -> 3_500_000
            QUALITY_ULTRA -> 8_000_000
            else -> if (memoryMb >= 6) 5_000_000 else 2_500_000
        }

        val codec = findH264Encoder(
            rawW.coerceAtMost(maxLongSide),
            rawH.coerceAtMost(maxLongSide),
            fps,
            bitrate
        )

        val finalLongSide = min(
            maxLongSide,
            codec?.first ?: 1280
        )

        val scale = min(
            1f,
            finalLongSide.toFloat() / maxOf(rawW, rawH)
        )

        val width = ((rawW * scale).toInt() / 2) * 2
        val height = ((rawH * scale).toInt() / 2) * 2

        val safeFps = if (codec != null) fps else 30
        val safeBitrate = if (codec != null) bitrate else 2_500_000

        return VideoProfile(
            width.coerceAtLeast(2),
            height.coerceAtLeast(2),
            safeFps,
            safeBitrate,
            "${width}x${height} @ ${safeFps} FPS"
        )
    }

    private fun findH264Encoder(
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int
    ): Pair<Int, String>? {
        return try {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            codecs.codecInfos.firstOrNull { info ->
                if (!info.isEncoder) return@firstOrNull false

                val supports = info.supportedTypes.any { type ->
                    if (!type.equals(
                            MediaFormat.MIMETYPE_VIDEO_AVC,
                            ignoreCase = true
                        )
                    ) return@any false

                    try {
                        val caps = info.getCapabilitiesForType(type)
                        val videoCaps = caps.videoCapabilities
                        val bitrateCaps = caps.encoderCapabilities
                        videoCaps.areSizeAndRateSupported(
                            width.coerceAtLeast(2),
                            height.coerceAtLeast(2),
                            fps.toDouble()
                        ) && bitrateCaps.isBitrateModeSupported(
                            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                        )
                    } catch (_: Exception) {
                        false
                    }
                }
                supports
            }?.let { info ->
                val caps = info.getCapabilitiesForType(
                    MediaFormat.MIMETYPE_VIDEO_AVC
                )
                val maxSide = maxOf(
                    caps.videoCapabilities.supportedWidths.upper,
                    caps.videoCapabilities.supportedHeights.upper
                )
                maxSide to info.name
            }
        } catch (e: Exception) {
            Log.w(TAG, "H.264 capability check failed", e)
            null
        }
    }

    private fun startPipeline(intent: Intent) {
        stopped = false
        createChannel()

        val notification =
            buildNotification("YouTube Live तैयार हो रहा है…")

        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            val resultCode = intent.getIntExtra(
                EXTRA_RESULT_CODE,
                Activity.RESULT_CANCELED
            )

            @Suppress("DEPRECATION")
            val resultData =
                intent.getParcelableExtra<Intent>(EXTRA_DATA)
                    ?: error("MediaProjection permission data missing")

            val config = YouTubeRtmpConfig(
                intent.getStringExtra(EXTRA_SERVER).orEmpty(),
                intent.getStringExtra(EXTRA_KEY).orEmpty()
            )
            val publishUrl = config.validatedPublishUrl()

            val manager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            val mp = manager.getMediaProjection(resultCode, resultData)
                ?: error("Android MediaProjection could not be created")

            projection = mp

            mp.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        Handler(Looper.getMainLooper()).post {
                            stopPipeline()
                            stopSelf()
                        }
                    }
                },
                Handler(Looper.getMainLooper())
            )

            val requested = intent.getStringExtra(EXTRA_QUALITY)
                ?: QUALITY_AUTO

            val profile = chooseProfile(requested)

            Log.i(
                TAG,
                "Selected video profile: ${profile.label}, bitrate=${profile.bitrate}"
            )

            val mixedAudio: AudioSource =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MixAudioSource(mp).apply {
                        microphoneVolume = 1.5f
                        internalVolume = 0.85f
                    }
                } else {
                    MicrophoneSource()
                }

            val stream = RtmpStream(
                applicationContext,
                this,
                ScreenSource(applicationContext, mp),
                mixedAudio
            )

            rtmpStream = stream

            check(
                stream.prepareVideo(
                    profile.width,
                    profile.height,
                    profile.bitrate,
                    profile.fps,
                    2,
                    0
                )
            ) {
                "H.264 video encoder preparation failed on this device"
            }

            check(
                stream.prepareAudio(
                    AUDIO_RATE,
                    true,
                    128_000,
                    true,
                    true
                )
            ) {
                "AAC audio encoder preparation failed on this device"
            }

            stream.startStream(publishUrl)
            updateNotification(
                "YouTube Live शुरू • ${profile.label}"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Live pipeline start failed", e)
            updateNotification(
                "स्ट्रीम शुरू नहीं हुई: ${e.message ?: "त्रुटि"}"
            )
            stopPipeline()
            stopSelf()
        }
    }

    private fun updateNotification(message: String) {
        val notification = buildNotification(message.take(120))
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(message: String): Notification {
        val stopIntent =
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP)

        val stopPendingIntent = PendingIntent.getService(
            this,
            305,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL)
            .setContentTitle("FF LIVE MIC")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "STOP LIVE",
                stopPendingIntent
            )
            .build()
    }

    private fun stopPipeline() {
        if (stopped) return
        stopped = true

        try {
            rtmpStream?.stopStream()
        } catch (e: Exception) {
            Log.w(TAG, "RTMP stop", e)
        }

        try {
            rtmpStream?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Encoder release", e)
        }

        rtmpStream = null

        try {
            projection?.stop()
        } catch (_: Exception) {
        }

        projection = null

        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onConnectionStarted(url: String) {
        updateNotification("YouTube RTMP कनेक्शन शुरू…")
    }

    override fun onConnectionSuccess() {
        updateNotification("YouTube RTMP कनेक्टेड")
    }

    override fun onConnectionFailed(reason: String) {
        updateNotification("RTMP कनेक्शन विफल — server URL/network जाँचें")
    }

    override fun onNewBitrate(bitrate: Long) {
        updateNotification("लाइव • ${bitrate / 1000} kbps")
    }

    override fun onDisconnect() {
        updateNotification("YouTube RTMP डिस्कनेक्ट हुआ")
    }

    override fun onAuthError() {
        updateNotification("YouTube ने stream key अस्वीकार की")
    }

    override fun onAuthSuccess() {
        updateNotification("YouTube RTMP प्रमाणीकरण सफल")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        "FF Live Mic streaming",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
        }
    }

    override fun onDestroy() {
        stopPipeline()
        super.onDestroy()
    }
}
