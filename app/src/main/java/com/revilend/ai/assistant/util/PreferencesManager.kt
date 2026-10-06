package com.revilend.ai.assistant.util

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("revilend_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_GROQ_API_KEY = "groq_api_key"
        private const val KEY_VOICE_LANG = "voice_language"
        private const val KEY_TTS_LANG = "tts_language"
        private const val KEY_IS_FIRST_LAUNCH = "is_first_launch"
        private const val KEY_GLOW_EFFECT = "glow_effect"
        private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
        private const val KEY_AUTO_LISTEN = "auto_listen"
        private const val KEY_FAB_POSITION_X = "fab_position_x"
        private const val KEY_FAB_POSITION_Y = "fab_position_y"
        private const val KEY_LAST_USED_LANG = "last_used_lang"
        private const val KEY_ACTION_STATUS = "action_status"
        private const val KEY_IS_HUD_VISIBLE = "is_hud_visible"
        private const val KEY_HUD_POSITION_X = "hud_position_x"
        private const val KEY_HUD_POSITION_Y = "hud_position_y"
        private const val KEY_HUD_DOCKED = "hud_docked"
        private const val KEY_VOICE_FEEDBACK = "voice_feedback"
    }

    var groqApiKey: String
        get() = prefs.getString(KEY_GROQ_API_KEY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_GROQ_API_KEY, value).apply()
        }

    var voiceLanguage: String
        get() = prefs.getString(KEY_VOICE_LANG, "uz-UZ") ?: "uz-UZ"
        set(value) {
            prefs.edit().putString(KEY_VOICE_LANG, value).apply()
        }

    var ttsLanguage: String
        get() = prefs.getString(KEY_TTS_LANG, "uz") ?: "uz"
        set(value) {
            prefs.edit().putString(KEY_TTS_LANG, value).apply()
        }

    var isFirstLaunch: Boolean
        get() = prefs.getBoolean(KEY_IS_FIRST_LAUNCH, true)
        set(value) {
            prefs.edit().putBoolean(KEY_IS_FIRST_LAUNCH, value).apply()
        }

    var isGlowEffectEnabled: Boolean
        get() = prefs.getBoolean(KEY_GLOW_EFFECT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_GLOW_EFFECT, value).apply()
        }

    var isVibrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIBRATION_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_VIBRATION_ENABLED, value).apply()
        }

    var isAutoListenEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_LISTEN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_LISTEN, value).apply()
        }

    var hudPositionX: Float
        get() = prefs.getFloat(KEY_HUD_POSITION_X, 50f)
        set(value) {
            prefs.edit().putFloat(KEY_HUD_POSITION_X, value).apply()
        }

    var hudPositionY: Float
        get() = prefs.getFloat(KEY_HUD_POSITION_Y, 50f)
        set(value) {
            prefs.edit().putFloat(KEY_HUD_POSITION_Y, value).apply()
        }

    var isHudDocked: Boolean
        get() = prefs.getBoolean(KEY_HUD_DOCKED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HUD_DOCKED, value).apply()
        }

    var isHudVisible: Boolean
        get() = prefs.getBoolean(KEY_IS_HUD_VISIBLE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_IS_HUD_VISIBLE, value).apply()
        }

    var isVoiceFeedbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_FEEDBACK, true)
        set(value) {
            prefs.edit().putBoolean(KEY_VOICE_FEEDBACK, value).apply()
        }

    fun reset() {
        prefs.edit().clear().apply()
    }

    fun saveActionStatus(action: String, status: String) {
        prefs.edit().putString("action_${action.hashCode()}", status).apply()
    }

    fun getActionStatus(action: String): String {
        return prefs.getString("action_${action.hashCode()}", "") ?: ""
    }
}
