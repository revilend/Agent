package com.revilend.ai.assistant.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.service.dreams.DreamService
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.revilend.ai.assistant.R
import com.revilend.ai.assistant.agent.AgentBrain
import com.revilend.ai.assistant.ui.MainActivity
import com.revilend.ai.assistant.util.PreferencesManager
import com.revilend.ai.assistant.util.SpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class FloatingHudService : Service() {

    companion object {
        private const val TAG = "FloatingHudService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "revilend_hud_channel"
        private const val ACTION_TAP = "com.revilend.ai.assistant.ACTION_TAP"
        private const val ACTION_LONG_PRESS = "com.revilend.ai.assistant.ACTION_LONG_PRESS"
        private const val ACTION_SPEECH = "com.revilend.ai.assistant.ACTION_SPEECH"
        private const val ACTION_HIDE = "com.revilend.ai.assistant.ACTION_HIDE"
        private const val ACTION_SHOW = "com.revilend.ai.assistant.ACTION_SHOW"
        private const val ACTION_DOCK = "com.revilend.ai.assistant.ACTION_DOCK"
    }

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var speechManager: SpeechManager
    private lateinit var agentBrain: AgentBrain
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var toneGenerator: ToneGenerator? = null
    private var binding: FloatingHudLayout? = null
    private var windowManager: WindowManager? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var isDragging = false
    private var isDocked = false
    private var initialX = 0f
    private var initialY = 0f
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private val handler = Handler(Looper.getMainLooper())
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(Vibrator::class.java)
        } else null
    }

    private var hudState = HudState.Idle
    private var actionMessage = ""
    private var actionStatusAlpha = 0.8f

    private val statePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.FILL
        maskFilter = android.graphics.BlurMaskFilter(30f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    private val soundwavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    private var state: HudState = HudState.Idle
    private var stateTime = 0L
    private val animationHandler = Handler(Looper.getMainLooper())
    private val animationRunnable = object : Runnable {
        override fun run() {
            updateAnimation()
            animationHandler.postDelayed(this, 16)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FloatingHudService created")
        createNotificationChannel()
        preferencesManager = PreferencesManager(this)
        speechManager = SpeechManager(this)
        agentBrain = AgentBrain(this)
        toneGenerator = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        } catch (e: Exception) {
            Log.e(TAG, "ToneGenerator init failed: ${e.message}")
            null
        }

        // Declare the microphone foreground-service type only when we can legally record.
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && micGranted) {
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupWindowParameters()
        setupFloatingView()
        startAnimation()
        setupWakeWordListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent != null) {
            when (intent.action) {
                ACTION_TAP -> onHudTap()
                ACTION_LONG_PRESS -> onHudLongPress()
                ACTION_SPEECH -> onSpeechRequested()
                ACTION_HIDE -> hideHud()
                ACTION_SHOW -> showHud()
                ACTION_DOCK -> toggleDock()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopAnimation()
        serviceScope.cancel()
        try {
            if (::speechManager.isInitialized) {
                speechManager.stopWakeWordListening()
                speechManager.shutdown()
            }
            if (::agentBrain.isInitialized) agentBrain.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error: ${e.message}")
        }
        toneGenerator?.release()
        toneGenerator = null
        binding?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Remove view error: ${e.message}")
            }
        }
        binding = null
        Log.d(TAG, "FloatingHudService destroyed")
    }

    // ---- Wake word ("Revilend") hands-free handling ----

    private fun setupWakeWordListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted; wake word listening disabled")
            return
        }
        speechManager.voiceLanguage = preferencesManager.voiceLanguage
        speechManager.wakeWordListener = { command -> onWakeWordCommand(command) }
        speechManager.onWakeWordOnly = { onWakeWordOnly() }
        speechManager.startWakeWordListening()
        Log.d(TAG, "Wake word listening started")
    }

    private fun onWakeWordOnly() {
        Log.d(TAG, "Wake word only detected")
        playActivationFeedback()
        setState(HudState.Listening)
        actionMessage = "Labbay?"
        binding?.setActionMessage(actionMessage)
        speechManager.speak("Labbay, sizni eshitmoqdaman")
    }

    private fun onWakeWordCommand(command: String) {
        Log.d(TAG, "Wake word command: $command")
        playActivationFeedback()
        hudState = HudState.Thinking
        stateTime = System.currentTimeMillis()
        binding?.setState(HudState.Thinking)
        actionMessage = command
        binding?.setActionMessage(command)

        serviceScope.launch {
            try {
                val response = agentBrain.processCommand(command)
                actionMessage = response.message ?: command
            } catch (e: Exception) {
                Log.e(TAG, "processCommand failed: ${e.message}")
                actionMessage = "Xato: ${e.message}"
            } finally {
                binding?.setActionMessage(actionMessage)
                hudState = HudState.Action
                stateTime = System.currentTimeMillis()
                binding?.setState(HudState.Action)
                handler.postDelayed({ resetToIdle() }, 2500)
            }
        }
    }

    private fun resetToIdle() {
        hudState = HudState.Idle
        stateTime = System.currentTimeMillis()
        actionMessage = ""
        binding?.setActionMessage("")
        binding?.setState(HudState.Idle)
    }

    private fun playActivationFeedback() {
        vibrate(40)
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (e: Exception) {
            Log.e(TAG, "Tone error: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Revilend AI HUD",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Floating HUD service channel"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Revilend AI")
                .setContentText("Floating HUD active")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        } else {
            NotificationCompat.Builder(this)
                .setContentTitle("Revilend AI")
                .setContentText("Floating HUD active")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        }
    }

    private fun setupWindowParameters() {
        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 100
            y = 300
        }
    }

    private fun setupFloatingView() {
        binding = FloatingHudLayout(this).apply {
            layoutParams = windowParams!!
            updatePositionFromPrefs()
        }

        binding?.setOnTouchListener { _, event ->
            handleTouch(event)
            false
        }

        windowManager?.addView(binding!!, windowParams!!)
        Log.d(TAG, "Floating view added")
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialX = (windowParams?.x ?: 0).toFloat()
                initialY = (windowParams?.y ?: 0).toFloat()
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging && abs(event.rawX - initialTouchX) > 10 && abs(event.rawY - initialTouchY) > 10) {
                    isDragging = true
                    preferencesManager.isHudDocked = false
                    preferencesManager.isHudVisible = true
                }
                if (isDragging) {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    windowParams?.x = (initialX + dx).toInt()
                    windowParams?.y = (initialY + dy).toInt()
                    updateViewPosition()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    // Check if docked to edge
                    val params = windowParams ?: return false
                    val screenWidth = windowManager?.defaultDisplay?.width ?: 0
                    val screenHeight = windowManager?.defaultDisplay?.height ?: 0
                    val hudRadius = 120

                    if (params.x < 50 && params.y > screenHeight / 4 && params.y < screenHeight * 3 / 4) {
                        // Docked to left edge - minimize
                        isDocked = true
                        preferencesManager.isHudDocked = true
                        preferencesManager.isHudVisible = false
                    } else if (params.x > screenWidth - 50 && params.y > screenHeight / 4 && params.y < screenHeight * 3 / 4) {
                        // Docked to right edge - minimize
                        isDocked = true
                        preferencesManager.isHudDocked = true
                        preferencesManager.isHudVisible = false
                    }
                }
                return true
            }
        }
        return false
    }

    private fun updateViewPosition() {
        windowManager?.updateViewLayout(binding!!, windowParams!!)
    }

    private fun updatePositionFromPrefs() {
        val params = windowParams ?: return
        params.x = (preferencesManager.hudPositionX * (windowManager?.defaultDisplay?.width ?: 1080) / 100).toInt()
        params.y = (preferencesManager.hudPositionY * (windowManager?.defaultDisplay?.height ?: 1920) / 100).toInt()
        updateViewPosition()
    }

    private fun onHudTap() {
        Log.d(TAG, "HUD tapped")
        // Start speech recognition
        val broadcastIntent = Intent(ACTION_SPEECH)
        sendBroadcast(broadcastIntent)
        vibrate(50)
        hudState = HudState.Listening
        stateTime = System.currentTimeMillis()
    }

    private fun onHudLongPress() {
        Log.d(TAG, "HUD long pressed")
        // Open text input dialog
        val broadcastIntent = Intent(ACTION_LONG_PRESS)
        sendBroadcast(broadcastIntent)
        vibrate(100)
        hudState = HudState.Thinking
        stateTime = System.currentTimeMillis()
    }

    private fun onSpeechRequested() {
        Log.d(TAG, "Speech requested")
        // Forward to accessibility service or MainActivity
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    private fun hideHud() {
        if (binding != null && windowParams != null) {
            try {
                windowManager?.removeView(binding!!)
                binding = null
                preferencesManager.isHudVisible = false
            } catch (e: Exception) {
                Log.e(TAG, "Hide error: ${e.message}")
            }
        }
    }

    private fun showHud() {
        if (binding == null) {
            setupWindowParameters()
            setupFloatingView()
        }
        preferencesManager.isHudVisible = true
    }

    private fun toggleDock() {
        if (isDocked) {
            isDocked = false
            preferencesManager.isHudDocked = false
            preferencesManager.isHudVisible = true
            showHud()
        } else {
            hideHud()
        }
    }

    private fun vibrate(duration: Long) {
        vibrator?.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun setActionMessage(message: String) {
        actionMessage = message
        binding?.setActionMessage(message)
    }

    // HUD state management
    fun setState(state: HudState) {
        hudState = state
        stateTime = System.currentTimeMillis()
        binding?.setState(state)
    }

    fun getState(): HudState = hudState

    // Animation updates
    private fun updateAnimation() {
        val elapsed = System.currentTimeMillis() - stateTime
        binding?.updateAnimation(elapsed)
    }

    fun startAnimation() {
        animationHandler.post(animationRunnable)
    }

    fun stopAnimation() {
        animationHandler.removeCallbacks(animationRunnable)
    }

    // Public API
    fun setActionStatus(action: String, message: String) {
        setActionMessage(message)
        Log.d(TAG, "Action: $action -> $message")
    }

    // Status badge
    fun updateActionStatus(action: String, message: String) {
        setActionMessage("$action: $message")
    }
}

enum class HudState {
    Idle,
    Listening,
    Thinking,
    Action
}

class FloatingHudLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var hudState: HudState = HudState.Idle
    private var actionMessage: String = ""
    private var elapsedTime: Long = 0L

    private val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF")
        textSize = 12f
        textAlign = Paint.Align.CENTER
    }

    private val soundwavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private var glowShader: SweepGradient? = null
    private var glowRadius = 100f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f

        when (hudState) {
            HudState.Idle -> drawIdleState(canvas, centerX, centerY)
            HudState.Listening -> drawListeningState(canvas, centerX, centerY)
            HudState.Thinking -> drawThinkingState(canvas, centerX, centerY)
            HudState.Action -> drawActionState(canvas, centerX, centerY)
        }

        drawActionBanner(canvas, centerX, centerY)
    }

    private var animationStep = 0f

    private fun drawIdleState(canvas: Canvas, cx: Float, cy: Float) {
        // Breathing glow effect
        val pulse = (sin(elapsedTime * 0.003) * 0.3 + 0.7).toFloat()
        glowRadius = 100 * pulse

        // Glow
        canvas.drawCircle(cx, cy, glowRadius + 20, glowPaint.apply {
            alpha = (180 * pulse).toInt()
        })

        // Main circle
        canvas.drawCircle(cx, cy, 72f, mainPaint.apply {
            setColor(Color.parseColor("#0D1117"))
            setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        })

        // Border
        canvas.drawCircle(cx, cy, 72f, soundwavePaint.apply {
            setColor(Color.parseColor("#00E5FF"))
            alpha = 100
        })

        // "REVILEND" text
        canvas.drawText("REVILEND", cx, cy - 8, textPaint.apply {
            setColor(Color.parseColor("#00E5FF"))
        })

        // Subtitle
        canvas.drawText("AI Assistant", cx, cy + 20, subtitlePaint)
    }

    private fun drawListeningState(canvas: Canvas, cx: Float, cy: Float) {
        val time = elapsedTime * 0.003

        // Pulsing soundwave animation
        val soundwaveRadius = (60f + (abs(sin(time * 4)) * 20)).toFloat()

        // Outer glow
        canvas.drawCircle(cx, cy, soundwaveRadius + 40, glowPaint.apply {
            alpha = (100 + abs(sin(time * 4)) * 100).toInt()
        })

        // Soundwave circles
        for (i in 1..3) {
            val radius = (30f + i * 20 + abs(sin(time * 4 + i)) * 15).toFloat()
            canvas.drawCircle(cx, cy, radius, soundwavePaint.apply {
                alpha = (150 - i * 40).toInt()
                strokeWidth = 3f - i * 0.5f
            })
        }

        // Main circle background
        canvas.drawCircle(cx, cy, 72f, mainPaint.apply {
            setColor(Color.parseColor("#0D1117"))
        })

        // Icon (mic symbol)
        drawMicIcon(canvas, cx, cy - 10)
    }

    private fun drawMicIcon(canvas: Canvas, cx: Float, cy: Float) {
        val micPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            style = Paint.Style.FILL
        }

        // Simplified mic icon
        canvas.drawCircle(cx, cy, 15f, micPaint)
        canvas.drawRect(cx - 3, cy - 25, cx + 3, cy + 5, micPaint.apply {
            setColor(Color.parseColor("#00E5FF"))
        })

        // Sound waves
        for (i in 1..3) {
            val radius = 20f + i * 10
            canvas.drawCircle(cx, cy, radius, soundwavePaint.apply {
                alpha = (120 - i * 35).toInt()
            })
        }
    }

    private fun drawThinkingState(canvas: Canvas, cx: Float, cy: Float) {
        val time = elapsedTime * 0.005
        val rotation = (time * 360) % 360

        // Glow
        canvas.drawCircle(cx, cy, 110f, glowPaint.apply {
            alpha = 100
        })

        // Main circle
        canvas.drawCircle(cx, cy, 72f, mainPaint.apply {
            setColor(Color.parseColor("#0D1117"))
        })

        // Rotating spinner
        canvas.save()
        canvas.rotate(rotation.toFloat(), cx, cy)
        canvas.drawCircle(cx, cy, 50f, spinnerPaint.apply {
            setColor(Color.parseColor("#00E5FF"))
            alpha = 200
        })

        // Spinner lines
        for (i in 0..3) {
            val angle = (i * 90).toFloat()
            val startX = cx + cos(Math.toRadians(angle.toDouble())).toFloat() * 40
            val startY = cy + sin(Math.toRadians(angle.toDouble())).toFloat() * 40
            val endX = cx + cos(Math.toRadians((angle + 45).toDouble())).toFloat() * 50
            val endY = cy + sin(Math.toRadians((angle + 45).toDouble())).toFloat() * 50
            canvas.drawLine(startX, startY, endX, endY, spinnerPaint.apply {
                alpha = 150
            })
        }
        canvas.restore()

        // "REVILEND" text
        canvas.drawText("REVILEND", cx, cy - 15, textPaint.apply {
            setColor(Color.parseColor("#00E5FF"))
        })

        // Thinking indicator
        canvas.drawText("Thinking...", cx, cy + 15, subtitlePaint.apply {
            setColor(Color.parseColor("#80FFFFFF"))
        })
    }

    private fun drawActionState(canvas: Canvas, cx: Float, cy: Float) {
        val time = elapsedTime * 0.003

        // Pulsing action glow
        val pulse = (sin(time * 6) * 0.3 + 0.7)
        canvas.drawCircle(cx, cy, (90 * pulse).toFloat(), glowPaint.apply {
            alpha = (150 * pulse).toInt()
        })

        // Main circle
        canvas.drawCircle(cx, cy, 72f, mainPaint.apply {
            setColor(Color.parseColor("#0D1117"))
        })

        // Border pulse
        canvas.drawCircle(cx, cy, 72f, soundwavePaint.apply {
            setColor(Color.parseColor("#00E5FF"))
            alpha = (100 + abs(sin(time * 6)) * 100).toInt()
        })

        // "REVILEND" text
        canvas.drawText("REVILEND", cx, cy - 8, textPaint.apply {
            setColor(Color.parseColor("#00E5FF"))
        })
    }

    private fun drawActionBanner(canvas: Canvas, cx: Float, cy: Float) {
        if (actionMessage.isNotEmpty()) {
            val bannerWidth = 400f
            val bannerHeight = 30f
            val bannerY = cy + 80f

            // Banner background
            val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1C2128")
            }
            canvas.drawRoundRect(
                cx - bannerWidth / 2,
                bannerY - bannerHeight / 2,
                cx + bannerWidth / 2,
                bannerY + bannerHeight / 2,
                8f, 8f, bannerPaint
            )

            // Banner border
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#00E5FF")
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
            }
            canvas.drawRoundRect(
                cx - bannerWidth / 2,
                bannerY - bannerHeight / 2,
                cx + bannerWidth / 2,
                bannerY + bannerHeight / 2,
                8f, 8f, borderPaint
            )

            // Banner text
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 12f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(actionMessage, cx, bannerY + 4, textPaint)
        }
    }

    fun setState(state: HudState) {
        hudState = state
        invalidate()
    }

    fun setActionMessage(message: String) {
        actionMessage = message
        invalidate()
    }

    fun updateAnimation(elapsed: Long) {
        elapsedTime = elapsed
        invalidate()
    }
}

private fun abs(value: Float): Float = kotlin.math.abs(value)
private fun sin(value: Double): Double = kotlin.math.sin(value)
private fun cos(value: Double): Double = kotlin.math.cos(value)
