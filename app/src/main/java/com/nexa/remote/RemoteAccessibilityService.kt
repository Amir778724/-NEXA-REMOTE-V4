package com.nexa.remote

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
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

        fun performRemoteSwipe(
            normalizedStartX: Double,
            normalizedStartY: Double,
            normalizedEndX: Double,
            normalizedEndY: Double,
            requestedDuration: Long
        ) {
            if (!sessionActive ||
                normalizedStartX !in 0.0..1.0 || normalizedStartY !in 0.0..1.0 ||
                normalizedEndX !in 0.0..1.0 || normalizedEndY !in 0.0..1.0
            ) return
            val service = instance ?: return
            if (service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return

            // A downward swipe beginning at the very top opens the owner's notification shade.
            if (normalizedStartY <= 0.07 && normalizedEndY - normalizedStartY >= 0.12) {
                service.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                return
            }

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (service.getSystemService(WindowManager::class.java).defaultDisplay).getRealMetrics(metrics)
            val width = metrics.widthPixels.coerceAtLeast(2)
            val height = metrics.heightPixels.coerceAtLeast(2)
            val startX = (normalizedStartX * (width - 1)).toFloat()
            val startY = (normalizedStartY * (height - 1)).toFloat()
            val endX = (normalizedEndX * (width - 1)).toFloat()
            val endY = (normalizedEndY * (height - 1)).toFloat()
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val duration = requestedDuration.coerceIn(100L, 800L)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
            service.dispatchGesture(gesture, null, null)
        }

        /**
         * Inserts text only into the currently focused editable field.
         * The text is never read back, stored, or logged.
         */
        fun performRemoteText(text: String): Boolean {
            if (!sessionActive || text.isEmpty() || text.length > 256) return false
            val service = instance ?: return false
            if (service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
            val root = service.rootInActiveWindow ?: return false
            val focused = try {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            } finally {
                root.recycle()
            } ?: return false
            return try {
                if (!focused.isEditable || !focused.isEnabled) return false
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            } finally {
                focused.recycle()
            }
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
