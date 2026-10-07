package com.revilend.ai.assistant.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.revilend.ai.assistant.R
import com.revilend.ai.assistant.agent.AgentBrain
import com.revilend.ai.assistant.databinding.ActivityMainBinding
import com.revilend.ai.assistant.service.AgentAccessibilityService
import com.revilend.ai.assistant.service.FloatingHudService
import com.revilend.ai.assistant.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager
    private var isHudRunning = false
    private var lastPermissionSignature = ""

    /** Shared brain: typed messages and voice commands both go through it. */
    private val agentBrain by lazy { AgentBrain(applicationContext) }
    private var taskRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesManager(this)
        setupUI()
        setupTaskConsole()
        checkPermissions()
        loadSettings()
        handleTaskConsoleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTaskConsoleIntent(intent)
    }

    private fun setupUI() {
        // Master switch for HUD
        binding.switchHud.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startHudService()
            } else {
                stopHudService()
            }
        }

        // Permission shortcut buttons
        binding.btnAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }
        binding.btnOverlay.setOnClickListener {
            openOverlaySettings()
        }
        binding.btnMicsms.setOnClickListener {
            requestMediaPermissions()
        }
        binding.btnCamera.setOnClickListener {
            requestCameraPermission()
        }
        binding.btnNotifications.setOnClickListener {
            openNotificationsSettings()
        }

        // About button
        binding.btnAbout.setOnClickListener {
            showAboutDialog()
        }

        // Voice command button
        binding.btnVoiceCommand.setOnClickListener {
            startSpeechRecognition()
        }
        binding.btnTaskMic.setOnClickListener {
            startSpeechRecognition()
        }
    }

    // ------------------------------------------------------ Task console

    /** The typed "message box": whatever the user writes is executed by the agent. */
    private fun setupTaskConsole() {
        binding.btnTaskRun.setOnClickListener {
            runTask(binding.etTaskInput.text?.toString().orEmpty())
        }
        binding.btnTaskClear.setOnClickListener {
            binding.tvTaskLog.text = ""
        }

        agentBrain.stepListener = { message ->
            binding.tvTaskStatus.text = message
            appendLog("• $message")
        }
    }

    private fun handleTaskConsoleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_TASK_CONSOLE, false) != true) return
        binding.etTaskInput.requestFocus()
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.etTaskInput, InputMethodManager.SHOW_IMPLICIT)
        } catch (e: Exception) {
            Log.e(TAG, "Keyboard error: ${e.message}")
        }
    }

    /** Runs one task end to end and streams every agent step into the console. */
    private fun runTask(task: String) {
        val goal = task.trim()
        if (goal.isEmpty()) {
            Toast.makeText(this, R.string.task_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (taskRunning) {
            Toast.makeText(this, "Avvalgi vazifa bajarilmoqda...", Toast.LENGTH_SHORT).show()
            return
        }

        taskRunning = true
        setTaskControlsEnabled(false)
        binding.tvTaskStatus.text = getString(R.string.task_running)
        appendLog("› $goal")

        lifecycleScope.launch {
            val response = try {
                agentBrain.processCommand(goal)
            } catch (e: Exception) {
                Log.e(TAG, "runTask error: ${e.message}")
                null
            }
            val answer = response?.message?.takeIf { it.isNotBlank() }
                ?: getString(R.string.error_occurred)
            binding.tvTaskStatus.text = if (response?.done == true) "✅ $answer" else "⏹ $answer"
            appendLog(if (response?.done == true) "✅ $answer" else "⏹ $answer")
            taskRunning = false
            setTaskControlsEnabled(true)
        }
    }

    private fun setTaskControlsEnabled(enabled: Boolean) {
        binding.btnTaskRun.isEnabled = enabled
        binding.btnTaskMic.isEnabled = enabled
        binding.btnTaskRun.text = if (enabled) getString(R.string.task_run) else getString(R.string.task_running)
    }

    private fun appendLog(line: String) {
        val current = binding.tvTaskLog.text?.toString().orEmpty()
        val merged = if (current.isEmpty()) line else "$current\n$line"
        // Keep only the last lines the log view can show, so the newest step stays visible.
        val lines = merged.lines()
        binding.tvTaskLog.text = if (lines.size > TASK_LOG_LINES) {
            lines.takeLast(TASK_LOG_LINES).joinToString("\n")
        } else {
            merged
        }
    }

    private fun loadSettings() {
        binding.switchHud.isChecked = prefs.isHudVisible && isHudRunning
        binding.switchVoiceFeedback.isChecked = prefs.isVoiceFeedbackEnabled
        binding.switchGlowEffect.isChecked = prefs.isGlowEffectEnabled
    }

    private fun checkPermissions() {
        val showOverlay = !canDrawOverlays()
        val showAccessibility = !isAccessibilityEnabled() || !isAccessibilityConnected()

        binding.cardOverlay.visibility = if (showOverlay) View.VISIBLE else View.GONE
        binding.cardAccessibility.visibility = if (showAccessibility) View.VISIBLE else View.GONE

        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        val overlayGranted = canDrawOverlays()
        binding.tvOverlayStatus.text = if (overlayGranted) "✅ Grantylangan" else "❌ Ruxsatsiz"
        binding.tvOverlayStatus.setTextColor(if (overlayGranted) GREEN else RED)

        val enabled = isAccessibilityEnabled()
        val connected = isAccessibilityConnected()
        when {
            !enabled -> {
                binding.tvAccessibilityStatus.text = "❌ Yoʻq"
                binding.tvAccessibilityStatus.setTextColor(RED)
            }
            !connected -> {
                // Switch is on in Settings but the system never handed us a live service:
                // the classic "Bu xizmat xato ishlayapti" state (MIUI kills it via battery
                // optimisation / missing autostart).
                binding.tvAccessibilityStatus.text =
                    "⚠️ Yoqilgan, lekin ulanmagan\nMIUI: Avtomatik ishga tushirish ON + Batareya → Cheklovsiz qilib qoʻying"
                binding.tvAccessibilityStatus.setTextColor(RED)
            }
            else -> {
                binding.tvAccessibilityStatus.text = "✅ Faol (ishlayapti)"
                binding.tvAccessibilityStatus.setTextColor(GREEN)
            }
        }

        val mic = hasPermission(Manifest.permission.RECORD_AUDIO)
        val phone = hasPermission(Manifest.permission.CALL_PHONE)
        val sms = hasPermission(Manifest.permission.SEND_SMS)
        binding.tvMicsmsStatus.text =
            "🎤 Mikrofon: ${mark(mic)}   📞 Telefon: ${mark(phone)}   💬 SMS: ${mark(sms)}"
        binding.tvMicsmsStatus.setTextColor(if (mic && phone && sms) GREEN else RED)

        val camera = hasPermission(Manifest.permission.CAMERA)
        binding.tvCameraStatus.text = "📷 Kamera: ${mark(camera)}"
        binding.tvCameraStatus.setTextColor(if (camera) GREEN else RED)
    }

    private fun mark(granted: Boolean): String = if (granted) "✅ Grantylangan" else "❌ Ruxsatsiz"

    /** Snapshot of every permission state, used to detect changes after returning from Settings. */
    private fun permissionSignature(): String = listOf(
        canDrawOverlays(),
        isAccessibilityEnabled(),
        isAccessibilityConnected(),
        hasPermission(Manifest.permission.RECORD_AUDIO),
        hasPermission(Manifest.permission.CALL_PHONE),
        hasPermission(Manifest.permission.SEND_SMS),
        hasPermission(Manifest.permission.CAMERA)
    ).joinToString(",")

    private fun allCriticalGranted(): Boolean =
        canDrawOverlays() && isAccessibilityEnabled() && hasPermission(Manifest.permission.RECORD_AUDIO)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun canDrawOverlays(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    /**
     * True only when the framework has actually bound our service, i.e. the service
     * process is alive. Settings can show the switch as ON while this is false, which
     * is exactly what Android/MIUI reports as "Bu xizmat xato ishlayapti".
     */
    private fun isAccessibilityConnected(): Boolean = AgentAccessibilityService.isConnected()

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        if (!am.isEnabled) return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (info in enabledServices) {
            val serviceInfo = info.resolveInfo?.serviceInfo ?: continue
            if (serviceInfo.packageName == packageName &&
                serviceInfo.name == AgentAccessibilityService::class.java.name
            ) {
                return true
            }
        }
        return false
    }

    private fun requestMediaPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.SEND_SMS
            ),
            PERMISSION_REQUEST_CODE
        )
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            PERMISSION_REQUEST_CODE
        )
    }

    private fun startHudService() {
        if (!canDrawOverlays()) {
            Toast.makeText(this, "Overlay permission required", Toast.LENGTH_SHORT).show()
            binding.switchHud.isChecked = false
            return
        }

        try {
            startService(Intent(this, FloatingHudService::class.java))
            isHudRunning = true
            prefs.isHudVisible = true
            Toast.makeText(this, "Revilend HUD yoqildi", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "HUD start failed: ${e.message}", Toast.LENGTH_SHORT).show()
            binding.switchHud.isChecked = false
        }
    }

    private fun stopHudService() {
        try {
            stopService(Intent(this, FloatingHudService::class.java))
            isHudRunning = false
            prefs.isHudVisible = false
            Toast.makeText(this, "Revilend HUD oʻchirildi", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Stop HUD error: ${e.message}")
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "Settings not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openOverlaySettings() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Overlay settings not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            Toast.makeText(this, "App settings not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openNotificationsSettings() {
        try {
            startActivity(Intent("android.settings.APP_NOTIFICATION_SETTINGS").apply {
                putExtra("app_package", packageName)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Notification settings not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startSpeechRecognition() {
        // Forward to service or show voice input dialog
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "uz-UZ")
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Revilend AI tinglamoqda...")
        }
        try {
            startActivityForResult(intent, SPEECH_REQUEST_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, "Speech not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("Revilend AI")
            .setMessage(
                "Revilend AI: Autonomous Super Assistant\n\n" +
                "Version: 1.3.0\n\n" +
                "An AI-powered voice assistant for hands-free phone control.\n\n" +
                "Features:\n" +
                "• Floating HUD overlay\n" +
                "• Voice control (Uzbek, Russian, English)\n" +
                "• Accessibility-based automation\n" +
                "• Device controls (torch, volume, battery)\n" +
                "• Groq LLM integration\n\n" +
                "Usage:\n" +
                "• Type your task in the message box and tap \"Bajarish\"\n" +
                "• Or tap the floating HUD and speak\n" +
                "• Long-press the HUD to open the message box anywhere"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Re-check every permission dynamically so returning from Android Settings
        // immediately flips the status labels to green without restarting the app.
        val signature = permissionSignature()
        checkPermissions()
        updatePermissionStatus()
        loadSettings()

        if (lastPermissionSignature.isNotEmpty() && signature != lastPermissionSignature) {
            val message = if (allCriticalGranted()) "✅ Ruxsatlar yangilandi" else "Ruxsatlar o'zgardi"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
        lastPermissionSignature = signature
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            updatePermissionStatus()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SPEECH_REQUEST_CODE && resultCode == RESULT_OK) {
            val recognized = data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            Log.d(TAG, "Speech recognized: $recognized")
            if (recognized.isNotBlank()) {
                // Show what was heard inside the message box, then run it, so the
                // voice path and the typed path do exactly the same thing.
                binding.etTaskInput.setText(recognized)
                runTask(recognized)
            }
        } else if (requestCode == SPEECH_REQUEST_CODE) {
            Toast.makeText(this, "Ovoz tanilmadi", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        try {
            agentBrain.stepListener = null
            agentBrain.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Shutdown error: ${e.message}")
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val SPEECH_REQUEST_CODE = 1001
        private const val PERMISSION_REQUEST_CODE = 2001
        private const val TASK_LOG_LINES = 12

        /** Set by the floating HUD / notification to jump straight into the message box. */
        const val EXTRA_OPEN_TASK_CONSOLE = "com.revilend.ai.assistant.EXTRA_OPEN_TASK_CONSOLE"

        private val GREEN = Color.parseColor("#22C55E")
        private val RED = Color.parseColor("#EF4444")
    }
}
