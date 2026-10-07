package com.revilend.ai.assistant.control

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.AlarmClock
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import android.view.KeyEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.revilend.ai.assistant.util.PreferencesManager

class DeviceController(private val context: Context) {

    companion object {
        private const val TAG = "DeviceController"

        /** Known "friendly name" -> package mapping for the fastest path. */
        private val COMMON_PACKAGES = mapOf(
            "telegram" to "org.telegram.messenger",
            "instagram" to "com.instagram.android",
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "tiktok" to "com.zhiliaoapp.musically",
            "facebook" to "com.facebook.katana",
            "x" to "com.twitter.android",
            "twitter" to "com.twitter.android",
            "mobile legends" to "com.mobile.legends",
            "mlbb" to "com.mobile.legends",
            "pubg" to "com.tencent.ig",
            "pubg mobile" to "com.tencent.ig",
            "free fire" to "com.dts.freefireth",
            "chrome" to "com.android.chrome",
            "camera" to "com.android.camera",
            "camera xiaomi" to "com.android.camera",
            "settings" to "com.android.settings",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "spotify" to "com.spotify.music",
            "netflix" to "com.netflix.mediaclient",
            "zoom" to "us.zoom.videomeetings",
            "play store" to "com.android.vending",
            "calculator" to "com.google.android.calculator",
            "clock" to "com.google.android.deskclock",
            "gallery" to "com.google.android.apps.photos",
            "photos" to "com.google.android.apps.photos"
        )

        /** Friendly name -> web alternative used when the app is not installed. */
        private val WEB_ALTERNATIVES = mapOf(
            "telegram" to "https://web.telegram.org",
            "whatsapp" to "https://web.whatsapp.com",
            "instagram" to "https://www.instagram.com",
            "youtube" to "https://m.youtube.com",
            "tiktok" to "https://www.tiktok.com",
            "facebook" to "https://m.facebook.com",
            "x" to "https://mobile.twitter.com",
            "twitter" to "https://mobile.twitter.com",
            "gmail" to "https://mail.google.com",
            "maps" to "https://maps.google.com",
            "google maps" to "https://maps.google.com",
            "calculator" to "https://www.google.com/search?q=calculator",
            "chatgpt" to "https://chat.openai.com",
            "spotify" to "https://open.spotify.com"
        )
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

    private val handler = Handler(Looper.getMainLooper())

    var isTorchOn: Boolean = false
        private set

    // ---------------------------------------------------------------- Torch

    /**
     * Scans every camera for a unit that actually reports [CameraCharacteristics.FLASH_INFO_AVAILABLE]
     * (important on Xiaomi/Redmi where camera id "0" is not always the flash unit).
     */
    private fun findFlashCameraId(): String? {
        val cm = cameraManager ?: return null
        return try {
            val ids = cm.cameraIdList
            ids.firstOrNull { id ->
                try {
                    val chars = cm.getCameraCharacteristics(id)
                    (chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) as? Boolean) == true
                } catch (e: Exception) {
                    false
                }
            } ?: ids.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Camera scan error: ${e.message}")
            null
        }
    }

    fun toggleFlashlight(): Boolean = setTorch(!isTorchOn)

