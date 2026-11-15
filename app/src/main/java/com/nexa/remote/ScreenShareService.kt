package com.nexa.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Screen frames are relayed only after the owner approves Android's system
 * MediaProjection dialog. The service shows a permanent notification and
 * exposes an immediate stop action. The relay uses TLS WebSockets (wss://).
 */
class ScreenShareService : Service() {
    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var socket: WebSocket? = null
    private var viewerConnected = false
    private var lastFrameAt = 0L
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var roomCode: String = ""
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> startCapture(intent)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        if (projection != null) return
        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)?.trim().orEmpty()
        roomCode = intent.getStringExtra(EXTRA_PAIR_CODE)?.trim().orEmpty()
        if (!serverUrl.startsWith("wss://") || !roomCode.matches(Regex("[0-9]{8}"))) {
            stopSelf()
            return
        }

        createNotificationChannel()
        startAsForeground()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = readResultData(intent)
        if (resultCode == 0 || resultData == null) {
            stopSelf()
            return
        }

        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = manager.getMediaProjection(resultCode, resultData)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            val display = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)

            captureThread = HandlerThread("NexaScreenFrames").also { it.start() }
            captureHandler = Handler(captureThread!!.looper)
            val reader = ImageReader.newInstance(
                metrics.widthPixels.coerceAtLeast(1),
                metrics.heightPixels.coerceAtLeast(1),
                PixelFormat.RGBA_8888,
                2
            )
            imageReader = reader
            reader.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    forwardFrame(image)
                } finally {
                    image.close()
                }
            }, captureHandler)

            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    releaseCapture(stopProjection = false)
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))

            virtualDisplay = projection?.createVirtualDisplay(
                "NEXA-Remote-Capture",
                metrics.widthPixels.coerceAtLeast(1),
                metrics.heightPixels.coerceAtLeast(1),
                metrics.densityDpi,
                0,
                reader.surface,
                null,
                captureHandler
            )
            connectRelay(serverUrl)
        } catch (_: SecurityException) {
            stopCapture()
            stopSelf()
        } catch (_: RuntimeException) {
            stopCapture()
            stopSelf()
        }
    }

    private fun connectRelay(serverUrl: String) {
        val request = Request.Builder().url(serverUrl).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "host").put("code", roomCode).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    when (JSONObject(text).optString("type")) {
                        "host_ready" -> updateNotification("کد اتصال: $roomCode")
                        "viewer_connected" -> {
                            viewerConnected = true
                            updateNotification("بیننده متصل شد؛ کد: $roomCode")
                        }
                        "viewer_disconnected" -> {
                            viewerConnected = false
                            updateNotification("بیننده قطع شد؛ کد: $roomCode")
                        }
                        "session_expired", "host_disconnected" -> {
                            stopCapture()
                            stopSelf()
                        }
                        "error" -> {
                            updateNotification("خطای اتصال؛ اشتراک را متوقف کنید")
                        }
                    }
                } catch (_: Exception) {
                    // Ignore malformed relay messages.
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                viewerConnected = false
                updateNotification("ارتباط با سرور قطع شد")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                viewerConnected = false
            }
        })
    }

    private fun forwardFrame(image: Image) {
        if (!viewerConnected || socket == null) return
        val now = System.currentTimeMillis()
        if (now - lastFrameAt < FRAME_INTERVAL_MS) return
        lastFrameAt = now
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val width = image.width
            val height = image.height
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val rowStart = y * rowStride
                for (x in 0 until width) {
                    val offset = rowStart + x * pixelStride
                    val r = buffer.get(offset).toInt() and 0xff
                    val g = buffer.get(offset + 1).toInt() and 0xff
                    val b = buffer.get(offset + 2).toInt() and 0xff
                    val a = buffer.get(offset + 3).toInt() and 0xff
                    pixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            var bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            if (width > MAX_FRAME_WIDTH) {
                val scaledHeight = (height * (MAX_FRAME_WIDTH.toFloat() / width)).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bitmap, MAX_FRAME_WIDTH, scaledHeight, true)
                bitmap.recycle()
                bitmap = scaled
            }
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            bitmap.recycle()
            val bytes = output.toByteArray()
            output.close()
            if (bytes.size <= MAX_FRAME_BYTES && (socket?.queueSize() ?: Long.MAX_VALUE) < MAX_QUEUE_BYTES) {
                socket?.send(ByteString.of(*bytes))
            }
        } catch (_: Exception) {
            // A dropped frame is preferable to interrupting the active session.
        }
    }

    private fun readResultData(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    private fun startAsForeground() {
        val notification = buildNotification("در انتظار اتصال بیننده؛ کد: $roomCode")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(message: String): Notification {
        val stopIntent = Intent(this, ScreenShareService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("NEXA Remote در حال اشتراک صفحه")
                .setContentText(message)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "توقف اشتراک", stopPendingIntent).build())
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("NEXA Remote")
                .setContentText(message)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "اشتراک صفحه", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun stopCapture() = releaseCapture(stopProjection = true)

    private fun releaseCapture(stopProjection: Boolean) {
        viewerConnected = false
        try { socket?.send(JSONObject().put("type", "stop").toString()) } catch (_: Exception) {}
        socket?.close(1000, "Owner stopped sharing")
        socket = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        val activeProjection = projection
        projection = null
        if (stopProjection) activeProjection?.stop()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        stopCapture()
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.nexa.remote.action.START_CAPTURE"
        const val ACTION_STOP = "com.nexa.remote.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_PAIR_CODE = "pair_code"
        private const val CHANNEL_ID = "nexa_screen_share"
        private const val NOTIFICATION_ID = 4101
        private const val FRAME_INTERVAL_MS = 160L
        private const val MAX_FRAME_WIDTH = 960
        private const val JPEG_QUALITY = 55
        private const val MAX_FRAME_BYTES = 1_500_000
        private const val MAX_QUEUE_BYTES = 2_000_000L
    }
}
