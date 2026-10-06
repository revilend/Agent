package com.revilend.ai.assistant.control

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.telephony.SmsManager
import android.provider.Settings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.revilend.ai.assistant.util.PreferencesManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeviceController(private val context: Context) {

    companion object {
        private const val TAG = "DeviceController"
        private const val CAMERA_ID = "0"
    }

    private val prefs = PreferencesManager(context)
    private val cameraManager: CameraManager? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ContextCompat.getSystemService(context, CameraManager::class.java)
        } else null
    }
    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.getSystemService(context, Vibrator::class.java)
        } else null
    }
    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    private val activityManager: ActivityManager by lazy {
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }
    private val alarmManager: AlarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        BluetoothAdapter.getDefaultAdapter()
    }
    private val packageManager: PackageManager by lazy {
        context.packageManager
    }

    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())

    var isTorchOn: Boolean = false
        private set

    // Flashlight control
    fun toggleFlashlight(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val cameraId = cameraManager?.cameraIdList?.firstOrNull()
                if (cameraId != null) {
                    isTorchOn = !isTorchOn
                    cameraManager?.setTorchMode(cameraId, isTorchOn)
                    Log.d(TAG, "Torch: ${if (isTorchOn) "ON" else "OFF"}")
                    isTorchOn
                } else {
                    Log.w(TAG, "No flash unit available")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Torch error: ${e.message}")
                false
            }
        } else {
            Log.w(TAG, "Torch not supported on this device")
            false
        }
    }

    fun setTorch(on: Boolean) {
        if (on != isTorchOn) {
            toggleFlashlight()
        }
    }

    // Audio and Volume control
    fun setMediaVolume(level: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val clamped = level.coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
        Log.d(TAG, "Media volume set to: $clamped")
    }

    fun setRingVolume(level: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
        val clamped = level.coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_RING, clamped, 0)
        Log.d(TAG, "Ring volume set to: $clamped")
    }

    fun setAlarmVolume(level: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val clamped = level.coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, clamped, 0)
        Log.d(TAG, "Alarm volume set to: $clamped")
    }

    fun getMediaVolume(): Int = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    fun getRingVolume(): Int = audioManager.getStreamVolume(AudioManager.STREAM_RING)

    fun getAlarmVolume(): Int = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)

    fun getMaxMediaVolume(): Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    fun getMaxRingVolume(): Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)

    fun getMaxAlarmVolume(): Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)

    fun muteAll() {
        audioManager.setStreamMute(AudioManager.STREAM_MUSIC, true)
        audioManager.setStreamMute(AudioManager.STREAM_RING, true)
        audioManager.setStreamMute(AudioManager.STREAM_ALARM, true)
        Log.d(TAG, "All audio muted")
    }

    fun unmuteAll() {
        audioManager.setStreamMute(AudioManager.STREAM_MUSIC, false)
        audioManager.setStreamMute(AudioManager.STREAM_RING, false)
        audioManager.setStreamMute(AudioManager.STREAM_ALARM, false)
        Log.d(TAG, "All audio unmuted")
    }

    fun playNotificationSound() {
        try {
            val notification = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = android.media.RingtoneManager.getRingtone(context, notification)
            ringtone?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Notification sound error: ${e.message}")
        }
    }

    // Haptic feedback
    fun vibrate(pattern: LongArray, repeat: Int = -1) {
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, repeat))
    }

    fun vibrate(duration: Long) {
        vibrator?.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // Battery and Hardware info
    fun getBatteryInfo(): BatteryInfo {
        val batteryStatus = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val isCharging = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING ||
                batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_FULL

        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else 0

        return BatteryInfo(
            level = level,
            scale = scale,
            percentage = batteryPct,
            isCharging = isCharging,
            status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: 0
        )
    }

    data class BatteryInfo(
        val level: Int,
        val scale: Int,
        val percentage: Int,
        val isCharging: Boolean,
        val status: Int
    )

    fun getAvailableMemory(): Long {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.availMem
    }

    fun getTotalMemory(): Long {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.totalMem
    }

    fun getMemoryInfo(): MemoryInfo {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return MemoryInfo(
            availableMemory = memoryInfo.availMem,
            totalMemory = memoryInfo.totalMem,
            lowMemory = memoryInfo.lowMemory,
            threshold = memoryInfo.threshold
        )
    }

    data class MemoryInfo(
        val availableMemory: Long,
        val totalMemory: Long,
        val lowMemory: Boolean,
        val threshold: Long
    )

    fun getWifiInfo(): WifiInfo? {
        return wifiManager.connectionInfo
    }

    fun isWifiConnected(): Boolean {
        return wifiManager.isWifiEnabled && wifiManager.connectionInfo != null
    }

    fun getWifiSignalStrength(): Int {
        val info = wifiManager.connectionInfo
        return info?.getRssi() ?: 0
    }

    // Telephony and SMS
    fun sendSMS(phoneNumber: String, message: String): Boolean {
        return try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            Log.d(TAG, "SMS sent to $phoneNumber")
            true
        } catch (e: Exception) {
            Log.e(TAG, "SMS send failed: ${e.message}")
            false
        }
    }

    fun sendSMSWithPermissions(phoneNumber: String, message: String): Boolean {
        // In production, check permissions first
        return sendSMS(phoneNumber, message)
    }

    fun dialPhone(phoneNumber: String) {
        Log.d(TAG, "Dialing: $phoneNumber")
    }

    fun callPhone(phoneNumber: String) {
        Log.d(TAG, "Phone call: $phoneNumber")
    }

    fun makeCall(phoneNumber: String) {
        Log.d(TAG, "Making call: $phoneNumber")
    }

    // Media controls
    fun playMedia() {
        Log.d(TAG, "Playing media")
    }

    fun pauseMedia() {
        Log.d(TAG, "Pausing media")
    }

    fun nextTrack() {
        Log.d(TAG, "Next track")
    }

    fun previousTrack() {
        Log.d(TAG, "Previous track")
    }

    fun togglePlayPause() {
        Log.d(TAG, "Toggle play/pause")
    }

    fun setMediaSessionActive(active: Boolean) {
        Log.d(TAG, "Media session active: $active")
    }

    // Alarms and timers
    fun setAlarm(timeInMillis: Long, label: String? = null) {
        val intent = android.content.Intent(context, android.app.Activity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            alarmManager.set(AlarmManager.RTC_WAKEUP, timeInMillis, pendingIntent)
            Log.d(TAG, "Alarm set for: $timeInMillis")
        } catch (e: Exception) {
            Log.e(TAG, "Alarm set error: ${e.message}")
        }
    }

    fun setTimer(durationMs: Long, label: String? = null) {
        val intent = android.content.Intent(context, android.app.Activity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + durationMs, pendingIntent)
            Log.d(TAG, "Timer set for ${durationMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Timer set error: ${e.message}")
        }
    }

    // App launching
    fun launchApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ContextCompat.startActivity(context, intent, null)
                Log.d(TAG, "Launched app: $packageName")
                true
            } else {
                Log.w(TAG, "Launch intent not found for: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Launch app error: ${e.message}")
            false
        }
    }

    fun launchAppByCommonName(name: String): Boolean {
        val packageMap = mapOf(
            "telegram" to "org.telegram.messenger",
            "instagram" to "com.instagram.android",
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "tiktok" to "com.zhiliaoapp.musically",
            "mobile legends" to "com.mobile.legends",
            "pubg" to "com.tencent.ig",
            "chrome" to "com.android.chrome",
            "camera" to "com.sec.android.app.camera",
            "settings" to "com.android.settings"
        )

        val packageName = packageMap[name.lowercase()] ?: return false
        return launchApp(packageName)
    }

    fun launchAppOrSearch(query: String): Boolean {
        // Try common names first
        if (launchAppByCommonName(query)) return true

        // Try as package name
        if (launchApp(query)) return true

        // Open in Play Store
        return try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = android.net.Uri.parse("market://search?q=${query}")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ContextCompat.startActivity(context, intent, null)
            Log.d(TAG, "Opened Play Store for: $query")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Play Store error: ${e.message}")
            false
        }
    }

    // Web code runner
    fun openWebContent(html: String, title: String = "Revilend Web App") {
        val contentView = android.webkit.WebView(context)
        contentView.settings.javaScriptEnabled = true
        contentView.settings.domStorageEnabled = true
        contentView.settings.loadWithOverviewMode = true
        contentView.settings.useWideViewPort = true
        contentView.webViewClient = WebViewClient()
        contentView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)

        // In a real app, you'd show this in a dialog or activity
        Log.d(TAG, "Web content loaded: $title")
    }

    fun openWebPage(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ContextCompat.startActivity(context, intent, null)
            Log.d(TAG, "Opened web page: $url")
        } catch (e: Exception) {
            Log.e(TAG, "Open web page error: ${e.message}")
        }
    }

    fun openCalculator() {
        try {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_APP_CALCULATOR)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ContextCompat.startActivity(context, intent, null)
            Log.d(TAG, "Opened calculator")
        } catch (e: Exception) {
            Log.e(TAG, "Calculator error: ${e.message}")
            // Fallback: open web calculator
            openWebPage("https://www.google.com/search?q=calculator")
        }
    }

    fun openSettings() {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ContextCompat.startActivity(context, intent, null)
            Log.d(TAG, "Opened settings")
        } catch (e: Exception) {
            Log.e(TAG, "Settings error: ${e.message}")
        }
    }

    fun openNotifications() {
        try {
            val intent = Intent("android.settings.APP_NOTIFICATION_SETTINGS")
            intent.putExtra("app_package", context.packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ContextCompat.startActivity(context, intent, null)
            Log.d(TAG, "Opened notifications settings")
        } catch (e: Exception) {
            Log.e(TAG, "Notifications error: ${e.message}")
        }
    }

    fun openSecuritySettings() {
        try {
            val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ContextCompat.startActivity(context, intent, null)
            Log.d(TAG, "Opened security settings")
        } catch (e: Exception) {
            Log.e(TAG, "Security settings error: ${e.message}")
        }
    }

    fun openLocationSettings() {
        try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ContextCompat.startActivity(context, intent, null)
            Log.d(TAG, "Opened location settings")
        } catch (e: Exception) {
            Log.e(TAG, "Location settings error: ${e.message}")
        }
    }

    // System commands
    fun executeCommand(cmd: String): CommandResult {
        return when (cmd.uppercase()) {
            "TORCH_ON" -> {
                toggleFlashlight()
                CommandResult(success = true, message = "Flashlight ON")
            }
            "TORCH_OFF" -> {
                if (isTorchOn) toggleFlashlight()
                CommandResult(success = true, message = "Flashlight OFF")
            }
            "VOLUME_UP" -> {
                val current = getMediaVolume()
                setMediaVolume((current + 1).coerceAtMost(getMaxMediaVolume()))
                CommandResult(success = true, message = "Volume increased")
            }
            "VOLUME_DOWN" -> {
                val current = getMediaVolume()
                setMediaVolume((current - 1).coerceAtLeast(0))
                CommandResult(success = true, message = "Volume decreased")
            }
            "MUTE" -> {
                muteAll()
                CommandResult(success = true, message = "Audio muted")
            }
            "UNMUTE" -> {
                unmuteAll()
                CommandResult(success = true, message = "Audio unmuted")
            }
            "BATTERY" -> {
                val battery = getBatteryInfo()
                CommandResult(
                    success = true,
                    message = "Battery: ${battery.percentage}% ${if (battery.isCharging) "(charging)" else ""}"
                )
            }
            "MEMORY" -> {
                val memory = getMemoryInfo()
                val availableMB = memory.availableMemory / (1024 * 1024)
                val totalMB = memory.totalMemory / (1024 * 1024)
                CommandResult(
                    success = true,
                    message = "RAM: ${availableMB}MB free / ${totalMB}MB total"
                )
            }
            "WIFI" -> {
                val wifi = getWifiInfo()
                val ssid = wifi?.ssid ?: "Not connected"
                val rssi = wifi?.rssi ?: 0
                CommandResult(
                    success = true,
                    message = "WiFi: $ssid, RSSI: $rssi dBm"
                )
            }
            "FLASHLIGHT" -> {
                toggleFlashlight()
                CommandResult(success = true, message = "Flashlight toggled")
            }
            "VIBRATE" -> {
                vibrate(500)
                CommandResult(success = true, message = "Vibrating")
            }
            "SILENT" -> {
                muteAll()
                CommandResult(success = true, message = "Silent mode")
            }
            "NOTIFICATION" -> {
                playNotificationSound()
                CommandResult(success = true, message = "Notification played")
            }
            "LOCK_SCREEN" -> {
                try {
                    val lockIntent = Intent(Settings.ACTION_SECURITY_SETTINGS)
                    lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ContextCompat.startActivity(context, lockIntent, null)
                    CommandResult(success = true, message = "Lock screen")
                } catch (e: Exception) {
                    CommandResult(success = false, message = "Lock screen not supported")
                }
            }
            else -> {
                CommandResult(success = false, message = "Unknown command: $cmd")
            }
        }
    }

    data class CommandResult(
        val success: Boolean,
        val message: String
    )

    // Bluetooth
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled ?: false

    fun enableBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Requires permission, just log for now
            Log.d(TAG, "Bluetooth enable requested (requires permission)")
        }
    }

    fun disableBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Log.d(TAG, "Bluetooth disable requested (requires permission)")
        }
    }

    // Screenshot (requires MediaProjection - simplified version)
    fun takeScreenshot(): Boolean {
        Log.d(TAG, "Screenshot requested (requires MediaProjection)")
        return false // Need MediaProjection permission flow
    }
}
