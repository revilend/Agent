package com.revilend.ai.assistant.agent

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.revilend.ai.assistant.control.DeviceController
import com.revilend.ai.assistant.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class AgentBrain(private val context: Context) {

    companion object {
        private const val TAG = "AgentBrain"
        private const val API_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val MODEL = "openai/gpt-oss-120b"
        private const val API_KEY = "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"
        private const val TIMEOUT_SECONDS = 60L
    }

    private val deviceController = DeviceController(context)
    private val prefs = PreferencesManager(context)
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private var currentGoal: String = ""
    private var actionHistory: MutableList<AgentAction> = mutableListOf()
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
        val message: String? = null,
        val done: Boolean = false
    )

    data class BrainRequest(
        val model: String,
        val messages: List<BrainMessage>,
        val temperature: Double = 0.7,
        val max_tokens: Int = 1000
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

    fun getGoal(): String = currentGoal

    fun isAgentRunning(): Boolean = isRunning

    suspend fun executeAction(action: AgentAction): AgentResponse {
        isRunning = true
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Executing action: ${action.action}")
            when (action.action.lowercase()) {
                "click" -> {
                    val target = action.target ?: "unknown"
                    deviceController.executeCommand("CLICK:$target")
                    AgentResponse(
                        action = "click",
                        target = target,
                        message = "Clicked: $target",
                        done = false
                    )
                }
                "tap_coords" -> {
                    val x = (action.data as? Map<*, *>)?.get("x") as? Number ?: 0
                    val y = (action.data as? Map<*, *>)?.get("y") as? Number ?: 0
                    Log.d(TAG, "Tap at: $x, $y")
                    AgentResponse(
                        action = "tap_coords",
                        parameters = mapOf("x" to x, "y" to y),
                        message = "Tapped at ($x, $y)",
                        done = false
                    )
                }
                "type_text" -> {
                    val text = action.data as? String ?: ""
                    Log.d(TAG, "Typing: $text")
                    AgentResponse(
                        action = "type_text",
                        message = "Typed: $text",
                        done = false
                    )
                }
                "scroll" -> {
                    val direction = action.target?.lowercase() ?: "down"
                    deviceController.executeCommand("SCROLL:$direction")
                    AgentResponse(
                        action = "scroll",
                        target = direction,
                        message = "Scrolled $direction",
                        done = false
                    )
                }
                "open_app" -> {
                    val package = action.target ?: action.data as? String ?: ""
                    val success = deviceController.launchApp(package)
                    AgentResponse(
                        action = "open_app",
                        target = package,
                        message = if (success) "Opened $package" else "Failed to open $package",
                        done = !success
                    )
                }
                "global" -> {
                    val type = action.target?.uppercase() ?: "HOME"
                    when (type) {
                        "BACK" -> {
                            // Handle back action
                            Log.d(TAG, "Global BACK")
                            deviceController.executeCommand("BACK")
                        }
                        "HOME" -> {
                            Log.d(TAG, "Global HOME")
                            deviceController.executeCommand("HOME")
                        }
                        "RECENTS" -> {
                            Log.d(TAG, "Global RECENTS")
                            deviceController.executeCommand("RECENTS")
                        }
                        "LOCK_SCREEN" -> {
                            Log.d(TAG, "Global LOCK_SCREEN")
                            deviceController.executeCommand("LOCK_SCREEN")
                        }
                        else -> {
                            Log.w(TAG, "Unknown global action: $type")
                        }
                    }
                    AgentResponse(
                        action = "global",
                        target = type,
                        message = "Executed global action: $type",
                        done = false
                    )
                }
                "device" -> {
                    val cmd = action.target ?: action.data as? String ?: ""
                    val result = deviceController.executeCommand(cmd)
                    AgentResponse(
                        action = "device",
                        target = cmd,
                        message = result.message,
                        done = !result.success
                    )
                }
                "create_web" -> {
                    val html = action.data as? String ?: action.target ?: ""
                    Log.d(TAG, "Creating web: ${html.take(100)}...")
                    AgentResponse(
                        action = "create_web",
                        message = "Web app created",
                        done = false
                    )
                }
                "talk" -> {
                    val message = action.data as? String ?: action.target ?: ""
                    Log.d(TAG, "Talking: $message")
                    AgentResponse(
                        action = "talk",
                        message = message,
                        done = false
                    )
                }
                "done" -> {
                    val message = action.data as? String ?: action.target ?: "Vazifa bajarildi"
                    Log.d(TAG, "Task done: $message")
                    AgentResponse(
                        action = "done",
                        message = message,
                        done = true
                    )
                }
                else -> {
                    Log.w(TAG, "Unknown action: ${action.action}")
                    AgentResponse(
                        action = action.action,
                        message = "Unknown action: ${action.action}",
                        done = true
                    )
                }
            }
        }
    }

    suspend fun getAIResponse(userMessage: String, screenState: ScreenState? = null): String = withContext(Dispatchers.IO) {
        try {
            val prompt = buildPrompt(userMessage, screenState)
            val response = sendToGroq(prompt)
            Log.d(TAG, "AI response: $response")
            response
        } catch (e: Exception) {
            Log.e(TAG, "AI error: ${e.message}")
            getFallbackResponse(userMessage)
        }
    }

    private fun buildPrompt(userMessage: String, screenState: ScreenState?): String {
        val systemPrompt = """
            You are Revilend AI - Shaxsiy avtonom yordamchi agent.
            You control an Android phone via voice commands and accessibility APIs.
            
            Current goal: $currentGoal
            
            Screen state:
            ${screenState?.description ?: "No screen information available"}
            
            Available actions:
            - click: Click on screen element by text or ID
            - tap_coords: Tap specific coordinates (x, y)
            - type_text: Type text into focused field
            - scroll: Scroll screen (up/down/left/right)
            - open_app: Launch app by package name
            - global: Perform global action (BACK, HOME, RECENTS, LOCK_SCREEN)
            - device: Execute device command (TORCH_ON, TORCH_OFF, VOLUME_UP, BATTERY, etc.)
            - create_web: Create interactive web content
            - talk: Speak a message
            - done: Mark task as complete
            
            Respond in JSON format:
            {"action": "click|talk|open_app|global|device|create_web|type_text|scroll|done", 
             "target": "element_id|message|package|command|direction",
             "data": {"x": 540, "y": 1200, "text": "message"},
             "message": "optional human-readable message",
             "done": true/false}
            
            User request: $userMessage
        """.trimIndent()

        return systemPrompt
    }

    private suspend fun sendToGroq(prompt: String): String = withContext(Dispatchers.IO) {
        val requestBody = BrainRequest(
            model = MODEL,
            messages = listOf(
                BrainMessage(role = "system", content = prompt)
            )
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
                brainResponse.choices.firstOrNull()?.message?.content ?: getFallbackResponse("")
            } else {
                getFallbackResponse("")
            }
        } else {
            Log.e(TAG, "Groq error: ${response.code} ${response.message}")
            getFallbackResponse("")
        }
    }

    private fun getFallbackResponse(userMessage: String): String {
        val messageLower = userMessage.lowercase()

        return when {
            messageLower.contains("home") || messageLower.contains("bosh") -> 
                """{"action":"global","target":"HOME","message":"Bosh sahifaga qaytildi","done":false}"""
            messageLower.contains("back") || messageLower.contains("orqaga") -> 
                """{"action":"global","target":"BACK","message":"Orqaga qaytildi","done":false}"""
            messageLower.contains("app") || messageLower.contains("ilova") -> {
                val appName = messageLower.substringAfter("open ").substringAfter("och ").trim()
                """{"action":"open_app","target":"$appName","message":"Ilova ochilmoqda: $appName","done":false}"""
            }
            messageLower.contains("light") || messageLower.contains("torc") -> 
                """{"action":"device","target":"TORCH_ON","message":"Flashlight yongildi","done":false}"""
            messageLower.contains("battery") -> 
                """{"action":"device","target":"BATTERY","message":"Battery holati","done":false}"""
            messageLower.contains("mute") || messageLower.contains("ovozsiz") -> 
                """{"action":"device","target":"MUTE","message":"Ovozsiz rejim","done":false}"""
            messageLower.contains("scroll") -> 
                """{"action":"scroll","target":"down","message":"Ekranda sakrammoq","done":false}"""
            messageLower.contains("read") || messageLower.contains("o'qi") -> 
                """{"action":"talk","data":"Ekranda: ...","message":"Ekranda o'qilmoqda","done":false}"""
            else -> 
                """{"action":"talk","data":"Men tushunmadim. Qayta ayting.","message":"Tushunmadim","done":true}"""
        }
    }

    fun startAutonomousLoop() {
        if (isRunning) return

        isRunning = true
        Log.d(TAG, "Starting autonomous loop")

        // This would be a continuous loop in production
        // For now, just log and return
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
        val prompt = buildPrompt(userMessage, screenState)
        val response = sendToGroq(prompt)

        try {
            val actionResponse = gson.fromJson(response, AgentResponse::class.java)
            actionResponse
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            AgentResponse(
                action = "talk",
                message = "Xato yuz berdi",
                done = true
            )
        }
    }

    private fun captureScreenState(): ScreenState {
        // In a real implementation, this would use AccessibilityService to get screen info
        return ScreenState(
            description = "Screen not available in this context",
            elements = emptyList(),
            packageName = "",
            className = ""
        )
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
