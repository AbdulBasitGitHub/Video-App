package com.instarec

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.os.Handler
import android.os.Looper

/** Only watches which app is in the foreground. It never reads screen content. */
class IgWatcherService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable { RecorderService.instance?.stopRecording() }
    private var ignored = setOf<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        val imes = getSystemService(InputMethodManager::class.java)
            .enabledInputMethodList.map { it.packageName }
        ignored = imes.toSet() + setOf("com.android.systemui", packageName)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        when {
            pkg == "com.instagram.android" -> {
                handler.removeCallbacks(stopRunnable)
                RecorderService.instance?.startRecording()
            }
            pkg in ignored -> return          // keyboard, notification shade, our own dialogs
            else -> {                          // another app: stop after a 2s debounce
                handler.removeCallbacks(stopRunnable)
                handler.postDelayed(stopRunnable, 2000)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(stopRunnable)
        super.onDestroy()
    }
}
