package com.com11h.partner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Chạy nền liên tục để phát hiện đơn mới kể cả khi người dùng rời khỏi màn hình App.
 *
 * Lưu ý: Android không cho polling 8 giây bằng WorkManager (WorkManager tối thiểu
 * khoảng 15 phút). Vì vậy dùng Foreground Service cho chức năng cảnh báo đơn hàng.
 */
class OrderAlertService : Service() {

    companion object {
        private const val SERVICE_CHANNEL_ID = "partner_order_service_v1"
        private const val ALERT_CHANNEL_ID = "partner_new_orders_v2"
        private const val SERVICE_NOTIF_ID = 2100
        private const val ALERT_NOTIF_ID = 2101
        private const val POLL_MS = 8000L
        private const val PREFS = "partner_order_alert"
        private const val LAST_PENDING_IDS = "last_pending_ids"
    }

    private var worker: Thread? = null
    @Volatile private var running = false
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(SERVICE_NOTIF_ID, serviceNotification())
        initTts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            worker = thread(name = "OrderAlertPolling") { pollLoop() }
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
                    val api = Api(BuildConfig.API_BASE_URL, kcn, token)

                    // Lấy trực tiếp danh sách đơn đang chờ. Cách này chắc chắn hơn
                    // chỉ dựa vào pending_count vì có thể xảy ra trường hợp:
                    // 1 đơn cũ được nhận + 1 đơn mới xuất hiện => tổng số đơn vẫn = 1.
                    val j = api.call("partner_pending_pickups")
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
                        .orEmpty()
                        .split(",")
                        .filter { it.isNotBlank() }
                        .toSet()

                    // Lần chạy đầu chỉ lấy mốc, không đọc lại các đơn cũ.
                    // Các lần sau: chỉ báo khi xuất hiện pickup_id mới.
                    if (hasBaseline && currentIds.any { it !in previousIds }) {
                        showNewOrderAlert(currentIds.size)
                    }

                    prefs.edit()
                        .putString(LAST_PENDING_IDS, currentIds.sorted().joinToString(","))
                        .apply()
                } catch (_: UnauthorizedException) {
                    // Phiên hết hạn: Activity sẽ xử lý khi người dùng mở lại app.
                } catch (_: Exception) {
                    // Mất mạng/API lỗi: thử lại ở vòng tiếp theo.
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
            if (result == TextToSpeech.SUCCESS) {
                val v = tts?.setLanguage(Locale("vi", "VN"))
                if (v == TextToSpeech.LANG_MISSING_DATA || v == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale("vi"))
                }
                tts?.setSpeechRate(0.95f)
                tts?.setPitch(1.0f)
                ttsReady = true
            }
        }
    }

    private fun speakNewOrder() {
        if (!ttsReady) return
        runCatching {
            tts?.speak(
                "Có đơn hàng mới cần làm",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "partner_new_order_${System.currentTimeMillis()}"
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
            description = "Duy trì kiểm tra đơn hàng mới cho App Partner"
            setSound(null, null)
            enableVibration(false)
        }

        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "🔔 Đơn hàng mới",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Cảnh báo khi có đơn hàng mới cần xác nhận"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }

        nm.createNotificationChannel(serviceChannel)
        nm.createNotificationChannel(alertChannel)
    }

    private fun serviceNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(this, 0, openIntent, piFlags)

        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("SHOP FOOD_KCN")
            .setContentText("Đang kiểm tra đơn hàng mới…")
            .setOngoing(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun showNewOrderAlert(pendingCount: Int) {
        // Giọng nói phải chạy trước; không phụ thuộc quyền POST_NOTIFICATIONS.
        speakNewOrder()

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(this, 0, openIntent, piFlags)

        val text = if (pendingCount > 1) {
            "Có $pendingCount đơn đang chờ xác nhận"
        } else {
            "Có đơn mới cần xác nhận"
        }

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🔔 ĐƠN HÀNG MỚI")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setVibrate(longArrayOf(0, 400, 200, 400))
            .setOnlyAlertOnce(false)
            .build()

        runCatching {
            NotificationManagerCompat.from(this).notify(ALERT_NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
