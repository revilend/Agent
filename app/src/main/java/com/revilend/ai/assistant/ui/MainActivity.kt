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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.revilend.ai.assistant.R
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesManager(this)
        setupUI()
        checkPermissions()
        loadSettings()
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
                "Version: 1.0.0\n\n" +
                "An AI-powered voice assistant for hands-free phone control.\n\n" +
                "Features:\n" +
                "• Floating HUD overlay\n" +
                "• Voice control (Uzbek, Russian, English)\n" +
                "• Accessibility-based automation\n" +
                "• Device controls (torch, volume, battery)\n" +
                "• Groq LLM integration\n\n" +
                "Tap the floating button to speak commands."
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
            data?.let {
                val results = it.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                results?.let { list ->
                    val recognized = list.firstOrNull() ?: ""
                    Log.d(TAG, "Speech recognized: $recognized")
                    // Forward to accessibility service for processing
                    forwardToAccessibility(recognized)
                }
            }
        }
    }

    private fun forwardToAccessibility(command: String) {
        // Broadcast to accessibility service
        val intent = Intent("com.revilend.ai.assistant.ACTION_VOICE_COMMAND").apply {
            putExtra("command", command)
        }
        sendBroadcast(intent)
        Toast.makeText(this, "Processing: $command", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val SPEECH_REQUEST_CODE = 1001
        private const val PERMISSION_REQUEST_CODE = 2001
        private val GREEN = Color.parseColor("#22C55E")
        private val RED = Color.parseColor("#EF4444")
    }
}
