package com.revilend.ai.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.revilend.ai.assistant.ui.MainActivity

class ActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        when (intent.action) {
            "com.revilend.ai.assistant.ACTION_SPEECH" -> {
                // Launch MainActivity for speech
                val activityIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
                context.startActivity(activityIntent)
            }
            "com.revilend.ai.assistant.ACTION_TAP" -> {
                // Forward to accessibility service or start speech
                val speechIntent = Intent("android.speech.action.RECOGNIZE_SPEECH").apply {
                    putExtra("android.speech.extra.LANGUAGE_MODEL", "free_form")
                    putExtra("android.speech.extra.LANGUAGE", "uz-UZ")
                }
                try {
                    context.startActivity(speechIntent)
                } catch (e: Exception) {
                    // Fallback: launch MainActivity
                    val activityIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(activityIntent)
                }
            }
            "com.revilend.ai.assistant.ACTION_LONG_PRESS" -> {
                // Show text input dialog via activity
                val activityIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("ACTION", "TEXT_INPUT")
                }
                context.startActivity(activityIntent)
            }
            "com.revilend.ai.assistant.ACTION_VOICE_COMMAND" -> {
                val command = intent.getStringExtra("command") ?: ""
                // Forward to the accessibility service for processing. The service is bound by
                // the system, so starting it may fail - never let that crash the receiver.
                try {
                    val serviceIntent = Intent(context, AgentAccessibilityService::class.java).apply {
                        putExtra("command", command)
                    }
                    context.startService(serviceIntent)
                } catch (e: Exception) {
                    android.util.Log.e("ActionReceiver", "Forward command failed: ${e.message}")
                }
            }
        }
    }
}
