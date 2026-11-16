package com.nexa.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import libXray.LibXray
import libXray.DialerController
import org.json.JSONObject

class NexaVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null

    private val dialerController = object : DialerController {
        override fun protectFd(fd: Int): Boolean = this@NexaVpnService.protect(fd)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVpn()
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG)
                if (config.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, notification())
                Thread { startVpn(config) }.start()
            }
        }
        return START_NOT_STICKY
    }

    private fun startVpn(configText: String) {
        try {
            tun?.close()
            tun = Builder()
                .setSession("NEXA VPN")
                .setMtu(1500)
                .addAddress("10.77.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .establish() ?: throw IllegalStateException("اندروید رابط VPN را ایجاد نکرد.")

            LibXray.registerDialerController(dialerController)
            val config = JSONObject(configText)
            config.getJSONObject("env").put("xray.tun.fd", tun!!.fd.toString())
            val request = JSONObject()
                .put("apiVersion", 3)
                .put("method", "runXray")
                .put("payload", JSONObject().put("xrayJson", config.toString()))
            val response = JSONObject(LibXray.invoke(request.toString()))
            if (!response.optBoolean("success")) {
                throw IllegalStateException(response.optString("error", "Xray اجرا نشد."))
            }
        } catch (e: Exception) {
            stopVpn()
            val prefs = getSharedPreferences("nexa_vpn_state", MODE_PRIVATE)
            prefs.edit().putString("error", e.message ?: "خطای اتصال").apply()
        }
    }

    private fun stopVpn() {
        runCatching {
            val request = JSONObject().put("apiVersion", 3).put("method", "stopXray")
            LibXray.invoke(request.toString())
        }
        runCatching { tun?.close() }
        tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val channelId = "nexa_vpn"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "NEXA VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId)
                      else Notification.Builder(this)
        return builder.setContentTitle("NEXA VPN")
            .setContentText("سرویس VPN فعال است؛ برای قطع اتصال وارد برنامه شوید.")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .build()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        runCatching { tun?.close() }
        tun = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.nexa.remote.VPN_START"
        const val ACTION_STOP = "com.nexa.remote.VPN_STOP"
        const val EXTRA_CONFIG = "xray_config"
        private const val NOTIFICATION_ID = 2407
    }
}
