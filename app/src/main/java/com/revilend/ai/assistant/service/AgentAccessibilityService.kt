package com.revilend.ai.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PathMeasure
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.revilend.ai.assistant.control.DeviceController
import com.revilend.ai.assistant.util.PreferencesManager
import com.revilend.ai.assistant.util.SpeechManager
import com.revilend.ai.assistant.util.TtsState
import java.util.UUID

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AgentAccessibilityService"
        private const val DEFAULT_LANGUAGE = "uz-UZ"
    }

    private lateinit var speechManager: SpeechManager
    private lateinit var deviceController: DeviceController
    private lateinit var preferencesManager: PreferencesManager

    private var tts: TextToSpeech? = null
    private val handler = Handler(Looper.getMainLooper())

    private var isListening = false
    private var currentAction: String = ""
    private var actionStartTime: Long = 0
    private var lastFocusedNode: AccessibilityNodeInfo? = null
    private var windowNodes: MutableList<AccessibilityNodeInfo> = mutableListOf()

    // Window and node information
    private var currentPackageName: String = ""
    private var currentClassName: String = ""
    private var rootNode: AccessibilityNodeInfo? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Accessibility Service created")

        preferencesManager = PreferencesManager(this)
        deviceController = DeviceController(this)
        speechManager = SpeechManager(this)
        speechManager.voiceLanguage = preferencesManager.voiceLanguage

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = java.util.Locale.US
                Log.d(TAG, "TTS initialized")
            } else {
                Log.e(TAG, "TTS init failed")
            }
        }

        // Set up service info
        val serviceInfo = android.accessibilityservice.AccessibilityServiceInfo().apply {
            flags = android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED
            notificationTimeout = 100
        }
        setServiceInfo(serviceInfo)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service connected")
        speak("Revilend AI tayyor, sizni tinglamoqdaman.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        Log.d(TAG, "Event: ${event.eventType}")

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                currentPackageName = event.packageName ?: ""
                currentClassName = event.className ?: ""
                Log.d(TAG, "Window changed: $currentPackageName / $currentClassName")
                updateRootNode()
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                lastFocusedNode = event.source?.copy() ?: return
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
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted")
        cleanup()
    }

    private fun updateRootNode() {
        rootNode = rootInActiveWindow
        if (rootNode != null) {
            collectAllNodes(rootNode!!)
        }
    }

    private fun collectAllNodes(node: AccessibilityNodeInfo) {
        if (node.childCount > 0) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    windowNodes.add(child)
                    collectAllNodes(child)
                    child.recycle()
                }
            }
        }
        windowNodes.add(node)
    }

    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        rootNode?.let { root ->
            return findNodeRecursive(root, text)
        }
        return null
    }

    private fun findNodeRecursive(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        node.contentDescription?.let { desc ->
            if (desc.contains(text, ignoreCase = true)) {
                return@findNodeRecursive node.copy()
            }
        }
        node.text?.let { nodeText ->
            if (nodeText.contains(text, ignoreCase = true)) {
                return@findNodeRecursive node.copy()
            }
        }
        if (node.childCount > 0) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    val found = findNodeRecursive(child, text)
                    if (found != null) {
                        child.recycle()
                        return found
                    }
                    child.recycle()
                }
            }
        }
        return null
    }

    fun findNodeById(id: String): AccessibilityNodeInfo? {
        rootNode?.let { root ->
            return findNodeByIdRecursive(root, id)
        }
        return null
    }

    private fun findNodeByIdRecursive(node: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        node.viewIdResourceName?.let { viewId ->
            if (viewId.contains(id, ignoreCase = true)) {
                return@findNodeByIdRecursive node.copy()
            }
        }
        if (node.childCount > 0) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    val found = findNodeByIdRecursive(child, id)
                    if (found != null) {
                        child.recycle()
                        return found
                    }
                    child.recycle()
                }
            }
        }
        return null
    }

    fun clickNode(node: AccessibilityNodeInfo?) {
        if (node == null) {
            Log.w(TAG, "Cannot click null node")
            return
        }
        try {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Clicked node: ${node.text}")
                reportActionStatus("click", "Clicked: ${node.text}")
            } else {
                // Try to find a parent that is clickable
                findClickableParent(node)?.let { parent ->
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Clicked parent: ${parent.text}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Click error: ${e.message}")
        }
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node.copy()
        if (node.parent != null) {
            return findClickableParent(node.parent)
        }
        return null
    }

    fun performGlobalAction(action: Int) {
        when (action) {
            GLOBAL_ACTION_HOME -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                Log.d(TAG, "Global HOME")
                speak("Bosh sahifaga qaytildi")
            }
            GLOBAL_ACTION_BACK -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                Log.d(TAG, "Global BACK")
                speak("Orqaga qaytildi")
            }
            GLOBAL_ACTION_RECENTS -> {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                Log.d(TAG, "Global RECENTS")
                speak("Joriy vazifalar")
            }
            GLOBAL_ACTION_NOTIFICATIONS -> {
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                Log.d(TAG, "Global NOTIFICATIONS")
                speak("Xabarlar ochildi")
            }
            GLOBAL_ACTION_QUICK_SETTINGS -> {
                performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                Log.d(TAG, "Global QUICK_SETTINGS")
                speak("Tezkor sozlamalar")
            }
            GLOBAL_ACTION_LOCK_SCREEN -> {
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                Log.d(TAG, "Global LOCK_SCREEN")
                speak("Ekranda qoflangan")
            }
            else -> {
                Log.w(TAG, "Unknown global action: $action")
            }
        }
    }

    fun tapAtCoordinates(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val path = Path().apply {
                    moveTo(x.toFloat(), y.toFloat())
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0))
                    .build()
                dispatchGesture(gesture, null, null)
                Log.d(TAG, "Tapped at: $x, $y")
                reportActionStatus("tap", "Tapped at ($x, $y)")
            } catch (e: Exception) {
                Log.e(TAG, "Tap gesture error: ${e.message}")
            }
        } else {
            Log.w(TAG, "Tap gesture not supported on API < 26")
        }
    }

    fun longPressAtCoordinates(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val path = Path().apply {
                    moveTo(x.toFloat(), y.toFloat())
                }
                val duration = 500L
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, duration))
                    .build()
                dispatchGesture(gesture, null, null)
                Log.d(TAG, "Long pressed at: $x, $y")
                reportActionStatus("longpress", "Long pressed at ($x, $y)")
            } catch (e: Exception) {
                Log.e(TAG, "Long press gesture error: ${e.message}")
            }
        }
    }

    fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, duration: Long = 300) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val path = Path().apply {
                    moveTo(fromX.toFloat(), fromY.toFloat())
                    lineTo(toX.toFloat(), toY.toFloat())
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, duration))
                    .build()
                dispatchGesture(gesture, null, null)
                Log.d(TAG, "Swiped from ($fromX,$fromY) to ($toX,$toY)")
                reportActionStatus("swipe", "Swiped from ($fromX,$fromY) to ($toX,$toY)")
            } catch (e: Exception) {
                Log.e(TAG, "Swipe gesture error: ${e.message}")
            }
        }
    }

    fun scroll(direction: String) {
        when (direction.lowercase()) {
            "up" -> {
                swipe(540, 1920, 540, 1000)
                reportActionStatus("scroll", "Scrolled up")
            }
            "down" -> {
                swipe(540, 1000, 540, 1920)
                reportActionStatus("scroll", "Scrolled down")
            }
            "left" -> {
                swipe(540, 1000, 100, 1000)
                reportActionStatus("scroll", "Scrolled left")
            }
            "right" -> {
                swipe(100, 1000, 540, 1000)
                reportActionStatus("scroll", "Scrolled right")
            }
            else -> {
                Log.w(TAG, "Unknown scroll direction: $direction")
            }
        }
    }

    fun setText(text: String) {
        if (lastFocusedNode == null) {
            Log.w(TAG, "No focused node for text input")
            return
        }
        try {
            lastFocusedNode?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, createBundleForSetText(text))
            Log.d(TAG, "Text set: $text")
            reportActionStatus("type_text", "Typed: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Set text error: ${e.message}")
        }
    }

    fun typeAndSubmit(text: String) {
        setText(text)
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_BACK)
        }, 200)
    }

    fun openApp(packageName: String) {
        Log.d(TAG, "Opening app: $packageName")
        deviceController.launchApp(packageName)
        reportActionStatus("open_app", "Opening: $packageName")
    }

    fun openAppByCommonName(name: String) {
        val packageMap = mapOf(
            "telegram" to "org.telegram.messenger",
            "instagram" to "com.instagram.android",
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "tiktok" to "com.zhiliaoapp.musically",
            "mobile legends" to "com.mobile.legends",
            "pubg" to "com.tencent.ig",
            "chrome" to "com.android.chrome",
            "camera" to "com.sec.android.app.camera",
            "settings" to "com.android.settings"
        )
        val packageName = packageMap[name.lowercase()] ?: name
        openApp(packageName)
    }

    fun executeDeviceCommand(command: String) {
        val result = deviceController.executeCommand(command)
        Log.d(TAG, "Device command: $command -> ${result.message}")
        speak(result.message)
        reportActionStatus("device", result.message)
    }

    fun getDeviceInfo(infoType: String): String? {
        return when (infoType.lowercase()) {
            "battery" -> {
                val battery = deviceController.getBatteryInfo()
                "Battery: ${battery.percentage}%, ${if (battery.isCharging) "charging" else "not charging"}"
            }
            "memory" -> {
                val memory = deviceController.getMemoryInfo()
                val availableMB = memory.availableMemory / (1024 * 1024)
                val totalMB = memory.totalMemory / (1024 * 1024)
                "RAM: ${availableMB}MB free / ${totalMB}MB total"
            }
            "wifi" -> {
                val wifi = deviceController.getWifiInfo()
                val ssid = wifi?.ssid ?: "Not connected"
                "WiFi: $ssid"
            }
            "volume" -> {
                "Media: ${deviceController.getMediaVolume()}/${deviceController.getMaxMediaVolume()}"
            }
            else -> null
        }
    }

    fun speak(message: String) {
        speechManager.speak(message)
    }

    fun speakIntro() {
        speechManager.speakIntro()
    }

    // Agent action execution
    fun executeAction(jsonAction: String) {
        try {
            val action = com.google.gson.Gson().fromJson(jsonAction, AgentAction::class.java)
            executeActionObject(action)
        } catch (e: Exception) {
            Log.e(TAG, "Action parse error: ${e.message}")
        }
    }

    private fun executeActionObject(action: AgentAction) {
        currentAction = action.action
        actionStartTime = System.currentTimeMillis()

        when (action.action.lowercase()) {
            "click" -> {
                val target = action.target ?: ""
                if (target.isNotEmpty()) {
                    val node = findNodeByText(target)
                    clickNode(node)
                } else {
                    Log.w(TAG, "No target for click")
                }
            }
            "tap_coords" -> {
                val x = action.data?.get("x") as? Int ?: 540
                val y = action.data?.get("y") as? Int ?: 1200
                tapAtCoordinates(x, y)
            }
            "type_text" -> {
                val text = action.data?.get("text") as? String ?: action.target ?: ""
                setText(text)
            }
            "scroll" -> {
                val direction = action.target ?: "down"
                scroll(direction)
            }
            "open_app" -> {
                val package = action.target ?: action.data?.get("package") as? String ?: ""
                openApp(package)
            }
            "global" -> {
                when (action.target?.uppercase()) {
                    "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
                    "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
                    "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                    "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                    "LOCK_SCREEN" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                    else -> Log.w(TAG, "Unknown global: ${action.target}")
                }
            }
            "device" -> {
                val cmd = action.target ?: action.data?.get("cmd") as? String ?: ""
                executeDeviceCommand(cmd)
            }
            "create_web" -> {
                val html = action.data?.get("html") as? String ?: action.target ?: ""
                deviceController.openWebContent(html)
            }
            "talk" -> {
                val message = action.data?.get("message") as? String ?: action.target ?: ""
                speak(message)
            }
            "done" -> {
                val message = action.data?.get("message") as? String ?: action.target ?: "Vazifa bajarildi"
                speak(message)
                currentAction = "none"
            }
            else -> {
                Log.w(TAG, "Unknown action: ${action.action}")
                speak("Noma'lum buyruq")
            }
        }
    }

    private fun reportActionStatus(action: String, message: String) {
        preferencesManager.saveActionStatus(action, message)
    }

    private fun cleanup() {
        windowNodes.forEach { it.recycle() }
        windowNodes.clear()
        rootNode?.recycle()
        rootNode = null
        lastFocusedNode?.recycle()
        lastFocusedNode = null
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
        speechManager.shutdown()
        tts?.shutdown()
    }

    inner class AgentAction(
        val action: String,
        val target: String? = null,
        val data: java.util.Map<String, Any>? = null
    )

    private fun createBundleForSetText(text: String): android.os.Bundle {
        return android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_KEY, text)
        }
    }
}
