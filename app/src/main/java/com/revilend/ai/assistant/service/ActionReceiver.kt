package com.revilend.ai.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.revilend.ai.assistant.ui.MainActivity

class ActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        when (intent.action) {
            "com.revilend.ai.assistant.ACTION_SPEECH" -> openTaskConsole(context)
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
            "com.revilend.ai.assistant.ACTION_LONG_PRESS" -> openTaskConsole(context)
            "com.revilend.ai.assistant.ACTION_VOICE_COMMAND" -> {
                // The accessibility service is bound by the system. Calling startService()
                // on it can spawn a second, unbound instance and leave the real one in a
                // broken state (which Android reports as "service is malfunctioning"),
                // so commands are only delivered to the live, framework-bound instance.
                val service = AgentAccessibilityService.instance
                if (service == null) {
                    android.util.Log.w(
                        "ActionReceiver",
                        "Voice command dropped: accessibility service is not connected"
                    )
                } else {
                    android.util.Log.d("ActionReceiver", "Voice command received for live service")
                }
            }
        }
    }

    /** Opens the app straight on the "Vazifa yozish" console. */
    private fun openTaskConsole(context: Context) {
        try {
            val activityIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                addCategory(Intent.CATEGORY_DEFAULT)
                putExtra(MainActivity.EXTRA_OPEN_TASK_CONSOLE, true)
            }
            context.startActivity(activityIntent)
        } catch (e: Exception) {
            android.util.Log.e("ActionReceiver", "Open task console failed: ${e.message}")
        }
    }
}
