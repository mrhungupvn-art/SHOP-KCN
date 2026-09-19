package com.com11h.shop

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.work.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Màn hình duy nhất của app tiệm (đối tác):
 *   - Đăng nhập bằng tài khoản đối tác (bảng partner_accounts) — YÊU CẦU đã
 *     ký hợp đồng điện tử trên web (admin/login.php) từ trước, server sẽ từ
 *     chối đăng nhập nếu chưa ký (xem partner_login trong api/index.php).
 *   - Danh sách "Đơn cần xử lý" = các pickup thuộc tiệm mình, đơn cha đã được
 *     xác nhận thanh toán (partner_pickups).
 *   - Mỗi pickup có tối đa 3 nút tuỳ trạng thái:
 *       'pending'   -> "Nhận đơn" (partner_confirm_pickup) hoặc "Từ chối" (partner_reject_pickup)
 *       'confirmed' -> "Món đã xong, sẵn sàng" (partner_ready_pickup)
 *   - KHÔNG hiển thị địa chỉ/SĐT khách — đó là việc của shipper, không phải
 *     của tiệm (tiệm chỉ cần biết món gì, số lượng bao nhiêu để chuẩn bị).
 *   - Việc ĐĂNG MÓN/quản lý thực đơn vẫn làm trên web portal cũ, app này chỉ
 *     lo phần xử lý đơn theo thời gian thực.
 */
class MainActivity: AppCompatActivity() {
    private lateinit var api: Api
    private lateinit var session: SecureSession
    private lateinit var status: TextView
    private lateinit var storeBadge: TextView
    private lateinit var box: LinearLayout
    private lateinit var loginPanel: LinearLayout
    private lateinit var appPanel: LinearLayout
    private lateinit var swipe: SwipeRefreshLayout
    private var kcn = 1

    override fun onCreate(b: Bundle?) {
        super.onCreate(b); setContentView(R.layout.activity_main)
        session = SecureSession(this)
        status = findViewById(R.id.status)
        storeBadge = findViewById(R.id.storeBadge)
        box = findViewById(R.id.pickupsBox)
        loginPanel = findViewById(R.id.loginPanel)
        appPanel = findViewById(R.id.appPanel)
        swipe = findViewById(R.id.swipeRefresh)
        swipe.setOnRefreshListener { sync() }
        findViewById<Button>(R.id.loginBtn).setOnClickListener { login() }
        findViewById<Button>(R.id.logoutBtn).setOnClickListener { logout() }
        findViewById<Button>(R.id.refreshBtn).setOnClickListener { sync() }
        session.token()?.let { t ->
            kcn = session.kcnId() ?: 1
            api = Api(BuildConfig.API_BASE_URL, kcn, t)
            showApp("Đã đăng nhập: ${session.name().orEmpty()}")
            sync()
        }
        scheduleSync()
    }

    private fun showApp(text: String) {
        loginPanel.visibility = LinearLayout.GONE
        appPanel.visibility = LinearLayout.VISIBLE
        status.text = text
        storeBadge.text = "🏬 ${session.name().orEmpty()} — KCN #$kcn"
    }

    private fun login() {
        kcn = findViewById<EditText>(R.id.kcnId).text.toString().toIntOrNull() ?: 0
        val u = findViewById<EditText>(R.id.username).text.toString().trim()
        val p = findViewById<EditText>(R.id.password).text.toString()
        if (kcn <= 0 || u.isBlank() || p.isBlank()) { status.text = "Vui lòng nhập KCN, tài khoản và mật khẩu"; return }
        api = Api(BuildConfig.API_BASE_URL, kcn)
        status.text = "Đang đăng nhập..."
        thread {
            try {
                val j = api.call("partner_login", JSONObject().put("username", u).put("password", p).put("device", "android"))
                val data = j.getJSONObject("data")
                val token = data.getString("token")
                val partner = data.optJSONObject("partner")
                val label = partner?.optString("store_name").takeUnless { it.isNullOrBlank() } ?: u
                session.save(token, kcn, label)
                api.setToken(token)
                runOnUiThread { showApp("Đăng nhập thành công: $label"); sync() }
            } catch (e: Exception) {
                runOnUiThread { status.text = e.message ?: "Đăng nhập thất bại" }
            }
        }
    }

