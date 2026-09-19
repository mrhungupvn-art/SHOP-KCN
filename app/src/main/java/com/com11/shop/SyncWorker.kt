package com.com11h.shop

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.app.NotificationChannel
import android.app.NotificationManager

class SyncWorker(appContext: Context, params: WorkerParameters): CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val session = SecureSession(applicationContext)
        val token = session.token() ?: return Result.success()
        val kcn = session.kcnId() ?: return Result.success()
        return try {
            val api = Api(BuildConfig.API_BASE_URL, kcn, token)
            val json = api.call("partner_pickups")
            val pickups = json.optJSONObject("data")?.optJSONArray("pickups")
            val count = pickups?.length() ?: 0
            val last = applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE).getInt("count", -1)
            if (last >= 0 && count > last) notifyNewOrders(count - last)
            applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE).edit().putInt("count", count).apply()
            Result.success()
        } catch (_: UnauthorizedException) { Result.success() }
        catch (_: Exception) { Result.retry() }
    }
    private fun notifyNewOrders(delta: Int) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("orders", "Đơn mới", NotificationManager.IMPORTANCE_HIGH))
        val notification = NotificationCompat.Builder(applicationContext, "orders")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Có đơn mới cần xử lý")
            .setContentText("Có $delta đơn mới cần tiệm xác nhận").setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()
        NotificationManagerCompat.from(applicationContext).notify(1101, notification)
    }
}