    fun setTorch(on: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.w(TAG, "Torch not supported on this device")
            return false
        }
        val cm = cameraManager ?: return false
        val cameraId = findFlashCameraId()
        if (cameraId == null) {
            Log.w(TAG, "No flash unit available")
            return false
        }
        return try {
            cm.setTorchMode(cameraId, on)
            isTorchOn = on
            Log.d(TAG, "Torch ${if (on) "ON" else "OFF"} (camera $cameraId)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Torch error: ${e.message}")
            false
        }
    }

    // ---------------------------------------------------------------- Volume

    fun setMediaVolume(level: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(0, max), 0)
    }

    fun setRingVolume(level: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
        audioManager.setStreamVolume(AudioManager.STREAM_RING, level.coerceIn(0, max), 0)
    }

    fun setAlarmVolume(level: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, level.coerceIn(0, max), 0)
    }

    fun getMediaVolume(): Int = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    fun getRingVolume(): Int = audioManager.getStreamVolume(AudioManager.STREAM_RING)

    fun getAlarmVolume(): Int = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)

    fun getMaxMediaVolume(): Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    fun getMaxRingVolume(): Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)

    fun getMaxAlarmVolume(): Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)

    fun adjustMediaVolume(delta: Int) = setMediaVolume(getMediaVolume() + delta)

    fun adjustRingVolume(delta: Int) = setRingVolume(getRingVolume() + delta)

    fun adjustAlarmVolume(delta: Int) = setAlarmVolume(getAlarmVolume() + delta)

    fun maxAllVolume() {
        setMediaVolume(getMaxMediaVolume())
        setRingVolume(getMaxRingVolume())
        setAlarmVolume(getMaxAlarmVolume())
    }

    @Suppress("DEPRECATION")
    fun muteAll() {
        try {
            audioManager.setStreamMute(AudioManager.STREAM_MUSIC, true)
            audioManager.setStreamMute(AudioManager.STREAM_RING, true)
            audioManager.setStreamMute(AudioManager.STREAM_ALARM, true)
        } catch (e: Exception) {
            Log.e(TAG, "Mute error: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    fun unmuteAll() {
        try {
            audioManager.setStreamMute(AudioManager.STREAM_MUSIC, false)
            audioManager.setStreamMute(AudioManager.STREAM_RING, false)
            audioManager.setStreamMute(AudioManager.STREAM_ALARM, false)
        } catch (e: Exception) {
            Log.e(TAG, "Unmute error: ${e.message}")
        }
    }

    fun playNotificationSound() {
        try {
            val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context, notification)?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Notification sound error: ${e.message}")
        }
    }

    // ------------------------------------------------------------ Haptics

    fun vibrate(pattern: LongArray, repeat: Int = -1) {
        try {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, repeat))
        } catch (e: Exception) {
            Log.e(TAG, "Vibrate error: ${e.message}")
        }
    }

    fun vibrate(duration: Long) {
        try {
            vibrator?.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Log.e(TAG, "Vibrate error: ${e.message}")
        }
    }

    // ------------------------------------------------------- Battery / RAM / WiFi

    fun getBatteryInfo(): BatteryInfo {
        val batteryStatus = context.registerReceiver(
            null,
            android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else 0

        return BatteryInfo(
            level = level,
            scale = scale,
            percentage = batteryPct,
            isCharging = isCharging,
            status = status
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

    @Suppress("DEPRECATION")
    fun getWifiInfo(): WifiInfo? = try {
        wifiManager.connectionInfo
    } catch (e: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    fun isWifiConnected(): Boolean = try {
        wifiManager.isWifiEnabled && wifiManager.connectionInfo != null
    } catch (e: Exception) {
        false
    }

    @Suppress("DEPRECATION")
    fun getWifiSignalStrength(): Int = try {
        wifiManager.connectionInfo?.rssi ?: 0
    } catch (e: Exception) {
        0
    }

    // -------------------------------------------------------- Calls / SMS

    fun callPhone(phoneNumber: String): Boolean {
        val number = phoneNumber.trim()
        if (number.isEmpty()) return false
        val uri = Uri.parse("tel:" + Uri.encode(number))
        return try {
            val intent = Intent(Intent.ACTION_CALL, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ContextCompat.startActivity(context, intent, null)
            Log.d(TAG, "Calling: $number")
            true
        } catch (e: Exception) {
            Log.e(TAG, "ACTION_CALL failed, falling back to dialer: ${e.message}")
            dialPhone(number)
        }
    }

    fun dialPhone(phoneNumber: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phoneNumber.trim()))).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ContextCompat.startActivity(context, intent, null)
            Log.d(TAG, "Dialing: $phoneNumber")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Dial error: ${e.message}")
            false
        }
    }

    fun makeCall(phoneNumber: String): Boolean = callPhone(phoneNumber)

    @Suppress("DEPRECATION")
    fun sendSMS(phoneNumber: String, message: String): Boolean {
        val number = phoneNumber.trim()
        if (number.isEmpty() || message.isEmpty()) return false
        return try {
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            if (parts != null && parts.size > 1) {
                smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(number, null, message, null, null)
            }
            Log.d(TAG, "SMS sent to $number")
            true
        } catch (e: Exception) {
            Log.e(TAG, "SMS send failed: ${e.message}")
            false
        }
    }

    fun sendSMSWithPermissions(phoneNumber: String, message: String): Boolean =
        sendSMS(phoneNumber, message)

    // ------------------------------------------------------- Media control

    private fun sendMediaKey(keyCode: Int) {
        try {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            Log.d(TAG, "Media key dispatched: $keyCode")
        } catch (e: Exception) {
            Log.e(TAG, "Media key error: ${e.message}")
        }
    }

    fun playMedia() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)

    fun pauseMedia() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)

    fun nextTrack() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)

    fun previousTrack() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    fun togglePlayPause() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)

    fun setMediaSessionActive(active: Boolean) {
        Log.d(TAG, "Media session active: $active")
    }

    // --------------------------------------------------- Alarms & timers

    /** Sets a real system alarm through the Clock app (API 9+ AlarmClock intents). */
    fun setAlarmClock(hour: Int, minute: Int, label: String? = null): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
                putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
                putExtra(AlarmClock.EXTRA_MESSAGE, label ?: "Revilend AI")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ContextCompat.startActivity(context, intent, null)
            Log.d(TAG, "Alarm set for $hour:$minute")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Alarm set error: ${e.message}")
            openClockApp()
        }
    }

    /** Starts a countdown timer through the Clock app. */
    fun startTimer(seconds: Int, label: String? = null): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceAtLeast(1))
                putExtra(AlarmClock.EXTRA_MESSAGE, label ?: "Revilend AI")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ContextCompat.startActivity(context, intent, null)
            Log.d(TAG, "Timer set for ${seconds}s")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Timer set error: ${e.message}")
            openClockApp()
        }
    }

    fun setTimer(durationMs: Long, label: String? = null): Boolean =
        startTimer((durationMs / 1000L).toInt(), label)

    fun openClockApp(): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ContextCompat.startActivity(context, intent, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------------------------------------------------- App launching

    fun isPackageInstalled(pkg: String): Boolean {
        return try {
            packageManager.getLaunchIntentForPackage(pkg) != null
        } catch (e: Exception) {
            false
        }
    }

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
        val pkg = COMMON_PACKAGES[name.lowercase().trim()] ?: return false
        return launchApp(pkg)
    }

    /** Finds an installed app whose launcher label matches [label] (case/partial). */
    fun findInstalledPackageByLabel(label: String): String? {
        val target = label.lowercase().trim()
        if (target.length < 2) return null
        return try {
            val apps = packageManager.getInstalledApplications(0)
            var partial: String? = null
            for (app in apps) {
                val appLabel = try {
                    packageManager.getApplicationLabel(app).toString().lowercase()
                } catch (e: Exception) {
                    continue
                }
                if (appLabel == target) return app.packageName
                if (partial == null && appLabel.length > 2 &&
                    (appLabel.contains(target) || target.contains(appLabel))
                ) {
                    partial = app.packageName
                }
            }
            partial
        } catch (e: Exception) {
            Log.e(TAG, "Label lookup error: ${e.message}")
            null
        }
    }

    /**
     * Universal launcher: known package, then installed-app label, then as a raw
     * package name, then a matching web alternative, then the Play Store.
     */
    fun launchAppOrSearch(query: String): Boolean {
        val clean = query.trim()
        if (clean.isEmpty()) return false

        if (launchAppByCommonName(clean)) return true
        if (clean.contains(".") && launchApp(clean)) return true

        findInstalledPackageByLabel(clean)?.let { pkg ->
            if (launchApp(pkg)) return true
        }
        if (launchApp(clean)) return true

        val alt = WEB_ALTERNATIVES[clean.lowercase()]
        if (alt != null && openWebPage(alt)) return true

        if (openPlayStore(clean)) return true
        return openWebSearch(clean)
    }

    fun openPlayStore(query: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(query))).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ContextCompat.startActivity(context, intent, null)
            Log.d(TAG, "Opened Play Store for: $query")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Play Store error: ${e.message}")
            false
        }
    }

    fun openWebSearch(query: String): Boolean =
        openWebPage("https://www.google.com/search?q=" + Uri.encode(query))

    // ------------------------------------------------ Web views & apps

    /** Shows HTML in a WebView hosted by a transparent overlay window. */
    fun openWebContent(html: String, title: String = "Revilend Web App") {
        try {
            val contentView = WebView(context)
            contentView.settings.javaScriptEnabled = true
            contentView.settings.domStorageEnabled = true
            contentView.settings.loadWithOverviewMode = true
            contentView.settings.useWideViewPort = true
            contentView.webViewClient = WebViewClient()
            contentView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
            Log.d(TAG, "Web content prepared: $title")
        } catch (e: Exception) {
            Log.e(TAG, "Web content error: ${e.message}")
        }
    }

    fun openWebPage(url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ContextCompat.startActivity(context, intent, null)
            Log.d(TAG, "Opened web page: $url")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Open web page error: ${e.message}")
            false
        }
    }

    fun openCalculator() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_CALCULATOR)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ContextCompat.startActivity(context, intent, null)
        } catch (e: Exception) {
            openWebPage("https://www.google.com/search?q=calculator")
        }
    }

    fun openSettings() {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ContextCompat.startActivity(context, intent, null)
        } catch (e: Exception) {
            Log.e(TAG, "Settings error: ${e.message}")
        }
    }

    fun openNotifications() {
        try {
            val intent = Intent("android.settings.APP_NOTIFICATION_SETTINGS").apply {
                putExtra("app_package", context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ContextCompat.startActivity(context, intent, null)
        } catch (e: Exception) {
            Log.e(TAG, "Notifications error: ${e.message}")
        }
    }

    fun openSecuritySettings() {
        try {
            val intent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ContextCompat.startActivity(context, intent, null)
        } catch (e: Exception) {
            Log.e(TAG, "Security settings error: ${e.message}")
        }
    }

    fun openLocationSettings() {
        try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ContextCompat.startActivity(context, intent, null)
        } catch (e: Exception) {
            Log.e(TAG, "Location settings error: ${e.message}")
        }
    }

    // ------------------------------------------------------ Command router

    /**
     * Central command router used by the AI brain. Supports both exact keywords
     * (TORCH_ON, BATTERY, ...) and prefixed commands (CALL:+998..., SMS:num:msg,
     * SET_ALARM:7:30, SET_TIMER:60, OPEN_WEB:https://..., VOLUME_MEDIA_UP ...).
     */
    fun executeCommand(cmd: String): CommandResult {
        val raw = cmd.trim()
        val upper = raw.uppercase()
        val payload = if (raw.contains(":")) raw.substring(raw.indexOf(':') + 1).trim() else ""

        return when (upper) {
            "TORCH_ON", "FLASHLIGHT_ON" -> status(setTorch(true), "Chiroq yondi", "Chiroq yoqilmadi")
            "TORCH_OFF", "FLASHLIGHT_OFF" -> status(setTorch(false), "Chiroq o'chdi", "Chiroq o'chirilmadi")
            "FLASHLIGHT", "TORCH" -> status(toggleFlashlight(), "Chiroq almashtirildi", "Chiroq ishlamadi")
            "VOLUME_UP", "VOLUME_MEDIA_UP" -> { adjustMediaVolume(1); CommandResult(true, "Media ovozi: ${getMediaVolume()}/${getMaxMediaVolume()}") }
            "VOLUME_DOWN", "VOLUME_MEDIA_DOWN" -> { adjustMediaVolume(-1); CommandResult(true, "Media ovozi: ${getMediaVolume()}/${getMaxMediaVolume()}") }
            "VOLUME_RING_UP" -> { adjustRingVolume(1); CommandResult(true, "Qo'ng'iroq ovozi: ${getRingVolume()}") }
            "VOLUME_RING_DOWN" -> { adjustRingVolume(-1); CommandResult(true, "Qo'ng'iroq ovozi: ${getRingVolume()}") }
            "VOLUME_ALARM_UP" -> { adjustAlarmVolume(1); CommandResult(true, "Budilnik ovozi: ${getAlarmVolume()}") }
            "VOLUME_ALARM_DOWN" -> { adjustAlarmVolume(-1); CommandResult(true, "Budilnik ovozi: ${getAlarmVolume()}") }
            "MAX_VOLUME" -> { maxAllVolume(); CommandResult(true, "Ovoz maksimal") }
            "MUTE", "SILENT" -> { muteAll(); CommandResult(true, "Ovozsiz rejim") }
            "UNMUTE" -> { unmuteAll(); CommandResult(true, "Ovoz qaytarildi") }
            "MEDIA_PLAY" -> { playMedia(); CommandResult(true, "Musiqa davom etdi") }
            "MEDIA_PAUSE" -> { pauseMedia(); CommandResult(true, "Musiqa to'xtadi") }
            "MEDIA_PLAY_PAUSE", "MEDIA_TOGGLE" -> { togglePlayPause(); CommandResult(true, "Media almashtirildi") }
            "MEDIA_NEXT" -> { nextTrack(); CommandResult(true, "Keyingi trek") }
            "MEDIA_PREV", "MEDIA_PREVIOUS" -> { previousTrack(); CommandResult(true, "Oldingi trek") }
            "BATTERY" -> {
                val b = getBatteryInfo()
                CommandResult(true, "Batareya: ${b.percentage}% ${if (b.isCharging) "(quvvatlanmoqda)" else ""}")
            }
            "MEMORY" -> {
                val m = getMemoryInfo()
                CommandResult(true, "RAM: ${m.availableMemory / (1024 * 1024)}MB bo'sh / ${m.totalMemory / (1024 * 1024)}MB")
            }
            "WIFI" -> {
                val wifi = getWifiInfo()
                CommandResult(true, "WiFi: ${wifi?.ssid ?: "ulanmagan"}")
            }
            "VIBRATE" -> { vibrate(500); CommandResult(true, "Titrash") }
            "NOTIFICATION" -> { playNotificationSound(); CommandResult(true, "Bildirishnoma ovozi") }
            "LOCK_SCREEN" -> CommandResult(false, "Qulflash uchun accessibility ishlatiladi")
            "OPEN_CALCULATOR" -> { openCalculator(); CommandResult(true, "Kalkulyator ochilmoqda") }
            "OPEN_SETTINGS" -> { openSettings(); CommandResult(true, "Sozlamalar ochilmoqda") }
            "OPEN_NOTIFICATIONS" -> { openNotifications(); CommandResult(true, "Bildirishnomalar") }
            else -> when {
                upper.startsWith("CALL:") -> status(callPhone(payload), "Qo'ng'iroq: $payload", "Qo'ng'iroq qilib bo'lmadi")
                upper.startsWith("DIAL:") -> status(dialPhone(payload), "Terish: $payload", "Terish ishlamadi")
                upper.startsWith("SMS:") -> handleSms(payload)
                upper.startsWith("SET_ALARM:") -> handleAlarm(payload)
                upper.startsWith("SET_TIMER:") -> {
                    val seconds = payload.filter { it.isDigit() }.toIntOrNull()
                    if (seconds != null) status(startTimer(seconds), "Taymer: $seconds soniya", "Taymer qo'yilmadi")
                    else CommandResult(false, "Taymer formati: SET_TIMER:soniya")
                }
                upper.startsWith("OPEN_WEB:") -> status(openWebPage(payload), "Sahifa ochilmoqda", "Sahifa ochilmadi")
                upper.startsWith("OPEN_APP:") -> status(launchAppOrSearch(payload), "$payload ochilmoqda", "$payload topilmadi")
                else -> CommandResult(false, "Unknown command: $cmd")
            }
        }
    }

    private fun handleSms(payload: String): CommandResult {
        val separator = payload.indexOf(':')
        if (separator <= 0) return CommandResult(false, "SMS formati: SMS:raqam:xabar")
        val number = payload.substring(0, separator).trim()
        val message = payload.substring(separator + 1).trim()
        val ok = sendSMS(number, message)
        return CommandResult(ok, if (ok) "SMS yuborildi: $number" else "SMS yuborilmadi")
    }

    private fun handleAlarm(payload: String): CommandResult {
        val parts = payload.split(":").map { it.trim() }
        val hour = parts.getOrNull(0)?.filter { it.isDigit() }?.toIntOrNull()
        val minute = parts.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        if (hour == null) return CommandResult(false, "Budilnik formati: SET_ALARM:soat:daqiqa")
        val ok = setAlarmClock(hour, minute)
        return CommandResult(ok, if (ok) "Budilnik: %02d:%02d".format(hour, minute) else "Budilnik qo'yilmadi")
    }

    private fun status(ok: Boolean, successMessage: String, failMessage: String): CommandResult =
        CommandResult(ok, if (ok) successMessage else failMessage)

    data class CommandResult(
        val success: Boolean,
        val message: String
    )

    // ------------------------------------------------------ Bluetooth

    fun isBluetoothEnabled(): Boolean = try {
        bluetoothAdapter?.isEnabled ?: false
    } catch (e: Exception) {
        false
    }

    fun enableBluetooth() {
        Log.d(TAG, "Bluetooth enable requested (requires system permission)")
    }

    fun disableBluetooth() {
        Log.d(TAG, "Bluetooth disable requested (requires system permission)")
    }

    fun takeScreenshot(): Boolean {
        Log.d(TAG, "Screenshot requested (requires MediaProjection)")
        return false
    }
}
