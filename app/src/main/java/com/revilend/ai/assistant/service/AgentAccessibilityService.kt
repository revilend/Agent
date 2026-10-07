package com.revilend.ai.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.revilend.ai.assistant.control.DeviceController
import com.revilend.ai.assistant.util.PreferencesManager
import com.revilend.ai.assistant.util.SpeechManager
import java.util.Locale

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AgentAccessibilityService"

        /** Live handle so the AI brain can parse the screen and run UI actions. */
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }

    private lateinit var speechManager: SpeechManager
    private lateinit var deviceController: DeviceController
    private lateinit var preferencesManager: PreferencesManager

    private var tts: TextToSpeech? = null
    private val handler = Handler(Looper.getMainLooper())

    private var currentAction: String = ""
    private var lastFocusedNode: AccessibilityNodeInfo? = null

    // Window and node information
    @Volatile
    var currentPackageName: String = ""
        private set

    @Volatile
    var currentClassName: String = ""
        private set

    private var rootNode: AccessibilityNodeInfo? = null

    override fun onCreate() {
        super.onCreate()
        try {
            Log.d(TAG, "Accessibility Service created")
            preferencesManager = PreferencesManager(this)
            deviceController = DeviceController(this)
            speechManager = SpeechManager(this)
            speechManager.voiceLanguage = preferencesManager.voiceLanguage

            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    try {
                        tts?.language = Locale.US
                    } catch (e: Exception) {
                        Log.e(TAG, "TTS language error: ${e.message}")
                    }
                    Log.d(TAG, "TTS initialized")
                } else {
                    Log.e(TAG, "TTS init failed")
                }
            }

            val serviceInfo = android.accessibilityservice.AccessibilityServiceInfo().apply {
                flags = android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                        android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_CLICKED or
                        AccessibilityEvent.TYPE_VIEW_FOCUSED or
                        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                        AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                notificationTimeout = 100
            }
            setServiceInfo(serviceInfo)
        } catch (e: Exception) {
            Log.e(TAG, "onCreate error: ${e.message}")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Crash-proof: a failure here must never take down the system UI.
        try {
            instance = this
            Log.d(TAG, "Accessibility Service connected")
            speak("Revilend AI tayyor, sizni tinglamoqdaman.")
        } catch (e: Exception) {
            Log.e(TAG, "onServiceConnected error: ${e.message}")
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
                    Log.d(TAG, "Window changed: $currentPackageName / $currentClassName")
                    updateRootNode()
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    if (rootNode == null) updateRootNode()
                }
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    lastFocusedNode = event.source?.let { AccessibilityNodeInfo.obtain(it) }
                    Log.d(TAG, "View focused: ${lastFocusedNode?.text}")
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    Log.d(TAG, "View clicked: ${event.source?.text}")
                    reportActionStatus("click", "Clicked: ${event.source?.text}")
                }
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    Log.d(TAG, "Text changed: ${event.source?.text}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onAccessibilityEvent error: ${e.message}")
        }
    }

    override fun onInterrupt() {
        try {
            Log.d(TAG, "Accessibility Service interrupted")
            cleanup()
        } catch (e: Exception) {
            Log.e(TAG, "onInterrupt error: ${e.message}")
        }
    }

    // ------------------------------------------------- Universal screen parser

    private fun updateRootNode() {
        try {
            rootNode = rootInActiveWindow
        } catch (e: Exception) {
            Log.e(TAG, "updateRootNode error: ${e.message}")
        }
    }

    /**
     * Reads the full active window tree and returns a compact, LLM friendly
     * description of every visible text, clickable element, input field and its
     * on-screen coordinates.
     */
    fun captureScreenDescription(): String {
        return try {
            val root = rootInActiveWindow ?: rootNode
            if (root == null) {
                return "Ekran tarkibi mavjud emas (package=$currentPackageName)"
            }
            rootNode = root
            val sb = StringBuilder()
            sb.append("Package: ").append(root.packageName ?: currentPackageName)
            sb.append(", Activity: ").append(currentClassName).append('\n')
            sb.append("Interactive elements:\n")
            val seen = HashSet<String>()
            appendVisibleNodes(root, sb, 0, seen)
            if (sb.length > 8000) sb.setLength(8000)
            sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "captureScreenDescription error: ${e.message}")
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

            if ((label != null && label.isNotEmpty()) || clickable || editable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val key = "$label|${rect.left},${rect.top}"
                if (rect.width() > 0 && rect.height() > 0 && seen.add(key)) {
                    sb.append("- ")
                    if (label != null && label.isNotEmpty()) {
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
        } catch (e: Exception) {
            Log.e(TAG, "appendVisibleNodes error: ${e.message}")
        }
    }

    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        return try {
            val root = rootInActiveWindow ?: rootNode ?: return null
            rootNode = root
            findNodeRecursive(root, text)
        } catch (e: Exception) {
            Log.e(TAG, "findNodeByText error: ${e.message}")
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
        } catch (e: Exception) {
            Log.e(TAG, "findNodeRecursive error: ${e.message}")
        }
        return null
    }

    fun findNodeById(id: String): AccessibilityNodeInfo? {
        return try {
            val root = rootInActiveWindow ?: rootNode ?: return null
            findNodeByIdRecursive(root, id)
        } catch (e: Exception) {
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
        } catch (e: Exception) {
            Log.e(TAG, "findNodeByIdRecursive error: ${e.message}")
        }
        return null
    }

    /** Finds the first editable field (input/search box) on screen. */
    fun findEditableNode(): AccessibilityNodeInfo? {
        return try {
            val root = rootInActiveWindow ?: rootNode ?: return null
            findEditableRecursive(root)
        } catch (e: Exception) {
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
        } catch (e: Exception) {
            Log.e(TAG, "findEditableRecursive error: ${e.message}")
        }
        return null
    }

    // --------------------------------------------------------------- Actions

    fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) {
            Log.w(TAG, "Cannot click null node")
            return false
        }
        return try {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d(TAG, "Clicked node: ${node.text}")
                reportActionStatus("click", "Clicked: ${node.text}")
                true
            } else {
                val parent = findClickableParent(node)
                if (parent != null && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.d(TAG, "Clicked parent: ${parent.text}")
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
        } catch (e: Exception) {
            Log.e(TAG, "Click error: ${e.message}")
            false
        }
    }

    /** Clicks the first element whose text/description matches [target]. */
    fun clickByText(target: String): Boolean {
        return try {
            val node = findNodeByText(target)
            if (node != null) {
                clickNode(node)
            } else {
                Log.w(TAG, "Element not found: $target")
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 8) {
            if (current.isClickable) return AccessibilityNodeInfo.obtain(current)
            current = current.parent
            depth++
        }
        return null
    }

    fun performAgentGlobalAction(action: Int) {
        val spoken = when (action) {
            GLOBAL_ACTION_HOME -> "Bosh sahifaga qaytildi"
            GLOBAL_ACTION_BACK -> "Orqaga qaytildi"
            GLOBAL_ACTION_RECENTS -> "Joriy vazifalar"
            GLOBAL_ACTION_NOTIFICATIONS -> "Bildirishnomalar ochildi"
            GLOBAL_ACTION_QUICK_SETTINGS -> "Tezkor sozlamalar"
            GLOBAL_ACTION_LOCK_SCREEN -> "Ekran qulflandi"
            else -> null
        }
        try {
            performGlobalAction(action)
            if (spoken != null) {
                Log.d(TAG, "Global action executed: $action")
                speak(spoken)
            } else {
                Log.w(TAG, "Unknown global action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Global action error: ${e.message}")
        }
    }

    /** Executes a global navigation action by its friendly name. */
    fun performGlobalByName(name: String): Boolean {
        val action = when (name.uppercase().trim()) {
            "BACK", "ORQAGA" -> GLOBAL_ACTION_BACK
            "HOME", "BOSH" -> GLOBAL_ACTION_HOME
            "RECENTS", "VAZIFALAR" -> GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS", "HABARLAR" -> GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS", "TEZKOR" -> GLOBAL_ACTION_QUICK_SETTINGS
            "LOCK_SCREEN", "QULF" -> GLOBAL_ACTION_LOCK_SCREEN
            else -> return false
        }
        performAgentGlobalAction(action)
        return true
    }

    fun tapAtCoordinates(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.w(TAG, "Tap gesture not supported on API < 26")
            return
        }
        try {
            val path = android.graphics.Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 100L))
                .build()
            dispatchGesture(gesture, null, null)
            Log.d(TAG, "Tapped at: $x, $y")
            reportActionStatus("tap", "Tapped at ($x, $y)")
        } catch (e: Exception) {
            Log.e(TAG, "Tap gesture error: ${e.message}")
        }
    }

    fun longPressAtCoordinates(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val path = android.graphics.Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 500L))
                .build()
            dispatchGesture(gesture, null, null)
            Log.d(TAG, "Long pressed at: $x, $y")
            reportActionStatus("longpress", "Long pressed at ($x, $y)")
        } catch (e: Exception) {
            Log.e(TAG, "Long press gesture error: ${e.message}")
        }
    }

    fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, duration: Long = 300) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val path = android.graphics.Path().apply {
                moveTo(fromX.toFloat(), fromY.toFloat())
                lineTo(toX.toFloat(), toY.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
                .build()
            dispatchGesture(gesture, null, null)
            Log.d(TAG, "Swiped from ($fromX,$fromY) to ($toX,$toY)")
            reportActionStatus("swipe", "Swiped from ($fromX,$fromY) to ($toX,$toY)")
        } catch (e: Exception) {
            Log.e(TAG, "Swipe gesture error: ${e.message}")
        }
    }

    /** Screen-size aware scroll in the requested direction. */
    fun scroll(direction: String) {
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
    }

    fun setText(text: String): Boolean {
        val node = lastFocusedNode?.takeIf { it.isEditable } ?: findEditableNode()
        if (node == null) {
            Log.w(TAG, "No focused/editable node for text input")
            return false
        }
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, createBundleForSetText(text))
            lastFocusedNode = node
            Log.d(TAG, "Text set: $text")
            reportActionStatus("type_text", "Typed: $text")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Set text error: ${e.message}")
            false
        }
    }

    fun typeAndSubmit(text: String) {
        val typed = setText(text)
        if (!typed) return
        handler.postDelayed({
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    lastFocusedNode?.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER)
                }
                val submitLabels = listOf("send", "search", "yuborish", "qidirish", "go", "enter")
                for (label in submitLabels) {
                    val node = findNodeByText(label)
                    if (node != null && (node.isClickable || findClickableParent(node) != null)) {
                        clickNode(node)
                        return@postDelayed
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Submit error: ${e.message}")
            }
        }, 350)
    }

    fun openApp(packageName: String) {
        Log.d(TAG, "Opening app: $packageName")
        deviceController.launchAppOrSearch(packageName)
        reportActionStatus("open_app", "Opening: $packageName")
    }

    /** Universal launcher - launches installed apps/games or falls back to web/Play Store. */
    fun openAppUniversal(name: String): Boolean {
        Log.d(TAG, "Universal open: $name")
        val ok = deviceController.launchAppOrSearch(name)
        reportActionStatus("open_app", "Opening: $name")
        return ok
    }

    fun executeDeviceCommand(command: String): String {
        val result = deviceController.executeCommand(command)
        Log.d(TAG, "Device command: $command -> ${result.message}")
        speak(result.message)
        reportActionStatus("device", result.message)
        return result.message
    }

    fun getDeviceInfo(infoType: String): String? {
        return when (infoType.lowercase()) {
            "battery" -> {
                val battery = deviceController.getBatteryInfo()
                "Batareya: ${battery.percentage}%, ${if (battery.isCharging) "quvvatlanmoqda" else "quvvatlanmayapti"}"
            }
            "memory" -> {
                val memory = deviceController.getMemoryInfo()
                "RAM: ${memory.availableMemory / (1024 * 1024)}MB bo'sh / ${memory.totalMemory / (1024 * 1024)}MB"
            }
            "wifi" -> "WiFi: ${deviceController.getWifiInfo()?.ssid ?: "ulanmagan"}"
            "volume" -> "Media: ${deviceController.getMediaVolume()}/${deviceController.getMaxMediaVolume()}"
            else -> null
        }
    }

    fun speak(message: String) {
        try {
            speechManager.speak(message)
        } catch (e: Exception) {
            Log.e(TAG, "speak error: ${e.message}")
        }
    }

    fun speakIntro() {
        try {
            speechManager.speakIntro()
        } catch (e: Exception) {
            Log.e(TAG, "speakIntro error: ${e.message}")
        }
    }

    private fun reportActionStatus(action: String, message: String) {
        try {
            preferencesManager.saveActionStatus(action, message)
        } catch (e: Exception) {
            Log.e(TAG, "reportActionStatus error: ${e.message}")
        }
    }

    private fun cleanup() {
        rootNode = null
        lastFocusedNode = null
    }

    override fun onDestroy() {
        try {
            if (instance === this) instance = null
            cleanup()
            speechManager.shutdown()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy error: ${e.message}")
        }
        super.onDestroy()
    }

    private fun createBundleForSetText(text: String): android.os.Bundle {
        return android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
    }
}
