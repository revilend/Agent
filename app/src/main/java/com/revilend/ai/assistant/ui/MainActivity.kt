package com.revilend.ai.assistant.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
            openAppSettings()
        }
        binding.btnCamera.setOnClickListener {
            openAppSettings()
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
        val showAccessibility = !isAccessibilityEnabled()

        binding.cardOverlay.visibility = if (showOverlay) View.VISIBLE else View.GONE
        binding.cardAccessibility.visibility = if (showAccessibility) View.VISIBLE else View.GONE

        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        binding.tvOverlayStatus.text = if (canDrawOverlays()) "✅ Grantylangan" else "❌ Ruxsatsiz"
        binding.tvAccessibilityStatus.text = if (isAccessibilityEnabled()) "✅ Joylashtirilgan" else "❌ Yoʻq"
    }

    private fun canDrawOverlays(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        return am.isEnabled && am.isTouchExplorationEnabled
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
        checkPermissions()
        updatePermissionStatus()
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
    }
}
