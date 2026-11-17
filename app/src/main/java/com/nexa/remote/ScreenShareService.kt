package com.nexa.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.app.KeyguardManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
    private var lastSentLocationKey = ""
    private var locationRelayRunnable: Runnable? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var roomCode: String = ""
    private var activeServerUrl: String = ""
    private var reconnectAttempt = 0
    private var reconnectRunnable: Runnable? = null
    private var stopping = false
    private var lockStateReceiver: BroadcastReceiver? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    @Volatile private var deviceLocked = false
    private val mainHandler = Handler(Looper.getMainLooper())
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
        activeServerUrl = serverUrl
        stopping = false
        reconnectAttempt = 0
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
            synchronized(SESSION_LOCK) {
                sharingActive = true
                activePairCode = roomCode
            }
            // Keep the foreground capture/network worker alive while the display is off.
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            screenWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:NexaScreenShare"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            val display = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)

            captureThread = HandlerThread("NexaScreenFrames").also { it.start() }
            captureHandler = Handler(captureThread!!.looper)
            // Bound capture resolution to avoid expensive full-screen pixel conversion.
            val captureWidth = metrics.widthPixels.coerceAtMost(MAX_FRAME_WIDTH).coerceAtLeast(1)
            val captureHeight = (metrics.heightPixels.toFloat() * captureWidth /
                metrics.widthPixels.coerceAtLeast(1)).toInt().coerceAtLeast(1)
            val reader = ImageReader.newInstance(
                captureWidth,
                captureHeight,
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
                captureWidth,
                captureHeight,
                metrics.densityDpi,
                0,
                reader.surface,
                null,
                captureHandler
            )
            RemoteAccessibilityService.setSessionActive(true)
            registerLockStateReceiver()
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
                if (socket !== webSocket || stopping) return
                reconnectAttempt = 0
                webSocket.send(JSONObject().put("type", "host").put("code", roomCode).toString())
                sendLockState(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    when (JSONObject(text).optString("type")) {
                        "host_ready" -> updateNotification("کد اتصال: $roomCode")
                        "viewer_connected" -> {
                            viewerConnected = true
                            sendLockState(webSocket)
                            lastSentLocationKey = ""
                            relayHostLocation()
                            scheduleLocationRelay()
                            updateNotification("بیننده متصل شد؛ کد: $roomCode")
                        }
                        "viewer_disconnected" -> {
                            viewerConnected = false
                            locationRelayRunnable?.let(mainHandler::removeCallbacks)
                            locationRelayRunnable = null
                            lastSentLocationKey = ""
                            updateNotification("بیننده قطع شد؛ کد: $roomCode")
                        }
                        "reconnect_requested" -> {
                            if (socket === webSocket && !stopping) {
                                updateNotification("درخواست تازه‌سازی دریافت شد؛ اتصال دوباره برقرار می‌شود")
                                webSocket.close(4001, "Viewer requested refresh")
                            }
                        }
                        "swipe" -> {
                            if (viewerConnected && !deviceLocked) {
                                val message = JSONObject(text)
                                RemoteAccessibilityService.performRemoteSwipe(
                                    message.optDouble("x1", -1.0),
                                    message.optDouble("y1", -1.0),
                                    message.optDouble("x2", -1.0),
                                    message.optDouble("y2", -1.0),
                                    message.optLong("duration", 300L)
                                )
                            }
                        }
                        "tap" -> {
                            if (viewerConnected && !deviceLocked) {
                                RemoteAccessibilityService.performRemoteTap(
                                    JSONObject(text).optDouble("x", -1.0),
                                    JSONObject(text).optDouble("y", -1.0)
                                )
                            }
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
                if (socket !== webSocket) return
                socket = null
                viewerConnected = false
                updateNotification("ارتباط ناپایدار است؛ تلاش برای اتصال دوباره…")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket !== webSocket) return
                socket = null
                viewerConnected = false
                if (!stopping && projection != null) scheduleReconnect()
            }
        })
    }

    private fun registerLockStateReceiver() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        deviceLocked = keyguard.isKeyguardLocked
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val lockedNow = when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> true
                    Intent.ACTION_USER_PRESENT -> false
                    Intent.ACTION_SCREEN_ON -> keyguard.isKeyguardLocked
                    else -> keyguard.isKeyguardLocked
                }
                if (lockedNow != deviceLocked) {
                    deviceLocked = lockedNow
                    socket?.let(::sendLockState)
                }
                // A sleeping network stack may have dropped the relay socket.
                // Retry immediately after the owner unlocks the device.
                if (!lockedNow && !stopping && projection != null) reconnectAfterUnlock()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        lockStateReceiver = receiver
    }

    private fun reconnectAfterUnlock() {
        // Recreate the relay socket after unlock: mobile networks can leave a
        // half-open WebSocket that still looks present but no longer carries frames.
        reconnectRunnable?.let(mainHandler::removeCallbacks)
        reconnectRunnable = null
        val oldSocket = socket
        socket = null
        viewerConnected = false
        try { oldSocket?.close(1001, "Device unlocked; refresh relay") } catch (_: Exception) {}
        if (stopping || projection == null || activeServerUrl.isBlank()) return
        val task = Runnable {
            reconnectRunnable = null
            if (!stopping && projection != null && socket == null) {
                connectRelay(activeServerUrl)
            }
        }
        reconnectRunnable = task
        mainHandler.postDelayed(task, 1_500L)
    }

    private fun relayHostLocation() {
        if (!viewerConnected || stopping) return
        val prefs = getSharedPreferences("nexa_remote_location", MODE_PRIVATE)
        val latitude = prefs.getString("latitude", null)?.toDoubleOrNull() ?: return
        val longitude = prefs.getString("longitude", null)?.toDoubleOrNull() ?: return
        val timestamp = prefs.getLong("timestamp", 0L)
        if (timestamp <= 0L || System.currentTimeMillis() - timestamp > 15 * 60 * 1000L) return
        val key = "$latitude,$longitude,$timestamp"
        if (key == lastSentLocationKey) return
        val target = socket ?: return
        if (target.send(JSONObject().put("type", "host_location")
                .put("latitude", latitude).put("longitude", longitude)
                .put("timestamp", timestamp).toString())) {
            lastSentLocationKey = key
        }
    }

    private fun scheduleLocationRelay() {
        locationRelayRunnable?.let(mainHandler::removeCallbacks)
        val task = object : Runnable {
            override fun run() {
                if (!viewerConnected || stopping) return
                relayHostLocation()
                mainHandler.postDelayed(this, 3000L)
            }
        }
        locationRelayRunnable = task
        mainHandler.post(task)
    }

    private fun sendLockState(target: WebSocket) {
        if (target !== socket || stopping) return
        val type = if (deviceLocked) "device_locked" else "device_unlocked"
        target.send(JSONObject().put("type", type).toString())
    }

    private fun scheduleReconnect() {
        if (stopping || projection == null || activeServerUrl.isBlank()) return
        reconnectRunnable?.let(mainHandler::removeCallbacks)
        val delay = (2_000L shl reconnectAttempt.coerceAtMost(4)).coerceAtMost(30_000L)
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(6)
        val task = Runnable {
            if (!stopping && projection != null && socket == null) {
                connectRelay(activeServerUrl)
            }
        }
        reconnectRunnable = task
        mainHandler.postDelayed(task, delay)
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
        stopping = true
        reconnectRunnable?.let(mainHandler::removeCallbacks)
        reconnectRunnable = null
        locationRelayRunnable?.let(mainHandler::removeCallbacks)
        locationRelayRunnable = null
        viewerConnected = false
        RemoteAccessibilityService.setSessionActive(false)
        lockStateReceiver?.let { receiver ->
            try { unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
        }
        lockStateReceiver = null
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
        synchronized(SESSION_LOCK) {
            sharingActive = false
            activePairCode = ""
        }
        try {
            screenWakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: RuntimeException) { }
        screenWakeLock = null
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
        private val SESSION_LOCK = Any()
        @Volatile private var sharingActive = false
        @Volatile private var activePairCode = ""

        fun isSharingActive(): Boolean = synchronized(SESSION_LOCK) { sharingActive }
        fun currentPairCode(): String = synchronized(SESSION_LOCK) {
            if (sharingActive) activePairCode else ""
        }

        const val ACTION_START = "com.nexa.remote.action.START_CAPTURE"
        const val ACTION_STOP = "com.nexa.remote.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_PAIR_CODE = "pair_code"
        private const val CHANNEL_ID = "nexa_screen_share"
        private const val NOTIFICATION_ID = 4101
        private const val FRAME_INTERVAL_MS = 150L
        private const val MAX_FRAME_WIDTH = 640
        private const val JPEG_QUALITY = 42
        private const val MAX_FRAME_BYTES = 650_000
        private const val MAX_QUEUE_BYTES = 256_000L
    }
}
