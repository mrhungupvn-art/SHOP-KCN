package com.com11h.partner

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.work.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var api: Api
    private lateinit var session: SecureSession
    private lateinit var loginPanel: LinearLayout
    private lateinit var appPanel: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var loginStatus: TextView
    private lateinit var storeBadge: TextView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var spinner: Spinner
    private lateinit var contractPanel: LinearLayout
    private lateinit var contractBody: TextView
    private lateinit var contractStatus: TextView
    private lateinit var contractName: EditText
    private lateinit var contractAgree: CheckBox
    private var kcnList: List<KcnItem> = emptyList()
    private var kcn = 0
    private var selectedImageUri: Uri? = null
    private var imagePickerMode: String = ""
    private var pendingCreateName: String = ""
    private var pendingCreateStock: Int = 0

    private val pingHandler = Handler(Looper.getMainLooper())
    private var pingRunnable: Runnable? = null
    private var lastPingSignature: String? = null
    // Số đơn đang "pending" (chờ Partner xác nhận) ở lần ping gần nhất — dùng
    // để phát hiện có ĐƠN MỚI thật sự (pending_count TĂNG), tách biệt với
    // lastPingSignature ở trên (đổi cho MỌI thay đổi pickup, kể cả do chính
    // Partner bấm Nhận đơn/Báo xong) chỉ dùng để tự làm mới danh sách.
    private var lastPendingCount: Int? = null
    private val PING_INTERVAL_MS = 8000L
    private val PICK_IMAGE = 9001
    private val NEW_ORDER_CHANNEL_ID = "partner_new_orders"
    private val NEW_ORDER_NOTIF_ID = 2001

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        session = SecureSession(this)
        loginPanel = findViewById(R.id.loginPanel)
        appPanel = findViewById(R.id.appPanel)
        content = findViewById(R.id.contentBox)
        status = findViewById(R.id.status)
        loginStatus = findViewById(R.id.loginStatus)
        storeBadge = findViewById(R.id.storeBadge)
        swipe = findViewById(R.id.swipeRefresh)
        spinner = findViewById(R.id.kcnSpinner)
        contractPanel = findViewById(R.id.contractPanel)
        contractBody = findViewById(R.id.contractBody)
        contractStatus = findViewById(R.id.contractStatus)
        contractName = findViewById(R.id.contractName)
        contractAgree = findViewById(R.id.contractAgree)
        findViewById<Button>(R.id.signContractBtn).setOnClickListener { signContract() }

        loadKcnList()
        findViewById<Button>(R.id.loginBtn).setOnClickListener { login() }
        findViewById<Button>(R.id.logoutBtn).setOnClickListener { logout() }
        findViewById<Button>(R.id.refreshBtn).setOnClickListener { syncPending() }
        findViewById<Button>(R.id.newTab).setOnClickListener { loadPickups("partner_pending_pickups") }
        findViewById<Button>(R.id.prepTab).setOnClickListener { loadPickups("partner_pickups", "preparing") }
        findViewById<Button>(R.id.historyTab).setOnClickListener { loadPickups("partner_pickups", "ready,rejected") }
        findViewById<Button>(R.id.foodsTab).setOnClickListener { loadFoods() }
        findViewById<Button>(R.id.ledgerTab).setOnClickListener { loadLedger() }
        findViewById<Button>(R.id.accountTab).setOnClickListener { loadAccount() }
        swipe.setOnRefreshListener { syncPending() }

        session.token()?.let { t ->
            kcn = session.kcnId() ?: 0
            if (kcn > 0) {
                api = Api(BuildConfig.API_BASE_URL, kcn, t)
                loadContractState()

            }
        }
        createNewOrderNotificationChannel()
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf("android.permission.POST_NOTIFICATIONS"), 12)
        }
        scheduleSync()
    }

    override fun onResume() { super.onResume(); startFastPolling() }
    override fun onPause() { super.onPause(); stopFastPolling() }

    private fun startFastPolling() {
        if (!::api.isInitialized) return
        stopFastPolling()
        val r = object : Runnable {
            override fun run() { pingOnce(); pingHandler.postDelayed(this, PING_INTERVAL_MS) }
        }
        pingRunnable = r
        pingHandler.postDelayed(r, PING_INTERVAL_MS)
    }
    private fun stopFastPolling() { pingRunnable?.let { pingHandler.removeCallbacks(it) }; pingRunnable = null }

    private fun pingOnce() {
        if (!::api.isInitialized) return
        thread {
            try {
                val j = api.call("partner_ping")
                val data = j.optJSONObject("data") ?: JSONObject()
                val sig = data.toString()
                val changed = lastPingSignature != null && sig != lastPingSignature
                lastPingSignature = sig

                // 🔔 ĐƠN HÀNG MỚI: chỉ rung chuông + phát âm thanh khi pending_count
                // THẬT SỰ TĂNG so với lần ping trước (đơn mới vừa được Admin xác nhận
                // và đưa vào chờ Partner). Không dùng "changed" ở trên vì nó đổi cho
                // mọi thao tác (kể cả chính Partner bấm Nhận đơn/Báo xong), sẽ báo nhầm.
                val pendingCount = data.optInt("pending_count", -1)
                if (pendingCount >= 0) {
                    val prev = lastPendingCount
                    if (prev != null && pendingCount > prev) {
                        runOnUiThread { notifyNewOrder(pendingCount) }
                    }
                    lastPendingCount = pendingCount
                }

                if (changed) runOnUiThread { syncPending() }
            } catch (_: UnauthorizedException) { runOnUiThread { logout() } }
            catch (_: Exception) { }
        }
    }

    /** Tạo kênh thông báo "Đơn hàng mới" (âm thanh + rung) — cần gọi trước khi notify trên Android 8+. */
    private fun createNewOrderNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val pattern = longArrayOf(0, 400, 200, 400)
        val channel = NotificationChannel(NEW_ORDER_CHANNEL_ID, "Đơn hàng mới", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Thông báo khi có đơn hàng mới cần xác nhận"
            enableVibration(true)
            vibrationPattern = pattern
            enableLights(true)
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), audioAttrs)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** 🔔 ĐƠN HÀNG MỚI — hiện thông báo hệ thống + rung + phát âm thanh (kể cả khi app đang mở). */
    private fun notifyNewOrder(pendingCount: Int) {
        vibrateNewOrder()
        playNewOrderSound()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val contentIntent = PendingIntent.getActivity(this, 0, openIntent, flags)
        val notif = NotificationCompat.Builder(this, NEW_ORDER_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🔔 ĐƠN HÀNG MỚI")
            .setContentText(if (pendingCount > 1) "Có $pendingCount đơn mới cần xác nhận" else "Có đơn mới cần xác nhận")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setVibrate(longArrayOf(0, 400, 200, 400))
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(NEW_ORDER_NOTIF_ID, notif) }
    }

    private fun vibrateNewOrder() {
        val pattern = longArrayOf(0, 400, 200, 400)
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val vm = getSystemService(VibratorManager::class.java)
                vm?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= 26) v?.vibrate(VibrationEffect.createWaveform(pattern, -1))
                else @Suppress("DEPRECATION") v?.vibrate(pattern, -1)
            }
        }
    }

    private fun playNewOrderSound() {
        runCatching {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(this, uri)?.play()
        }
    }

    private fun loadKcnList() {
        thread {
            runCatching { Api.fetchKcnList(BuildConfig.API_BASE_URL) }
                .onSuccess { list -> runOnUiThread {
                    kcnList = list
                    spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, list).also {
                        it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    }
                }}
                .onFailure { e -> runOnUiThread { loginStatus.text = "Không tải được KCN: ${e.message}" } }
        }
    }

    private fun showApp() {
        loginPanel.visibility = LinearLayout.GONE
        contractPanel.visibility = LinearLayout.GONE
        appPanel.visibility = LinearLayout.VISIBLE
        storeBadge.text = "🏪 ${session.name().orEmpty()}  •  Store #${session.storeId()}  •  ${session.category()}"
        status.text = "Sẵn sàng"
        startFastPolling()
    }

    private fun login() {
        val sel = spinner.selectedItem as? KcnItem
        kcn = sel?.id ?: 0
        val u = findViewById<EditText>(R.id.username).text.toString().trim()
        val p = findViewById<EditText>(R.id.password).text.toString()
        if (kcn <= 0 || u.isBlank() || p.isBlank()) { loginStatus.text = "Vui lòng chọn KCN và nhập tài khoản/mật khẩu"; return }
        api = Api(BuildConfig.API_BASE_URL, kcn)
        loginStatus.text = "Đang đăng nhập..."
        thread {
            try {
                val d = api.call("partner_login", JSONObject().put("username", u).put("password", p).put("device", "android")).getJSONObject("data")
                val t = d.getString("token")
                val x = d.getJSONObject("partner")
                session.save(t, kcn, x.optString("store_name", u), x.optInt("store_id"), x.optString("category", ""))
                api.setToken(t)
                runOnUiThread { if (d.optBoolean("contract_required", false)) loadContractState() else { showApp(); syncPending() } }
            } catch (e: Exception) { runOnUiThread { loginStatus.text = e.message ?: "Đăng nhập thất bại" } }
        }
    }

    private fun loadContractState() {
        if (!::api.isInitialized) return
        thread {
            try {
                val d = api.call("partner_contract").optJSONObject("data") ?: JSONObject()
                val c = d.optJSONObject("contract")
                runOnUiThread {
                    if (c == null) {
                        showContractPanel("Tài khoản chưa có hợp đồng hợp tác. Vui lòng liên hệ Admin.", "")
                    } else if (c.optString("status") == "signed") {
                        showApp(); syncPending()
                    } else {
                        showContractPanel("Bạn cần đọc và ký hợp đồng trước khi sử dụng App Partner.", c.optString("contract_body"))
                    }
                }
            } catch (e: Exception) { runOnUiThread { showContractPanel(e.message ?: "Không tải được hợp đồng.", "") } }
        }
    }

    private fun showContractPanel(message: String, body: String) {
        loginPanel.visibility = LinearLayout.GONE
        appPanel.visibility = LinearLayout.GONE
        contractPanel.visibility = LinearLayout.VISIBLE
        contractStatus.text = message
        contractBody.text = if (body.isBlank()) "Chưa có nội dung hợp đồng." else body
        contractName.text.clear(); contractAgree.isChecked = false
        findViewById<Button>(R.id.signContractBtn).isEnabled = body.isNotBlank()
    }

    private fun signContract() {
        val name = contractName.text.toString().trim()
        if (name.isBlank()) { contractName.error = "Nhập họ tên"; return }
        if (!contractAgree.isChecked) { toast("Bạn phải xác nhận đồng ý với hợp đồng"); return }
        thread {
            try {
                api.call("partner_sign_contract", JSONObject().put("signed_name", name))
                runOnUiThread { toast("Đã ký hợp đồng điện tử"); showApp(); syncPending() }
            } catch (e: Exception) { runOnUiThread { toast(e.message) } }
        }
    }

    private fun logout() {
        stopFastPolling()
        if (::api.isInitialized) thread { runCatching { api.call("partner_logout", JSONObject()) } }
        session.clear(); content.removeAllViews()
        lastPingSignature = null; lastPendingCount = null
        loginPanel.visibility = LinearLayout.VISIBLE; appPanel.visibility = LinearLayout.GONE
        loginStatus.text = "Đã đăng xuất"
    }

    private fun syncPending() {
        if (!::api.isInitialized) return
        status.text = "Đang đồng bộ..."
        thread {
            try {
                val j = api.call("partner_pending_pickups")
                val a = j.optJSONObject("data")?.optJSONArray("pickups") ?: JSONArray()
                runOnUiThread {
                    swipe.isRefreshing = false
                    status.text = "Có ${a.length()} đơn mới • ${SimpleDateFormat("HH:mm:ss").format(Date())}"
                    renderPickups(a, true)
                }
            } catch (e: UnauthorizedException) { runOnUiThread { swipe.isRefreshing = false; logout() } }
            catch (e: Exception) { runOnUiThread { swipe.isRefreshing = false; status.text = e.message ?: "Không đồng bộ được" } }
        }
    }

    private fun loadPickups(action: String, filter: String = "") {
        if (!::api.isInitialized) return
        thread {
            try {
                val a = api.call(action).optJSONObject("data")?.optJSONArray("pickups") ?: JSONArray()
                runOnUiThread {
                    val out = JSONArray()
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i); val st = o.optString("pickup_status")
                        if (filter.isBlank() || filter.split(',').contains(st)) out.put(o)
                    }
                    renderPickups(out, false)
                }
            } catch (e: Exception) { runOnUiThread { toast(e.message) } }
        }
    }

    private fun renderPickups(arr: JSONArray, onlyPending: Boolean) {
        content.removeAllViews()
        content.addView(title(if (onlyPending) "📥 Đơn mới — cần xác nhận trong 15 phút" else "📦 Danh sách pickup"))
        if (arr.length() == 0) { content.addView(hint("Không có dữ liệu.")); return }
        for (i in 0 until arr.length()) addPickup(arr.getJSONObject(i))
    }

    private fun addPickup(o: JSONObject) {
        val c = card(); val id = o.optInt("pickup_id")
        c.addView(text("Đơn ${o.optString("code")} • Pickup #$id", 20, true))
        c.addView(text("Trạng thái: ${o.optString("pickup_status")}", 15, false))
        c.addView(text("Khách: ${o.optString("customer")} • ${o.optString("phone")}", 14, false))
        c.addView(text("Giao: ${o.optString("address")}", 14, false))
        c.addView(text("Thanh toán: ${if (o.optString("payment_status") == "paid") "Đã thanh toán online" else "COD"}", 14, false))
        val items = o.optJSONArray("items") ?: JSONArray()
        for (i in 0 until items.length()) { val x = items.getJSONObject(i); c.addView(text("• ${x.optString("name")} ×${x.optInt("qty")}", 14, false)) }
        val st = o.optString("pickup_status")
        if (st == "pending") {
            val row = LinearLayout(this); val ok = Button(this).apply { text = "✅ Nhận đơn" }; val no = Button(this).apply { text = "❌ Từ chối" }
            row.addView(ok, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(no, LinearLayout.LayoutParams(0, -2, 1f)); c.addView(row)
            ok.setOnClickListener { action(id, "partner_confirm_pickup") { loadPickups("partner_pending_pickups") } }
            no.setOnClickListener { rejectDialog(id) }
        } else if (st == "confirmed" || st == "preparing") {
            val ready = Button(this).apply { text = "🍱 Món đã xong — báo Shipper" }; c.addView(ready)
            ready.setOnClickListener { action(id, "partner_mark_ready") { loadPickups("partner_pickups", "preparing") } }
        }
        content.addView(c)
    }

    private fun action(id: Int, name: String, done: () -> Unit) {
        thread { try { api.call(name, JSONObject().put("pickup_id", id)); runOnUiThread { toast("Thành công"); done() } }
            catch (e: Exception) { runOnUiThread { toast(e.message) } } }
    }

    private fun rejectDialog(id: Int) {
        val input = EditText(this).apply { hint = "Lý do bắt buộc (hết món/quá tải/khác)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        AlertDialog.Builder(this).setTitle("Từ chối pickup").setView(input).setNegativeButton("Huỷ", null)
            .setPositiveButton("Từ chối") { _, _ -> val reason = input.text.toString().trim(); if (reason.isBlank()) toast("Lý do bắt buộc") else actionReject(id, reason) }.show()
    }
    private fun actionReject(id: Int, reason: String) {
        thread { try { api.call("partner_reject_pickup", JSONObject().put("pickup_id", id).put("reason", reason)); runOnUiThread { toast("Đã từ chối pickup"); loadPickups("partner_pending_pickups") } }
            catch (e: Exception) { runOnUiThread { toast(e.message) } } }
    }

    private fun loadFoods() {
        thread {
            try {
                val d = api.call("partner_food_list").optJSONObject("data") ?: JSONObject()
                val a = d.optJSONArray("foods") ?: JSONArray(); val perms = d.optJSONObject("permissions") ?: JSONObject()
                runOnUiThread {
                    content.removeAllViews()
                    content.addView(title("🍜 Món ăn / tồn kho"))
                    val limit = if (perms.isNull("daily_post_limit")) "Không giới hạn" else perms.optInt("daily_post_limit").toString()
                    val today = perms.optInt("posts_today")
                    val canCreate = perms.optBoolean("can_create_food", true)
                    content.addView(hint("Danh mục được cấp: ${perms.optString("category", session.category())} • Đã đăng hôm nay: $today / $limit"))
                    if (canCreate) {
                        val add = Button(this).apply { text = "➕ Thêm món mới" }
                        content.addView(add); add.setOnClickListener { showCreateFoodDialog() }
                    } else content.addView(hint("Bạn đã đạt giới hạn đăng món hôm nay."))
                    if (a.length() == 0) content.addView(hint("Chưa có món trong tiệm."))
                    for (i in 0 until a.length()) addFood(a.getJSONObject(i))
                }
            } catch (e: Exception) { runOnUiThread { toast(e.message) } }
        }
    }

    private fun addFood(o: JSONObject) {
        val c = card(); val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val img = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(86, 64); scaleType = ImageView.ScaleType.CENTER_CROP }
        titleRow.addView(img); titleRow.addView(text(o.optString("name"), 18, true), LinearLayout.LayoutParams(0, -2, 1f)); c.addView(titleRow)
        val imageUrl = o.optString("image_url")
        if (imageUrl.isNotBlank()) loadRemoteImage(imageUrl, img)
        c.addView(text("Danh mục: ${o.optString("category", session.category())}", 13, false))
        c.addView(text("Giá: ${vnd(o.optInt("price"))} • ${if (o.optInt("is_active") == 1) "Đang bán" else "Chờ/ẩn"}", 14, false))
        c.addView(hint("Tên món, giá, danh mục và trạng thái bán do Admin quản lý."))
        val row = LinearLayout(this); val stock = EditText(this).apply { setText(o.optInt("stock").toString()); inputType = InputType.TYPE_CLASS_NUMBER; hint = "Tồn kho" }
        val save = Button(this).apply { text = "Lưu tồn" }; val image = Button(this).apply { text = "📷 Đổi ảnh" }
        row.addView(stock, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(save, LinearLayout.LayoutParams(0, -2, 1f)); c.addView(row); c.addView(image)
        save.setOnClickListener { updateFood(o.optInt("id"), stock.text.toString().toIntOrNull() ?: 0, null) }
        image.setOnClickListener {
            pendingCreateName = ""; imagePickerMode = "update:${o.optInt("id")}:${stock.text}"; openImagePicker()
        }
        content.addView(c)
    }

    private fun showCreateFoodDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 4, 20, 0) }
        val name = EditText(this).apply { hint = "Tên món" }
        val stock = EditText(this).apply { hint = "Số lượng tồn ban đầu"; inputType = InputType.TYPE_CLASS_NUMBER }
        val imageInfo = TextView(this).apply { text = "Chưa chọn ảnh"; setPadding(0, 8, 0, 8) }
        val pick = Button(this).apply { text = "📷 Chọn ảnh món" }
        box.addView(name); box.addView(stock); box.addView(imageInfo); box.addView(pick)
        selectedImageUri = null
        val dialog = AlertDialog.Builder(this).setTitle("Thêm món mới").setView(box).setNegativeButton("Huỷ", null)
            .setPositiveButton("Gửi Admin duyệt", null).create()
        pick.setOnClickListener { imagePickerMode = "create"; openImagePicker(); imageInfo.text = "Đang chọn ảnh..." }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = name.text.toString().trim(); val s = stock.text.toString().toIntOrNull() ?: 0
                if (n.isBlank()) { name.error = "Nhập tên món"; return@setOnClickListener }
                pendingCreateName = n; pendingCreateStock = maxOf(0, s)
                createFood(selectedImageUri); dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
        startActivityForResult(intent, PICK_IMAGE)
    }

    @Deprecated("Deprecated Android callback retained for minSdk 24 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_IMAGE || resultCode != RESULT_OK || data?.data == null) return
        selectedImageUri = data.data
        try { contentResolver.takePersistableUriPermission(selectedImageUri!!, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        if (imagePickerMode.startsWith("update:")) {
            val parts = imagePickerMode.split(":")
            val id = parts.getOrNull(1)?.toIntOrNull() ?: return
            val stock = parts.getOrNull(2)?.toIntOrNull() ?: 0
            updateFood(id, stock, selectedImageUri)
        } else toast("Đã chọn ảnh")
        imagePickerMode = ""
    }

    private fun createFood(uri: Uri?) {
        thread {
            try {
                val file = uri?.let { readUpload(it) }
                val result = api.upload("partner_food_create", mapOf("name" to pendingCreateName, "stock" to pendingCreateStock.toString()), file)
                runOnUiThread { toast(result.optString("message", "Đã gửi món mới")); loadFoods() }
            } catch (e: Exception) { runOnUiThread { toast(e.message) } }
        }
    }

    private fun updateFood(id: Int, stock: Int, uri: Uri?) {
        thread {
            try {
                val file = uri?.let { readUpload(it) }
                api.upload("partner_food_update", mapOf("food_id" to id.toString(), "stock" to maxOf(0, stock).toString()), file)
                runOnUiThread { toast("Đã cập nhật"); loadFoods() }
            } catch (e: Exception) { runOnUiThread { toast(e.message) } }
        }
    }

    private fun readUpload(uri: Uri): UploadFile {
        val mime = contentResolver.getType(uri) ?: "image/jpeg"
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Không đọc được ảnh")
        if (bytes.size > 10 * 1024 * 1024) throw IllegalStateException("Ảnh vượt quá 10MB")
        val ext = when (mime.lowercase(Locale.US)) { "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> "jpg" }
        return UploadFile("food_${System.currentTimeMillis()}.$ext", mime, bytes)
    }

    private fun loadRemoteImage(url: String, image: ImageView) {
        thread {
            try { val bmp = BitmapFactory.decodeStream(URL(url).openStream()); runOnUiThread { if (bmp != null) image.setImageBitmap(bmp) } } catch (_: Exception) { }
        }
    }

    private fun loadLedger() {
        thread {
            try {
                val d = api.call("partner_ledger").optJSONObject("data") ?: JSONObject()
                val a = d.optJSONArray("ledger") ?: JSONArray(); val summary = d.optJSONObject("summary")
                runOnUiThread {
                    content.removeAllViews(); content.addView(title("💰 Đối soát"))
                    summary?.let { content.addView(hint("Tổng phát sinh: ${vnd(it.optInt("total"))} • Chờ đối soát: ${vnd(it.optInt("pending"))}")) }
                    if (a.length() == 0) content.addView(hint("Chưa có phát sinh ledger."))
                    for (i in 0 until a.length()) { val o = a.getJSONObject(i); content.addView(card().apply {
                        addView(text("${o.optString("type")} • ${vnd(o.optInt("amount"))}", 17, true)); addView(text("Đơn #${o.optInt("order_id")} • ${o.optString("status")}", 14, false)); addView(text(o.optString("description"), 13, false)); addView(text(o.optString("created_at"), 12, false))
                    }) }
                }
            } catch (e: Exception) { runOnUiThread { toast(e.message) } }
        }
    }

    private fun loadAccount() {
        thread {
            try {
                val p = api.call("partner_me").optJSONObject("data")?.optJSONObject("partner") ?: JSONObject()
                val perms = p.optJSONObject("permissions") ?: JSONObject(); val foods = perms.optJSONObject("foods") ?: JSONObject(); val orders = perms.optJSONObject("orders") ?: JSONObject()
                runOnUiThread {
                    content.removeAllViews(); content.addView(title("🔐 Tài khoản & quyền hạn"))
                    content.addView(card().apply {
                        addView(text("Tài khoản: ${p.optString("username")}", 17, true)); addView(text("Tiệm: ${p.optString("store_name")}", 15, false)); addView(text("KCN ID: ${p.optInt("store_kcn_id")}", 14, false)); addView(text("Danh mục được cấp: ${p.optString("category")}", 14, false)); addView(text("Giới hạn đăng món/ngày: ${if (p.isNull("daily_post_limit")) "Không giới hạn" else p.optInt("daily_post_limit")}", 14, false)); addView(text("Đã đăng hôm nay: ${p.optInt("posts_today")}", 14, false)); addView(text("Điện thoại tiệm: ${p.optString("store_phone")}", 14, false)); addView(text("Địa chỉ: ${p.optString("store_address")}", 14, false))
                    })
                    content.addView(card().apply {
                        addView(text("Quyền đơn hàng", 17, true)); addView(text("${if (orders.optBoolean("view")) "✓" else "✗"} Xem đơn", 14, false)); addView(text("${if (orders.optBoolean("confirm")) "✓" else "✗"} Nhận đơn", 14, false)); addView(text("${if (orders.optBoolean("reject")) "✓" else "✗"} Từ chối đơn", 14, false)); addView(text("${if (orders.optBoolean("mark_ready")) "✓" else "✗"} Báo món đã xong", 14, false))
                    })
                    content.addView(card().apply {
                        addView(text("Quyền món ăn", 17, true)); addView(text("${if (foods.optBoolean("create")) "✓" else "✗"} Thêm món mới theo giới hạn Admin", 14, false)); addView(text("✓ Cập nhật tồn kho", 14, false)); addView(text("✓ Cập nhật ảnh", 14, false)); addView(text("✗ Sửa tên món", 14, false)); addView(text("✗ Sửa giá", 14, false)); addView(text("✗ Đổi danh mục", 14, false)); addView(text("✗ Bật/tắt món", 14, false)); addView(text("✗ Xoá món", 14, false))
                    })
                    content.addView(hint("Các quyền quan trọng được kiểm tra lại ở máy chủ. App không thể tự mở thêm quyền bằng cách sửa giao diện."))
                }
            } catch (e: Exception) { runOnUiThread { toast(e.message) } }
        }
    }

    private fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 18, 18, 18); setBackgroundResource(android.R.drawable.dialog_holo_light_frame); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12 } }
    private fun title(s: String) = text(s, 20, true).apply { setPadding(0, 18, 0, 4) }
    private fun hint(s: String) = text(s, 14, false)
    private fun text(s: String, size: Int, bold: Boolean) = TextView(this).apply { text = s; textSize = size.toFloat(); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 3, 0, 3) }
    private fun vnd(n: Int) = "%,d đ".format(n).replace(',', '.')
    private fun toast(s: String?) = Toast.makeText(this, s ?: "Có lỗi", Toast.LENGTH_SHORT).show()
    private fun scheduleSync() { val r = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(); WorkManager.getInstance(this).enqueueUniquePeriodicWork("partner_sync", ExistingPeriodicWorkPolicy.UPDATE, r) }
}
