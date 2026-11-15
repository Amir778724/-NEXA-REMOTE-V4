package com.nexa.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
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
            val now = System.currentTimeMillis()
            if (now - lastTapAt < 120L) return
            lastTapAt = now

            val metrics = service.resources.displayMetrics
            val x = (normalizedX * metrics.widthPixels).toFloat().coerceIn(1f, metrics.widthPixels - 1f)
            val y = (normalizedY * metrics.heightPixels).toFloat().coerceIn(1f, metrics.heightPixels - 1f)
            val path = Path().apply {
                moveTo(x, y)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 70))
                .build()
            service.dispatchGesture(gesture, null, null)
        }
    }
}
