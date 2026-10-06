package com.revilend.ai.assistant.ui

import androidx.lifecycle.ViewModel
import com.revilend.ai.assistant.util.PreferencesManager

class MainActivityViewModel(
    private val prefs: PreferencesManager
) : ViewModel() {

    val isHudVisible: Boolean
        get() = prefs.isHudVisible

    val isGlowEnabled: Boolean
        get() = prefs.isGlowEffectEnabled

    val isVibrationEnabled: Boolean
        get() = prefs.isVibrationEnabled

    fun setHudVisible(visible: Boolean) {
        prefs.isHudVisible = visible
    }

    fun setGlowEnabled(enabled: Boolean) {
        prefs.isGlowEffectEnabled = enabled
    }

    fun setVibrationEnabled(enabled: Boolean) {
        prefs.isVibrationEnabled = enabled
    }
}
