package com.com11h.partner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Cảnh báo đơn mới cho Shop.
 *
 * Chỉ báo khi pickup thuộc shop đã được thanh toán (payment_status=paid).
 * Mỗi đơn mới: rung + đọc 3 lần, bắt đầu mỗi lần cách nhau 2 giây.
 */
class OrderAlertService : Service() {

    companion object {
        private const val SERVICE_CHANNEL_ID = "partner_order_service_v1"
        private const val ALERT_CHANNEL_ID = "partner_new_orders_v3"
        private const val SERVICE_NOTIF_ID = 2100
        private const val POLL_MS = 8000L
        private const val PREFS = "partner_order_alert"
        private const val LAST_PENDING_IDS = "last_pending_ids"
        private const val MESSAGE = "Có đơn hàng mới, cần làm nhanh!"
        private const val REPEAT_COUNT = 3
        private const val REPEAT_INTERVAL_MS = 2000L
    }

    private var worker: Thread? = null
    @Volatile private var running = false
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private val handler = Handler(Looper.getMainLooper())
    private var alertGeneration = 0L

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(SERVICE_NOTIF_ID, serviceNotification())
        initTts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            worker = thread(name = "PartnerOrderAlertPolling") { pollLoop() }
        }
        return START_STICKY
    }

    private fun pollLoop() {
        val session = SecureSession(this)
        while (running) {
            val token = session.token()
            val kcn = session.kcnId() ?: 0
            if (!token.isNullOrBlank() && kcn > 0) {
                try {
                    val j = Api(BuildConfig.API_BASE_URL, kcn, token).call("partner_pending_pickups")
                    val arr = j.optJSONObject("data")?.optJSONArray("pickups")
                        ?: org.json.JSONArray()

                    val currentIds = mutableSetOf<String>()
                    for (i in 0 until arr.length()) {
                        val id = arr.optJSONObject(i)?.optInt("pickup_id", 0) ?: 0
                        if (id > 0) currentIds.add(id.toString())
                    }

                    val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                    val hasBaseline = prefs.contains(LAST_PENDING_IDS)
                    val previousIds = prefs.getString(LAST_PENDING_IDS, "")
                        .orEmpty().split(",").filter { it.isNotBlank() }.toSet()

                    if (hasBaseline) {
                        val newIds = currentIds.filter { it !in previousIds }
                        if (newIds.isNotEmpty()) alertNewOrder()
                    }

                    prefs.edit()
                        .putString(LAST_PENDING_IDS, currentIds.sorted().joinToString(","))
                        .apply()
                } catch (_: UnauthorizedException) {
                    // Activity xử lý phiên hết hạn khi người dùng mở app.
                } catch (_: Exception) {
                    // Giữ baseline; vòng sau thử lại.
                }
            }

            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun initTts() {
        tts = TextToSpeech(applicationContext) { result ->
            if (result != TextToSpeech.SUCCESS) return@TextToSpeech
            val engine = tts ?: return@TextToSpeech
            val vi = Locale("vi", "VN")
            runCatching {
                val maleVoice = engine.voices.orEmpty().firstOrNull {
                    it.locale.language.equals("vi", true) &&
                        it.name.lowercase(Locale.ROOT).contains("male")
                }
                if (maleVoice != null) engine.voice = maleVoice else engine.language = vi
                engine.setSpeechRate(0.92f)
                engine.setPitch(0.72f)
            }
            ttsReady = true
        }
    }

    private fun alertNewOrder() {
        alertGeneration++
        val generation = alertGeneration

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(this, 0, openIntent, piFlags)

        if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
        ) {
            val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("🔔 ĐƠN HÀNG MỚI")
                .setContentText(MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .build()

            runCatching {
                NotificationManagerCompat.from(this)
                    .notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
            }
        }

        repeat(REPEAT_COUNT) { index ->
            handler.postDelayed({
                if (running && generation == alertGeneration) {
                    vibrateOnce()
                    handler.postDelayed({ speakOnce(generation) }, 220L)
                }
            }, index * REPEAT_INTERVAL_MS)
        }
    }

    private fun vibrateOnce() {
        runCatching {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(500L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(500L)
            }
        }
    }

    private fun speakOnce(generation: Long) {
        if (!ttsReady || generation != alertGeneration) return
        runCatching {
            tts?.speak(
                MESSAGE,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "partner_new_order_${generation}_${System.currentTimeMillis()}"
            )
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return

        val serviceChannel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            "Dịch vụ kiểm tra đơn hàng",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Duy trì kiểm tra đơn hàng mới cho App Shop"
            setSound(null, null)
            enableVibration(false)
        }

        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "🔔 Đơn hàng mới",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Cảnh báo đơn hàng mới đã thanh toán"
            setSound(null, null)
            enableVibration(false)
        }

        nm.createNotificationChannel(serviceChannel)
        nm.createNotificationChannel(alertChannel)
    }

    private fun serviceNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(this, 0, intent, flags)

        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("SHOP FOOD_KCN")
            .setContentText("Đang kiểm tra đơn hàng mới…")
            .setOngoing(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker = null
        handler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
