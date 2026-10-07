package com.revilend.ai.assistant.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import com.google.gson.Gson
import com.revilend.ai.assistant.control.DeviceController
import com.revilend.ai.assistant.control.WebAppGenerator
import com.revilend.ai.assistant.service.AgentAccessibilityService
import com.revilend.ai.assistant.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.TimeUnit

class AgentBrain(private val context: Context) {

    companion object {
        private const val TAG = "AgentBrain"
        private const val API_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val MODEL = "openai/gpt-oss-120b"
        private const val API_KEY = "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"
        private const val TIMEOUT_SECONDS = 60L

        /** Actions that finish the goal on their own (no on-screen follow up needed). */
        private val TERMINAL_ACTIONS = setOf("device", "talk", "call", "sms", "alarm", "timer", "done")
    }

    private val deviceController = DeviceController(context)
    private val webAppGenerator = WebAppGenerator(context)
    private val prefs = PreferencesManager(context)
    private val gson = Gson()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    try {
                        tts?.language = Locale("uz", "UZ")
                    } catch (e: Exception) {
                        Log.e(TAG, "TTS language error: ${e.message}")
                    }
                    Log.d(TAG, "Agent TTS initialized")
                } else {
                    Log.e(TAG, "Agent TTS init failed: $status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Agent TTS error: ${e.message}")
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private var currentGoal: String = ""
    private val actionHistory: MutableList<AgentAction> = mutableListOf()
    private var isRunning = false

    inner class AgentAction(
        val action: String,
        val target: String? = null,
        val data: Any? = null
    )

    data class AgentResponse(
        val action: String,
        val target: String? = null,
        val parameters: Map<String, Any>? = null,
        val data: Map<String, Any>? = null,
        val message: String? = null,
        val done: Boolean = false
    )

    data class BrainRequest(
        val model: String,
        val messages: List<BrainMessage>,
        val temperature: Double = 0.4,
        val max_tokens: Int = 2400
    )

    data class BrainMessage(
        val role: String,
        val content: String
    )

    data class BrainResponse(
        val choices: List<BrainChoice>,
        val usage: Usage?
    )

    data class BrainChoice(
        val message: MessageResponse,
        val finish_reason: String?
    )

    data class MessageResponse(
        val content: String?
    )

    data class Usage(
        val prompt_tokens: Int,
        val completion_tokens: Int,
        val total_tokens: Int
    )

    fun setGoal(goal: String) {
        currentGoal = goal
        Log.d(TAG, "Goal set: $goal")
    }

    /**
     * Entry point for a speakable command (typed, tap-to-talk or wake word).
     * Runs the whole plan strictly on [Dispatchers.IO] and always gives feedback.
     */
    suspend fun processCommand(command: String): AgentResponse {
        if (command.isBlank()) {
            val empty = AgentResponse(action = "talk", message = "Buyruq bo'sh", done = true)
            speakAndToast(empty.message ?: "")
            return empty
        }
        Log.d(TAG, "processCommand: $command")
        return runGoal(command)
    }

    /**
     * Multi-step autonomous goal loop: ask the LLM for the next action, execute it,
     * announce it, and keep going until the goal is reported done or we hit [maxSteps].
     */
    suspend fun runGoal(goal: String, maxSteps: Int = 6): AgentResponse = withContext(Dispatchers.IO) {
        setGoal(goal)
        isRunning = true
        var last = AgentResponse(action = "done", message = "Vazifa yakunlandi", done = true)
        try {
            var step = 0
            var previousSignature = ""
            while (step < maxSteps) {
                step++
                val screenState = captureScreenState()
                val raw = try {
                    sendToGroq(buildPrompt(goal, screenState, step))
                } catch (e: Exception) {
                    Log.e(TAG, "Groq step error: ${e.message}")
                    getFallbackResponse(goal)
                }

                val parsed = try {
                    gson.fromJson(raw, AgentResponse::class.java) ?: fallbackAgentResponse(goal)
                } catch (e: Exception) {
                    fallbackAgentResponse(goal)
                }

                val announce = parsed.message?.takeIf { it.isNotBlank() }
                    ?: "Qadam $step: ${parsed.action}"
                speakAndToast(announce)

                val actionData: Any? = parsed.data ?: parsed.parameters ?: parsed.message
                val executed = try {
                    executeAction(AgentAction(parsed.action, parsed.target, actionData))
                } catch (e: Exception) {
                    Log.e(TAG, "Execute step error: ${e.message}")
                    parsed.copy(message = e.message ?: parsed.message)
                }
                last = executed

                val signature = "${parsed.action.lowercase()}|${parsed.target.orEmpty()}"
                val repeated = signature == previousSignature
                previousSignature = signature

                if (parsed.done || executed.done || repeated ||
                    parsed.action.lowercase() in TERMINAL_ACTIONS
                ) {
                    break
                }

                val stepResult = executed.message
                if (!stepResult.isNullOrBlank() && stepResult != announce) {
                    speakAndToast(stepResult)
                }
                delay(1000)
            }
            val finalMessage = last.message?.takeIf { it.isNotBlank() } ?: "Vazifa yakunlandi"
            last = last.copy(message = finalMessage)
            speakAndToast(finalMessage)
        } catch (e: Exception) {
            Log.e(TAG, "runGoal error: ${e.message}")
            last = AgentResponse(action = "talk", message = "Xato: ${e.message}", done = true)
            speakAndToast(last.message ?: "")
        } finally {
            isRunning = false
        }
        last
    }

    /** Speaks [message] via TTS and shows a Toast so the agent is never silent. */
    fun speakAndToast(message: String) {
        if (message.isBlank()) return
        mainHandler.post {
            try {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Toast error: ${e.message}")
            }
        }
        try {
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, System.currentTimeMillis().toString())
        } catch (e: Exception) {
            Log.e(TAG, "Agent TTS speak error: ${e.message}")
        }
    }

    private fun fallbackAgentResponse(command: String): AgentResponse {
        return try {
            gson.fromJson(getFallbackResponse(command), AgentResponse::class.java)
                ?: AgentResponse(action = "talk", message = "Men tushunmadim", done = true)
        } catch (e: Exception) {
            AgentResponse(action = "talk", message = "Men tushunmadim", done = true)
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Agent TTS shutdown error: ${e.message}")
        }
        tts = null
    }

    fun getGoal(): String = currentGoal

    fun isAgentRunning(): Boolean = isRunning

    // ------------------------------------------------------ Action routing

    /**
     * Executes a single agent action through the AccessibilityService (UI automation),
     * the DeviceController (hardware/system) or the WebAppGenerator (generated apps).
     */
    suspend fun executeAction(action: AgentAction): AgentResponse = withContext(Dispatchers.IO) {
        isRunning = true
        val params = action.data as? Map<*, *>
        val service = AgentAccessibilityService.instance
        val actionName = action.action.lowercase().trim()

        try {
            actionHistory.add(action)
            if (actionHistory.size > 50) actionHistory.removeAt(0)
        } catch (e: Exception) {
            // ignore
        }

        val response: AgentResponse = when (actionName) {
            "click", "tap_text" -> {
                val target = action.target ?: (params?.get("text") as? String) ?: ""
                val x = (params?.get("x") as? Number)?.toInt()
                val y = (params?.get("y") as? Number)?.toInt()
                when {
                    x != null && y != null && service != null -> {
                        service.tapAtCoordinates(x, y)
                        AgentResponse("click", target, message = "($x, $y) bosildi", done = false)
                    }
                    target.isNotEmpty() && service != null -> {
                        val ok = service.clickByText(target)
                        AgentResponse("click", target, message = if (ok) "\"$target\" bosildi" else "\"$target\" topilmadi", done = false)
                    }
                    service == null -> AgentResponse("click", target, message = "Accessibility xizmati o'chirilgan", done = true)
                    else -> AgentResponse("click", target, message = "Element topilmadi", done = false)
                }
            }
            "tap_coords", "tap" -> {
                val x = (params?.get("x") as? Number)?.toInt() ?: 0
                val y = (params?.get("y") as? Number)?.toInt() ?: 0
                service?.tapAtCoordinates(x, y)
                AgentResponse("tap_coords", "$x,$y", message = "($x, $y) bosildi", done = false)
            }
            "type_text", "type" -> {
                val text = (params?.get("text") as? String) ?: action.target ?: ""
                val submit = (params?.get("submit") as? Boolean) ?: false
                if (service != null && text.isNotEmpty()) {
                    if (submit) service.typeAndSubmit(text) else service.setText(text)
                    AgentResponse("type_text", text, message = "Yozildi: $text", done = false)
                } else {
                    AgentResponse("type_text", text, message = "Matn maydoni topilmadi", done = false)
                }
            }
            "scroll" -> {
                val direction = (params?.get("direction") as? String) ?: action.target ?: "down"
                service?.scroll(direction)
                AgentResponse("scroll", direction, message = "$direction tomonga surildi", done = false)
            }
            "swipe" -> {
                val fromX = (params?.get("fromX") as? Number)?.toInt() ?: 0
                val fromY = (params?.get("fromY") as? Number)?.toInt() ?: 0
                val toX = (params?.get("toX") as? Number)?.toInt() ?: 0
                val toY = (params?.get("toY") as? Number)?.toInt() ?: 0
                service?.swipe(fromX, fromY, toX, toY)
                AgentResponse("swipe", "$fromX,$fromY,$toX,$toY", message = "Surildi", done = false)
            }
            "long_press" -> {
                val x = (params?.get("x") as? Number)?.toInt() ?: 0
                val y = (params?.get("y") as? Number)?.toInt() ?: 0
                service?.longPressAtCoordinates(x, y)
                AgentResponse("long_press", "$x,$y", message = "Uzoq bosildi", done = false)
            }
            "open_app", "launch" -> {
                val name = action.target ?: (params?.get("package") as? String)
                    ?: (params?.get("name") as? String) ?: ""
                val ok = DeviceController(context).launchAppOrSearch(name)
                AgentResponse("open_app", name, message = if (ok) "$name ochilmoqda" else "$name topilmadi", done = false)
            }
            "global" -> {
                val name = action.target ?: (params?.get("action") as? String) ?: "HOME"
                val ok = service?.performGlobalByName(name) ?: false
                AgentResponse("global", name, message = if (ok) "$name bajarildi" else "Global amal bajarilmadi", done = false)
            }
            "device" -> {
                val cmd = action.target ?: (params?.get("cmd") as? String)
                    ?: (params?.get("command") as? String) ?: ""
                val result = deviceController.executeCommand(cmd)
                AgentResponse("device", cmd, message = result.message, done = result.success)
            }
            "call" -> {
                val number = action.target ?: (params?.get("number") as? String) ?: ""
                val ok = deviceController.callPhone(number)
                AgentResponse("call", number, message = if (ok) "Qo'ng'iroq: $number" else "Qo'ng'iroq qilib bo'lmadi", done = true)
            }
            "sms" -> {
                val number = action.target ?: (params?.get("number") as? String) ?: ""
                val text = (params?.get("text") as? String) ?: (params?.get("message") as? String) ?: ""
                val ok = deviceController.sendSMS(number, text)
                AgentResponse("sms", number, message = if (ok) "SMS yuborildi: $number" else "SMS yuborilmadi", done = true)
            }
            "alarm" -> {
                val hour = (params?.get("hour") as? Number)?.toInt() ?: 0
                val minute = (params?.get("minute") as? Number)?.toInt() ?: 0
                val ok = deviceController.setAlarmClock(hour, minute)
                AgentResponse("alarm", "$hour:$minute", message = if (ok) "Budilnik: $hour:$minute" else "Budilnik qo'yilmadi", done = true)
            }
            "timer" -> {
                val seconds = (params?.get("seconds") as? Number)?.toInt()
                    ?: (action.target?.filter { it.isDigit() }?.toIntOrNull()) ?: 0
                val ok = deviceController.startTimer(seconds)
                AgentResponse("timer", "$seconds", message = if (ok) "Taymer: $seconds soniya" else "Taymer qo'yilmadi", done = true)
            }
            "create_web", "create_app", "generate_web" -> {
                val html = (params?.get("html") as? String) ?: (params?.get("code") as? String) ?: action.target
                val title = (params?.get("title") as? String) ?: currentGoal.ifBlank { "Revilend Web App" }
                val resolved = webAppGenerator.resolveHtml(currentGoal, html)
                val ok = webAppGenerator.launch(resolved, title)
                AgentResponse(
                    "create_web",
                    title,
                    message = if (ok) "Web ilova yaratildi va ochildi: $title" else "Web ilovani ochib bo'lmadi",
                    done = true
                )
            }
            "talk", "say" -> {
                val message = (params?.get("message") as? String) ?: action.target ?: "Tayyor"
                AgentResponse("talk", message, message = message, done = true)
            }
            "done" -> {
                val message = (params?.get("message") as? String) ?: action.target ?: "Vazifa bajarildi"
                AgentResponse("done", message, message = message, done = true)
            }
            else -> {
                Log.w(TAG, "Unknown action: ${action.action}")
                AgentResponse(action.action, action.target, message = "Noma'lum amal: ${action.action}", done = true)
            }
        }
        isRunning = false
        response
    }

    suspend fun getAIResponse(userMessage: String, screenState: ScreenState? = null): String = withContext(Dispatchers.IO) {
        try {
            sendToGroq(buildPrompt(userMessage, screenState, 1))
        } catch (e: Exception) {
            Log.e(TAG, "AI error: ${e.message}")
            getFallbackResponse(userMessage)
        }
    }

    private fun buildPrompt(userMessage: String, screenState: ScreenState?, step: Int = 1): String {
        val elements = screenState?.description ?: "Ekran ma'lumoti yo'q"
        return """
            Sen Revilend AI - Android telefonni to'liq boshqaradigan avtonom agent.
            Sen FAQAT bitta JSON obyekt qaytarasan, boshqa matn yozmaysan.

            Maqsad: $userMessage
            Qadam: $step

            Hozirgi ekran:
            $elements

            Mumkin bo'lgan amallar (action):
            - open_app   : ilova/o'yinni ochish. target = ilova nomi yoki package (masalan "Telegram").
            - click      : matn/ikonka bo'yicha bosish. target = ekrandagi matn. Yoki data {x,y} bilan koordinataga bosish.
            - tap_coords : aniq koordinataga bosish. data {"x":540,"y":1200}
            - type_text  : matn terish. data {"text":"salom","submit":true} (submit=true bo'lsa yuboriladi)
            - scroll     : target = up|down|left|right
            - swipe      : data {"fromX":540,"fromY":1600,"toX":540,"toY":600}
            - long_press : data {"x":540,"y":1200}
            - global     : target = BACK | HOME | RECENTS | NOTIFICATIONS | QUICK_SETTINGS | LOCK_SCREEN
            - device     : target = TORCH_ON | TORCH_OFF | VOLUME_UP | VOLUME_DOWN | VOLUME_RING_UP | MUTE | UNMUTE | MAX_VOLUME | MEDIA_PLAY | MEDIA_PAUSE | MEDIA_NEXT | MEDIA_PREV | BATTERY | MEMORY | WIFI
            - call       : data {"number":"+998..."}
            - sms        : data {"number":"+998...","text":"xabar"}
            - alarm      : data {"hour":7,"minute":30}
            - timer      : data {"seconds":60}
            - create_web : web ilova/oyin yaratish. data {"title":"Snake","html":"<!DOCTYPE html>..."} HTML to'liq va o'z ichida bo'lishi kerak.
            - talk       : foydalanuvchiga aytish. data {"message":"..."}
            - done       : maqsad bajarildi. data {"message":"..."}

            Javob formati (faqat JSON):
            {"action":"...","target":"...","data":{...},"message":"qisqa o'zbekcha izoh","done":false}

            Qoidalar:
            - Maqsad bir nechta qadamdan iborat bo'lsa har javobda FAQAT keyingi bitta amalni bajar.
            - Maqsad to'liq bajarilganda "done":true qaytar.
            - Ekrandagi matnlar va koordinatalardan foydalanib aniq elementni tanla.
            - Har bir javobda "message" maydonini o'zbek tilida yoz.
        """.trimIndent()
    }

    private suspend fun sendToGroq(prompt: String): String = withContext(Dispatchers.IO) {
        val requestBody = BrainRequest(
            model = MODEL,
            messages = listOf(BrainMessage(role = "system", content = prompt))
        )

        val jsonBody = gson.toJson(requestBody)
        val request = Request.Builder()
            .url(API_URL)
            .header("Authorization", "Bearer $API_KEY")
            .header("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()

        if (response.isSuccessful) {
            val responseBody = response.body?.string()
            if (responseBody != null) {
                val brainResponse = gson.fromJson(responseBody, BrainResponse::class.java)
                extractJson(brainResponse?.choices?.firstOrNull()?.message?.content) ?: getFallbackResponse("")
            } else {
                getFallbackResponse("")
            }
        } else {
            Log.e(TAG, "Groq error: ${response.code} ${response.message}")
            getFallbackResponse("")
        }
    }

    /** Groq sometimes wraps JSON in prose or code fences - extract the first JSON object. */
    private fun extractJson(content: String?): String? {
        if (content.isNullOrBlank()) return null
        val trimmed = content.trim()
        if (trimmed.startsWith("{")) return trimmed
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start in 0 until end) {
            return trimmed.substring(start, end + 1)
        }
        return null
    }

    private fun getFallbackResponse(userMessage: String): String {
        val m = userMessage.lowercase()

        return when {
            m.contains("chiroq") || m.contains("fonar") || m.contains("flashlight") || m.contains("torch") ->
                """{"action":"device","target":"TORCH_ON","message":"Chiroq yondi","done":true}"""
            m.contains("batareya") || m.contains("battery") ->
                """{"action":"device","target":"BATTERY","message":"Batareya holati","done":true}"""
            m.contains("ovozsiz") || m.contains("mute") || m.contains("jim") ->
                """{"action":"device","target":"MUTE","message":"Ovozsiz rejim","done":true}"""
            m.contains("ovozi") || m.contains("max volume") ->
                """{"action":"device","target":"MAX_VOLUME","message":"Ovoz maksimal","done":true}"""
            m.contains("wifi") ->
                """{"action":"device","target":"WIFI","message":"WiFi holati","done":true}"""
            m.contains("media next") || m.contains("keyingi") ->
                """{"action":"device","target":"MEDIA_NEXT","message":"Keyingi trek","done":true}"""
            m.contains("home") || m.contains("bosh sahifa") ->
                """{"action":"global","target":"HOME","message":"Bosh sahifa","done":false}"""
            m.contains("orqaga") || m.contains("back") ->
                """{"action":"global","target":"BACK","message":"Orqaga","done":false}"""
            m.contains("sayt") || m.contains("website") || m.contains("ilova yarat") || m.contains("o'yin") || m.contains("oyin") ->
                """{"action":"create_web","data":{"title":"Revilend Web"},"message":"Web ilova yaratilmoqda","done":true}"""
            else -> {
                val appPart = m.substringAfter("och ", "").substringAfter("open ", "").trim()
                if (appPart.isNotEmpty()) {
                    """{"action":"open_app","target":"$appPart","message":"$appPart ochilmoqda","done":false}"""
                } else {
                    """{"action":"talk","data":{"message":"Kechirasiz, tushunmadim. Qayta ayting."},"message":"Tushunmadim","done":true}"""
                }
            }
        }
    }

    private fun defaultScreenState(): ScreenState = ScreenState(
        description = "Ekran ma'lumoti mavjud emas",
        elements = emptyList(),
        packageName = "",
        className = ""
    )

    /** Reads the real on-screen UI tree from the AccessibilityService when connected. */
    private fun captureScreenState(): ScreenState {
        val service = AgentAccessibilityService.instance ?: return defaultScreenState()
        return try {
            ScreenState(
                description = service.captureScreenDescription(),
                elements = emptyList(),
                packageName = service.currentPackageName,
                className = service.currentClassName
            )
        } catch (e: Exception) {
            Log.e(TAG, "captureScreenState error: ${e.message}")
            defaultScreenState()
        }
    }

    fun startAutonomousLoop() {
        if (isRunning) return
        isRunning = true
        Log.d(TAG, "Autonomous loop armed")
    }

    fun stopAutonomousLoop() {
        isRunning = false
        Log.d(TAG, "Stopping autonomous loop")
    }

    fun getActionHistory(): List<AgentAction> = actionHistory

    fun clearActionHistory() {
        actionHistory.clear()
    }

    suspend fun perceiveAndAct(userMessage: String): AgentResponse = withContext(Dispatchers.IO) {
        val screenState = captureScreenState()
        val response = sendToGroq(buildPrompt(userMessage, screenState, 1))
        try {
            gson.fromJson(response, AgentResponse::class.java)
                ?: AgentResponse(action = "talk", message = "Xato yuz berdi", done = true)
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            AgentResponse(action = "talk", message = "Xato yuz berdi", done = true)
        }
    }

    data class ScreenState(
        val description: String,
        val elements: List<ScreenElement>,
        val packageName: String,
        val className: String
    )

    data class ScreenElement(
        val id: String?,
        val text: String?,
        val contentDescription: String?,
        val className: String?,
        val bounds: android.graphics.Rect?,
        val clickable: Boolean
    )
}
