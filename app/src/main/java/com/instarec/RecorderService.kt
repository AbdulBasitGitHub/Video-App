package com.instarec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.WindowManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Holds the MediaProjection (approved once) and a VirtualDisplay. Each Instagram
 * session attaches a fresh MediaRecorder surface, then saves the file when it ends.
 */
class RecorderService : Service() {

    companion object {
        const val ACTION_INIT = "com.instarec.INIT"
        const val ACTION_SHUTDOWN = "com.instarec.SHUTDOWN"
        private const val CHANNEL = "recorder"
        private const val NOTIF_ID = 1

        @Volatile
        var instance: RecorderService? = null
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var currentUri: Uri? = null
    private var width = 1080
    private var height = 1920
    @Volatile private var recording = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHUTDOWN) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_INIT && projection == null) init(intent)
        return START_NOT_STICKY
    }

    private fun init(intent: Intent) {
        // Must be in the foreground BEFORE obtaining the projection (Android 14+).
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent.getIntExtra("code", 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("data", Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra("data")
        if (data == null) { stopSelf(); return }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data)
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { Handler(Looper.getMainLooper()).post { stopSelf() } }
        }, Handler(Looper.getMainLooper()))
        projection = proj

        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        var w = bounds.width()
        var h = bounds.height()
        val scale = minOf(1f, 1080f / minOf(w, h))
        w = ((w * scale).toInt() / 2) * 2
        h = ((h * scale).toInt() / 2) * 2
        width = w; height = h

        display = proj.createVirtualDisplay(
            "InstaRec", width, height, resources.configuration.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, null
        )
        instance = this
    }

    @Synchronized
    fun startRecording() {
        val vd = display ?: return
        if (recording) return

        val name = "IG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/InstaRec")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
        val fd = contentResolver.openFileDescriptor(uri, "w")
        if (fd == null) { contentResolver.delete(uri, null, null); return }

        try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this)
            else @Suppress("DEPRECATION") MediaRecorder()
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(width, height)
            r.setVideoFrameRate(30)
            r.setVideoEncodingBitRate(8_000_000)
            r.setOutputFile(fd.fileDescriptor)
            r.prepare()
            vd.surface = r.surface
            r.start()
            recorder = r; pfd = fd; currentUri = uri; recording = true
        } catch (e: Exception) {
            fd.close()
            contentResolver.delete(uri, null, null)
        }
    }

    @Synchronized
    fun stopRecording() {
        if (!recording) return
        recording = false
        var ok = true
        display?.surface = null
        try { recorder?.stop() } catch (e: RuntimeException) { ok = false } // no frames captured
        recorder?.release(); recorder = null
        pfd?.close(); pfd = null
        val uri = currentUri; currentUri = null
        if (uri != null) {
            if (ok) {
                val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                contentResolver.update(uri, v, null, null)
            } else {
                contentResolver.delete(uri, null, null)
            }
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recorder", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, RecorderService::class.java).setAction(ACTION_SHUTDOWN),
            PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("InstaRec is ready")
            .setContentText("Records automatically while Instagram is open")
            .addAction(Notification.Action.Builder(0, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopRecording()
        display?.release(); display = null
        projection?.stop(); projection = null
        instance = null
        super.onDestroy()
    }
}