    private fun logout() {
        if (::api.isInitialized) thread { runCatching { api.call("partner_logout", JSONObject()) } }
        session.clear()
        box.removeAllViews()
        loginPanel.visibility = LinearLayout.VISIBLE
        appPanel.visibility = LinearLayout.GONE
        status.text = "Đã đăng xuất"
    }

    private fun sync() {
        if (!::api.isInitialized) { swipe.isRefreshing = false; return }
        status.text = "Đang đồng bộ..."
        thread {
            try {
                val res = api.call("partner_pickups")
                val arr = res.optJSONObject("data")?.optJSONArray("pickups")
                runOnUiThread {
                    swipe.isRefreshing = false
                    box.removeAllViews()
                    if (arr == null || arr.length() == 0) {
                        box.addView(TextView(this).apply { text = "Không có đơn nào cần xử lý."; setPadding(0, 8, 0, 8) })
                    } else {
                        for (i in 0 until arr.length()) addPickup(arr.getJSONObject(i))
                    }
                    status.text = "Đồng bộ lúc ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}"
                }
            } catch (e: UnauthorizedException) {
                runOnUiThread { swipe.isRefreshing = false; logout(); status.text = "Phiên đăng nhập hết hạn" }
            } catch (e: Exception) {
                runOnUiThread { swipe.isRefreshing = false; status.text = e.message ?: "Không thể đồng bộ" }
            }
        }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20, 20, 20, 20)
        setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
        val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        p.topMargin = 16
        layoutParams = p
    }

    private fun addPickup(pk: JSONObject) {
        val pickupId = pk.optInt("pickup_id")
        val status = pk.optString("status")
        val c = card()
        c.addView(TextView(this).apply { text = "Đơn ${pk.optString("order_code", "-")}"; textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        c.addView(TextView(this).apply { text = pk.optString("items", "-"); textSize = 15f })
        val note = pk.optString("note", "")
        if (note.isNotBlank()) c.addView(TextView(this).apply { text = "Ghi chú: $note"; textSize = 13f })
        val deadline = pk.optString("confirmation_deadline", "")
        if (deadline.isNotBlank()) c.addView(TextView(this).apply { text = "Cần xử lý trước: $deadline"; textSize = 12f })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 12, 0, 0) }
        c.addView(row)

        when (status) {
            "pending" -> {
                val accept = Button(this).apply { text = "✅ Nhận đơn"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
                val reject = Button(this).apply { text = "🚫 Từ chối"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
                row.addView(accept); row.addView(reject)
                accept.setOnClickListener {
                    accept.isEnabled = false; reject.isEnabled = false
                    thread {
                        try {
                            api.call("partner_confirm_pickup", JSONObject().put("pickup_id", pickupId))
                            runOnUiThread { toast("Đã nhận đơn"); sync() }
                        } catch (e: Exception) {
                            runOnUiThread { toast(e.message); accept.isEnabled = true; reject.isEnabled = true }
                        }
                    }
                }
                reject.setOnClickListener { showRejectDialog(pickupId) }
            }
            "confirmed" -> {
                val ready = Button(this).apply { text = "🍳 Món đã xong, sẵn sàng"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
                row.addView(ready)
                ready.setOnClickListener {
                    ready.isEnabled = false
                    thread {
                        try {
                            api.call("partner_ready_pickup", JSONObject().put("pickup_id", pickupId))
                            runOnUiThread { toast("Đã báo sẵn sàng cho shipper"); sync() }
                        } catch (e: Exception) {
                            runOnUiThread { toast(e.message); ready.isEnabled = true }
                        }
                    }
                }
            }
        }
        box.addView(c)
    }

    private fun showRejectDialog(pickupId: Int) {
        val input = EditText(this).apply { hint = "Lý do từ chối (vd: hết món)" }
        AlertDialog.Builder(this)
            .setTitle("Từ chối đơn này?")
            .setView(input)
            .setPositiveButton("Từ chối") { _, _ ->
                val reason = input.text.toString().trim()
                if (reason.isBlank()) { toast("Vui lòng nhập lý do"); return@setPositiveButton }
                thread {
                    try {
                        api.call("partner_reject_pickup", JSONObject().put("pickup_id", pickupId).put("reason", reason))
                        runOnUiThread { toast("Đã từ chối đơn"); sync() }
                    } catch (e: Exception) {
                        runOnUiThread { toast(e.message) }
                    }
                }
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun toast(m: String?) { Toast.makeText(this, m ?: "Có lỗi", Toast.LENGTH_SHORT).show() }

    private fun scheduleSync() {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("partner_sync", ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}
