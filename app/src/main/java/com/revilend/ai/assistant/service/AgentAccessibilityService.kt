package com.revilend.ai.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.revilend.ai.assistant.control.DeviceController
import com.revilend.ai.assistant.util.PreferencesManager
import java.util.Locale

/**
 * Device control / UI automation service.
 *
 * IMPORTANT: Android binds accessibility services on the main thread with a strict
 * timeout and marks any service that throws or fails to answer during binding as
 * "malfunctioning" (MIUI shows "Bu xizmat xato ishlayapti"). Because of that the
 * lifecycle callbacks below do the absolute minimum: no SharedPreferences reads,
 * no SpeechRecognizer, no TextToSpeech, no setServiceInfo() and no engine lookups.
 * Everything else is created lazily, on first real use, and guarded.
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AgentAccessibilityService"

        /** Live handle so the AI brain can parse the screen and run UI actions. */
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }

    // Created only when an action actually needs them (never during binding).
    private var deviceControllerRef: DeviceController? = null
    private var preferencesRef: PreferencesManager? = null
    private var ttsRef: TextToSpeech? = null

    private val handler = Handler(Looper.getMainLooper())

    private var lastFocusedNode: AccessibilityNodeInfo? = null

    // Window and node information
    @Volatile
    var currentPackageName: String = ""
        private set

    @Volatile
    var currentClassName: String = ""
        private set

    private var rootNode: AccessibilityNodeInfo? = null

    private fun device(): DeviceController? = try {
        deviceControllerRef ?: DeviceController(applicationContext).also { deviceControllerRef = it }
    } catch (t: Throwable) {
        Log.e(TAG, "DeviceController unavailable: ${t.message}")
        null
    }

    private fun prefs(): PreferencesManager? = try {
        preferencesRef ?: PreferencesManager(applicationContext).also { preferencesRef = it }
    } catch (t: Throwable) {
        Log.e(TAG, "Preferences unavailable: ${t.message}")
        null
    }

    // ------------------------------------------------------------------ Lifecycle

    override fun onCreate() {
        super.onCreate()
        // Intentionally empty: the system is still binding us here.
        Log.d(TAG, "onCreate")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Crash-proof: a failure here must never take down the system UI.
        try {
            instance = this
            Log.d(TAG, "Accessibility Service connected")
        } catch (t: Throwable) {
            Log.e(TAG, "onServiceConnected error: ${t.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Crash-proof: event stream is noisy and comes from other apps.
        try {
            if (event == null) return
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    currentPackageName = event.packageName?.toString() ?: currentPackageName
                    currentClassName = event.className?.toString() ?: currentClassName
                    rootNode = null
                }
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    lastFocusedNode = event.source?.let { AccessibilityNodeInfo.obtain(it) }
                }
                else -> {
                    // Other event types are intentionally ignored: the screen is read
                    // on demand through captureScreenDescription().
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onAccessibilityEvent error: ${t.message}")
        }
    }

    override fun onInterrupt() {
        try {
            Log.d(TAG, "Accessibility Service interrupted")
            cleanup()
        } catch (t: Throwable) {
            Log.e(TAG, "onInterrupt error: ${t.message}")
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        try {
            if (instance === this) instance = null
            handler.removeCallbacksAndMessages(null)
            cleanup()
            Log.d(TAG, "Accessibility Service unbound")
        } catch (t: Throwable) {
            Log.e(TAG, "onUnbind error: ${t.message}")
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        try {
            if (instance === this) instance = null
            handler.removeCallbacksAndMessages(null)
            cleanup()
            ttsRef?.stop()
            ttsRef?.shutdown()
        } catch (t: Throwable) {
            Log.e(TAG, "onDestroy error: ${t.message}")
        }
        ttsRef = null
        super.onDestroy()
    }

    private fun cleanup() {
        rootNode = null
        lastFocusedNode = null
    }

    // ------------------------------------------------- Universal screen parser

    private fun currentRoot(): AccessibilityNodeInfo? = try {
        val root = rootInActiveWindow ?: rootNode
        if (root != null) rootNode = root
        root
    } catch (t: Throwable) {
        Log.e(TAG, "currentRoot error: ${t.message}")
        null
    }

    /**
     * Reads the active window tree and returns a compact, LLM friendly description
     * of every visible text, clickable element, input field and its coordinates.
     */
    fun captureScreenDescription(): String {
        return try {
            val root = currentRoot()
            if (root == null) {
                return "Ekran tarkibi mavjud emas (package=$currentPackageName)"
            }
            val sb = StringBuilder()
            sb.append("Package: ").append(root.packageName ?: currentPackageName)
            sb.append(", Activity: ").append(currentClassName).append('\n')
            sb.append("Interactive elements:\n")
            val seen = HashSet<String>()
            appendVisibleNodes(root, sb, 0, seen)
            if (sb.length > 8000) sb.setLength(8000)
            sb.toString()
        } catch (t: Throwable) {
            Log.e(TAG, "captureScreenDescription error: ${t.message}")
            "Ekran o'qishda xatolik"
        }
    }

    private fun appendVisibleNodes(
        node: AccessibilityNodeInfo?,
        sb: StringBuilder,
        depth: Int,
        seen: MutableSet<String>
    ) {
        if (node == null || depth > 30 || sb.length > 8000) return
        try {
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            val label = when {
                !text.isNullOrEmpty() -> text
                !desc.isNullOrEmpty() -> desc
                else -> null
            }
            val clickable = node.isClickable
            val editable = node.isEditable

            if ((!label.isNullOrEmpty()) || clickable || editable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val key = "$label|${rect.left},${rect.top}"
                if (rect.width() > 0 && rect.height() > 0 && seen.add(key)) {
                    sb.append("- ")
                    if (!label.isNullOrEmpty()) {
                        sb.append('"').append(label.take(80)).append("\" ")
                    }
                    if (editable) sb.append("[input] ")
                    if (clickable) sb.append("[clickable] ")
                    sb.append('(').append(rect.centerX()).append(',').append(rect.centerY()).append(')')
                    val id = node.viewIdResourceName
                    if (!id.isNullOrEmpty()) {
                        sb.append(" id=").append(id.substringAfterLast('/'))
                    }
                    sb.append('\n')
                }
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                appendVisibleNodes(node.getChild(i), sb, depth + 1, seen)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "appendVisibleNodes error: ${t.message}")
        }
    }

    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        return try {
            val root = currentRoot() ?: return null
            findNodeRecursive(root, text)
        } catch (t: Throwable) {
            Log.e(TAG, "findNodeByText error: ${t.message}")
            null
        }
    }

    private fun findNodeRecursive(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        try {
            if (node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true) {
                return AccessibilityNodeInfo.obtain(node)
            }
            if (node.text?.toString()?.contains(text, ignoreCase = true) == true) {
                return AccessibilityNodeInfo.obtain(node)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = findNodeRecursive(child, text)
                if (found != null) return found
            }
        } catch (t: Throwable) {
            Log.e(TAG, "findNodeRecursive error: ${t.message}")
        }
        return null
    }

    fun findNodeById(id: String): AccessibilityNodeInfo? {
        return try {
            val root = currentRoot() ?: return null
            findNodeByIdRecursive(root, id)
        } catch (t: Throwable) {
            null
        }
    }

    private fun findNodeByIdRecursive(node: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        try {
            if (node.viewIdResourceName?.contains(id, ignoreCase = true) == true) {
                return AccessibilityNodeInfo.obtain(node)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = findNodeByIdRecursive(child, id)
                if (found != null) return found
            }
        } catch (t: Throwable) {
            Log.e(TAG, "findNodeByIdRecursive error: ${t.message}")
        }
        return null
    }

    /** Finds the first editable field (input/search box) on screen. */
    fun findEditableNode(): AccessibilityNodeInfo? {
        return try {
            val root = currentRoot() ?: return null
            findEditableRecursive(root)
        } catch (t: Throwable) {
            null
        }
    }

    private fun findEditableRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try {
            if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = findEditableRecursive(child)
                if (found != null) return found
            }
        } catch (t: Throwable) {
            Log.e(TAG, "findEditableRecursive error: ${t.message}")
        }
        return null
    }

    // --------------------------------------------------------------- Actions

    fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return try {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                reportActionStatus("click", "Clicked: ${node.text}")
                true
            } else {
                val parent = findClickableParent(node)
                if (parent != null && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    reportActionStatus("click", "Clicked: ${parent.text}")
                    true
                } else {
                    // Last resort: tap the centre of the node's bounds.
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (rect.width() > 0 && rect.height() > 0) {
                        tapAtCoordinates(rect.centerX(), rect.centerY())
                        true
                    } else false
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Click error: ${t.message}")
            false
        }
    }

    /** Clicks the first element whose text/description matches [target]. */
    fun clickByText(target: String): Boolean {
        return try {
            val node = findNodeByText(target)
            if (node != null) clickNode(node) else false
        } catch (t: Throwable) {
            false
        }
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try {
            var current: AccessibilityNodeInfo? = node
            var depth = 0
            while (current != null && depth < 8) {
                if (current.isClickable) return AccessibilityNodeInfo.obtain(current)
                current = current.parent
                depth++
            }
        } catch (t: Throwable) {
            Log.e(TAG, "findClickableParent error: ${t.message}")
        }
        return null
    }

    fun performAgentGlobalAction(action: Int) {
        try {
            val performed = performGlobalAction(action)
            Log.d(TAG, "Global action $action -> $performed")
        } catch (t: Throwable) {
            Log.e(TAG, "Global action error: ${t.message}")
        }
    }

    /** Executes a global navigation action by its friendly name. */
    fun performGlobalByName(name: String): Boolean {
        return try {
            when (name.uppercase().trim()) {
                "BACK", "ORQAGA" -> performAgentGlobalAction(GLOBAL_ACTION_BACK)
                "HOME", "BOSH" -> performAgentGlobalAction(GLOBAL_ACTION_HOME)
                "RECENTS", "VAZIFALAR" -> performAgentGlobalAction(GLOBAL_ACTION_RECENTS)
                "NOTIFICATIONS", "HABARLAR" -> performAgentGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                "QUICK_SETTINGS", "TEZKOR" -> performAgentGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                // GLOBAL_ACTION_LOCK_SCREEN only exists from API 28.
                "LOCK_SCREEN", "QULF" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performAgentGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                } else {
                    return false
                }
                else -> return false
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "performGlobalByName error: ${t.message}")
            false
        }
    }

    fun tapAtCoordinates(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 100L))
                .build()
            dispatchGesture(gesture, null, null)
            reportActionStatus("tap", "Tapped at ($x, $y)")
        } catch (t: Throwable) {
            Log.e(TAG, "Tap gesture error: ${t.message}")
        }
    }

    fun longPressAtCoordinates(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 500L))
                .build()
            dispatchGesture(gesture, null, null)
            reportActionStatus("longpress", "Long pressed at ($x, $y)")
        } catch (t: Throwable) {
            Log.e(TAG, "Long press gesture error: ${t.message}")
        }
    }

    fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, duration: Long = 300) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val path = Path().apply {
                moveTo(fromX.toFloat(), fromY.toFloat())
                lineTo(toX.toFloat(), toY.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
                .build()
            dispatchGesture(gesture, null, null)
            reportActionStatus("swipe", "Swiped from ($fromX,$fromY) to ($toX,$toY)")
        } catch (t: Throwable) {
            Log.e(TAG, "Swipe gesture error: ${t.message}")
        }
    }

    /** Screen-size aware scroll in the requested direction. */
    fun scroll(direction: String) {
        try {
            val dm = resources.displayMetrics
            val cx = dm.widthPixels / 2
            val cy = dm.heightPixels / 2
            val top = (dm.heightPixels * 0.28).toInt()
            val bottom = (dm.heightPixels * 0.78).toInt()
            val left = (dm.widthPixels * 0.2).toInt()
            val right = (dm.widthPixels * 0.8).toInt()
            when (direction.lowercase().trim()) {
                "up" -> swipe(cx, top, cx, bottom, 300)
                "down" -> swipe(cx, bottom, cx, top, 300)
                "left" -> swipe(right, cy, left, cy, 300)
                "right" -> swipe(left, cy, right, cy, 300)
                else -> Log.w(TAG, "Unknown scroll direction: $direction")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "scroll error: ${t.message}")
        }
    }

    fun setText(text: String): Boolean {
        return try {
            val node = lastFocusedNode?.takeIf { it.isEditable } ?: findEditableNode()
            if (node == null) {
                Log.w(TAG, "No focused/editable node for text input")
                false
            } else {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, createBundleForSetText(text))
                lastFocusedNode = node
                reportActionStatus("type_text", "Typed: $text")
                true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Set text error: ${t.message}")
            false
        }
    }

    fun typeAndSubmit(text: String) {
        if (!setText(text)) return
        handler.postDelayed({
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    lastFocusedNode?.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                    )
                }
                val submitLabels = listOf("send", "search", "yuborish", "qidirish", "go", "enter")
                for (label in submitLabels) {
                    val node = findNodeByText(label)
                    if (node != null && (node.isClickable || findClickableParent(node) != null)) {
                        clickNode(node)
                        return@postDelayed
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Submit error: ${t.message}")
            }
        }, 350)
    }

    fun openApp(packageName: String) {
        try {
            device()?.launchAppOrSearch(packageName)
            reportActionStatus("open_app", "Opening: $packageName")
        } catch (t: Throwable) {
            Log.e(TAG, "openApp error: ${t.message}")
        }
    }

    /** Universal launcher - launches installed apps/games or falls back to web/Play Store. */
    fun openAppUniversal(name: String): Boolean {
        return try {
            val ok = device()?.launchAppOrSearch(name) ?: false
            reportActionStatus("open_app", "Opening: $name")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "openAppUniversal error: ${t.message}")
            false
        }
    }

    fun executeDeviceCommand(command: String): String {
        return try {
            val result = device()?.executeCommand(command)
            val message = result?.message ?: "Qurilma boshqaruvi mavjud emas"
            speak(message)
            reportActionStatus("device", message)
            message
        } catch (t: Throwable) {
            Log.e(TAG, "executeDeviceCommand error: ${t.message}")
            "Xatolik"
        }
    }

    fun getDeviceInfo(infoType: String): String? {
        return try {
            val controller = device() ?: return null
            when (infoType.lowercase()) {
                "battery" -> {
                    val battery = controller.getBatteryInfo()
                    "Batareya: ${battery.percentage}%, ${if (battery.isCharging) "quvvatlanmoqda" else "quvvatlanmayapti"}"
                }
                "memory" -> {
                    val memory = controller.getMemoryInfo()
                    "RAM: ${memory.availableMemory / (1024 * 1024)}MB bo'sh / ${memory.totalMemory / (1024 * 1024)}MB"
                }
                "wifi" -> "WiFi: ${controller.getWifiInfo()?.ssid ?: "ulanmagan"}"
                "volume" -> "Media: ${controller.getMediaVolume()}/${controller.getMaxMediaVolume()}"
                else -> null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "getDeviceInfo error: ${t.message}")
            null
        }
    }

    /** Short spoken confirmation. TTS is created on demand, never at bind time. */
    fun speak(message: String) {
        if (message.isBlank()) return
        try {
            if (prefs()?.isVoiceFeedbackEnabled == false) return

            val existing = ttsRef
            if (existing != null) {
                existing.speak(message, TextToSpeech.QUEUE_ADD, null, utteranceId())
                return
            }

            val engine = TextToSpeech(applicationContext) { status ->
                try {
                    if (status == TextToSpeech.SUCCESS) {
                        try {
                            ttsRef?.language = Locale("uz", "UZ")
                        } catch (t: Throwable) {
                            Log.e(TAG, "TTS language error: ${t.message}")
                        }
                        ttsRef?.speak(message, TextToSpeech.QUEUE_ADD, null, utteranceId())
                    } else {
                        Log.e(TAG, "TTS init failed: $status")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "TTS callback error: ${t.message}")
                }
            }
            ttsRef = engine
        } catch (t: Throwable) {
            Log.e(TAG, "speak error: ${t.message}")
        }
    }

    fun speakIntro() {
        speak("Revilend AI tayyor, sizni tinglamoqdaman.")
    }

    private fun utteranceId(): String = "revilend-${System.currentTimeMillis()}"

    private fun reportActionStatus(action: String, message: String) {
        try {
            prefs()?.saveActionStatus(action, message)
        } catch (t: Throwable) {
            Log.e(TAG, "reportActionStatus error: ${t.message}")
        }
    }

    private fun createBundleForSetText(text: String): Bundle {
        return Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
    }
}
