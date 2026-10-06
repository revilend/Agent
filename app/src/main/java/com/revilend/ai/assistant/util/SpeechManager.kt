package com.revilend.ai.assistant.util

import android.content.Context
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class SpeechManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechManager"
        private const val DEFAULT_LANGUAGE = "uz-UZ"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isListening = false
    private var isInitialized = false

    private val _speechState = MutableStateFlow<SpeechState>(SpeechState.Idle)
    val speechState: StateFlow<SpeechState> = _speechState.asStateFlow()

    private val _speechResult = MutableStateFlow<String?>(null)
    val speechResult: StateFlow<String?> = _speechResult.asStateFlow()

    private val _ttsState = MutableStateFlow<TtsState>(TtsState.Idle)
    val ttsState: StateFlow<TtsState> = _ttsState.asStateFlow()

    var voiceLanguage: String
        get() = PreferencesManager(context).voiceLanguage
        set(value) {
            PreferencesManager(context).voiceLanguage = value
            updateLanguage()
        }

    init {
        initialize()
    }

    private fun initialize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            setupRecognitionListener()
        }
        initializeTts()
        isInitialized = true
    }

    private fun setupRecognitionListener() {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {
                Log.d(TAG, "Ready for speech")
                _speechState.value = SpeechState.Ready
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Beginning of speech")
                _speechState.value = SpeechState.Listening
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Audio visualization callback
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Audio buffer callback
            }

            override fun onEndOfSpeech() {
                Log.d(TAG, "End of speech")
                _speechState.value = SpeechState.Processing
            }

            override fun onError(errorCode: Int) {
                Log.e(TAG, "Speech error: $errorCode")
                isListening = false
                handleSpeechError(errorCode)
            }

            override fun onResults(results: android.os.Bundle?) {
                Log.d(TAG, "Results received")
                processResults(results)
            }

            override fun onPartialResults(partialResults: android.os.Bundle?) {
                processPartialResults(partialResults)
            }

            override fun onEvent(eventType: Int, params: android.os.Bundle?) {
                // Handle events if needed
            }
        })
    }

    private fun initializeTts() {
        try {
            tts = TextToSpeech(context, TextToSpeech.OnInitListener { status ->
                if (status == TextToSpeech.SUCCESS) {
                    setTTsLanguage(Locale.getDefault())
                    Log.d(TAG, "TTS initialized successfully")
                } else {
                    Log.e(TAG, "TTS initialization failed: $status")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "TTS error: ${e.message}")
        }
    }

    private fun updateLanguage() {
        val locale = when (voiceLanguage.lowercase()) {
            "uz-uz" -> Locale("uz", "UZ")
            "ru-ru" -> Locale("ru", "RU")
            "en-us" -> Locale.US
            else -> Locale.getDefault()
        }
        setTTsLanguage(locale)
    }

    private fun setTTsLanguage(locale: Locale): Boolean {
        return try {
            tts?.language = locale
            val result = tts?.setLanguage(locale) ?: TextToSpeech.LANG_MISSING_DATA
            result == TextToSpeech.SUCCESS
        } catch (e: Exception) {
            Log.e(TAG, "Error setting TTS language: ${e.message}")
            false
        }
    }

    fun startListening(): Boolean {
        if (!isInitialized) initialize()
        if (speechRecognizer == null) {
            Log.e(TAG, "SpeechRecognizer not available")
            return false
        }
        if (isListening) stopListening()

        val intent = createSpeechIntent()
        try {
            speechRecognizer?.startListening(intent)
            isListening = true
            _speechState.value = SpeechState.Listening
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition: ${e.message}")
            _speechState.value = SpeechState.Error("Speech recognition not available")
            return false
        }
    }

    fun stopListening() {
        if (speechRecognizer != null && isListening) {
            try {
                speechRecognizer?.stopListening()
                isListening = false
                _speechState.value = SpeechState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping speech: ${e.message}")
            }
        }
    }

    fun cancelListening() {
        speechRecognizer?.cancel()
        isListening = false
        _speechState.value = SpeechState.Idle
    }

    private fun createSpeechIntent(): android.content.Intent {
        return android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceLanguage)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Revilend AI tinglamoqda...")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, true)
        }
    }

    private fun processResults(results: android.os.Bundle?) {
        if (results != null) {
            val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val bestMatch = matches[0]
                _speechResult.value = bestMatch
                _speechState.value = SpeechState.Finished(bestMatch)
                Log.d(TAG, "Speech result: $bestMatch")
            }
        } else {
            _speechState.value = SpeechState.Error("No results")
        }
    }

    private fun processPartialResults(partialResults: android.os.Bundle?) {
        partialResults?.let {
            val partial = it.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!partial.isNullOrEmpty()) {
                _speechResult.value = partial[0]
            }
        }
    }

    private fun handleSpeechError(errorCode: Int) {
        val errorMessage = when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio error"
            SpeechRecognizer.ERROR_CLIENT -> "Client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
            else -> "Unknown error: $errorCode"
        }
        _speechState.value = SpeechState.Error(errorMessage)
        isListening = false
    }

    fun speak(message: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (tts == null || !isInitialized) {
            Log.w(TAG, "TTS not initialized")
            return
        }

        _ttsState.value = TtsState.Speaking
        try {
            tts?.speak(message, queueMode, null, System.currentTimeMillis().toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error speaking: ${e.message}")
            _ttsState.value = TtsState.Error(e.message ?: "TTS error")
        }
    }

    fun speakWithCallback(
        message: String,
        onStart: () -> Unit = {},
        onDone: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                onStart()
            }

            override fun onDone(utteranceId: String?) {
                onDone()
                _ttsState.value = TtsState.Done
            }

            override fun onError(utteranceId: String?) {
                onError("TTS error")
                _ttsState.value = TtsState.Error("TTS error")
            }
        })

        speak(message)
    }

    fun stopSpeaking() {
        tts?.stop()
        _ttsState.value = TtsState.Idle
    }

    fun speakIntro() {
        val lang = voiceLanguage.lowercase()
        val intro = when {
            lang.contains("uz") -> "Revilend AI tayyor, sizni tinglamoqdaman."
            lang.contains("ru") -> "Revilend AI готов, я вас слушаю."
            else -> "Revilend AI ready, I'm listening."
        }
        speak(intro)
    }

    fun shutdown() {
        stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        stopSpeaking()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    fun getSupportedLanguages(): List<LanguageOption> = listOf(
        LanguageOption("uz-UZ", "O'zbek (Uzbek)"),
        LanguageOption("ru-RU", "Rus (Russian)"),
        LanguageOption("en-US", "Ingliz (English)")
    )

    data class LanguageOption(val code: String, val displayName: String)
}

sealed class SpeechState {
    object Idle : SpeechState()
    object Ready : SpeechState()
    object Listening : SpeechState()
    object Processing : SpeechState()
    data class Finished(val result: String) : SpeechState()
    data class Error(val message: String) : SpeechState()
}

sealed class TtsState {
    object Idle : TtsState()
    object Speaking : TtsState()
    object Done : TtsState()
    data class Error(val message: String) : TtsState()
}
