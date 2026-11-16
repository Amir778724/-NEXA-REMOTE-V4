package com.nexa.remote

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Optional remote tap input. The device owner must explicitly enable this
 * service in Android Accessibility settings. Taps are accepted only while
 * the owner-approved screen-sharing session is active.
 */
class RemoteAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: RemoteAccessibilityService? = null
        @Volatile private var sessionActive = false
        @Volatile private var lastTapAt = 0L

        fun setSessionActive(active: Boolean) {
            sessionActive = active
            if (!active) lastTapAt = 0L
        }

        fun performRemoteTap(normalizedX: Double, normalizedY: Double) {
            if (!sessionActive || normalizedX !in 0.0..1.0 || normalizedY !in 0.0..1.0) return
            val service = instance ?: return
            // Keep the session connected, but never inject remote gestures over a locked keyguard.
            if (service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
            val now = System.currentTimeMillis()
            if (now - lastTapAt < 120L) return
            lastTapAt = now

            // Use the full physical display bounds, matching MediaProjection's capture space.
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (service.getSystemService(WindowManager::class.java).defaultDisplay).getRealMetrics(metrics)
            val width = metrics.widthPixels.coerceAtLeast(2)
            val height = metrics.heightPixels.coerceAtLeast(2)
            val x = (normalizedX * (width - 1)).toFloat().coerceIn(0f, (width - 1).toFloat())
            val y = (normalizedY * (height - 1)).toFloat().coerceIn(0f, (height - 1).toFloat())
            // A zero-length line makes the intended tap explicit to GestureDescription.
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x, y)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            service.dispatchGesture(gesture, null, null)
        }
    }
}
