package com.personalai.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.os.Process
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * User-started microphone foreground service.
 * It measures signal level only: no audio is stored, transmitted, transcribed,
 * or used to authorize commands.
 */
class RayanVoiceCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.personalai.app.voice.START"
        const val ACTION_STOP = "com.personalai.app.voice.STOP"
        const val ACTION_STATUS = "com.personalai.app.voice.STATUS"
        const val ACTION_LEVEL = "com.personalai.app.voice.LEVEL"
        const val EXTRA_STATUS = "status"
        const val EXTRA_LEVEL_DB = "level_db"
        private const val CHANNEL_ID = "rayan_microphone"
        private const val NOTIFICATION_ID = 7401
        private const val SAMPLE_RATE = 16_000
    }

    @Volatile private var running = false
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var noiseSuppressor: NoiseSuppressor? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            ACTION_START -> startCapture()
            else -> publishStatus("برای شروع، دکمهٔ فعال‌سازی میکروفون را بزنید.")
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        if (running) return
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        running = true
        worker = Thread({ captureLoop() }, "RayanMicCapture").also { it.start() }
        publishStatus("میکروفون فعال است؛ فقط سطح صدا سنجیده می‌شود.")
    }

    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBytes <= 0) {
            publishStatus("ساخت بافر میکروفون ناموفق بود.")
            finishFailedStart()
            return
        }
        val bufferBytes = maxOf(minBytes, SAMPLE_RATE / 5 * 2)
        val audio = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
            )
        } catch (_: Exception) { null }

        if (audio == null || audio.state != AudioRecord.STATE_INITIALIZED) {
            audio?.release()
            publishStatus("راه‌اندازی سخت‌افزار میکروفون ناموفق بود.")
            finishFailedStart()
            return
        }

        recorder = audio
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audio.audioSessionId)?.apply { enabled = true }
            }
            audio.startRecording()
            val samples = ShortArray(bufferBytes / 2)
            publishStatus("دریافت زندهٔ سطح صدا آغاز شد.")
            while (running && !Thread.currentThread().isInterrupted) {
                val count = audio.read(samples, 0, samples.size)
                if (count > 0) {
                    var sum = 0.0
                    for (i in 0 until count) {
                        val value = samples[i].toDouble()
                        sum += value * value
                    }
                    val rms = sqrt(sum / count)
                    val db = if (rms <= 0.0) -90f
                    else (20.0 * log10(rms / 32768.0)).toFloat().coerceIn(-90f, 0f)
                    sendBroadcast(Intent(ACTION_LEVEL).setPackage(packageName).putExtra(EXTRA_LEVEL_DB, db))
                } else if (count < 0) {
                    publishStatus("خطا در دریافت میکروفون؛ کد $count.")
                    break
                }
            }
        } catch (_: SecurityException) {
            publishStatus("مجوز میکروفون لغو شد.")
        } catch (_: Exception) {
            publishStatus("دریافت میکروفون متوقف شد.")
        } finally {
            running = false
            try { audio.stop() } catch (_: Exception) {}
            noiseSuppressor?.release()
            noiseSuppressor = null
            audio.release()
            recorder = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun finishFailedStart() {
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopCapture() {
        running = false
        try { recorder?.stop() } catch (_: Exception) {}
        worker?.interrupt()
        publishStatus("میکروفون خاموش شد.")
        if (worker == null || worker?.isAlive != true) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun publishStatus(value: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, value))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "میکروفون رایان", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, RayanVoiceCaptureService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 7402, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("رایان: میکروفون فعال")
            .setContentText("فقط سنجش سطح صدا؛ بدون ذخیره یا ارسال")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "توقف", stopPending)
            .build()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
