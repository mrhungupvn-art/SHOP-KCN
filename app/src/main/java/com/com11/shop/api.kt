package com.com11h.shop

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class Api(private val baseUrl: String, private val kcnId: Int, private var token: String? = null) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
        .build()
    fun setToken(t: String?) { token = t }
    fun call(action: String, body: JSONObject? = null, query: String = ""): JSONObject {
        val url = baseUrl.trimEnd('/') + "/api/index.php?action=" + action + query
        val builder = Request.Builder().url(url).addHeader("X-KCN-ID", kcnId.toString()).addHeader("Accept", "application/json")
        token?.let { builder.addHeader("Authorization", "Bearer $it") }
        if (body != null) builder.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())) else builder.get()
        client.newCall(builder.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (r.code == 401) throw UnauthorizedException(json.optString("message", "Phiên đăng nhập đã hết hạn"))
            if (!r.isSuccessful || !json.optBoolean("ok", false)) throw ApiException(json.optString("message", "API error"), r.code)
            return json
        }
    }

    companion object {
        /**
         * Lấy danh sách KCN cho màn hình chọn KCN lúc đăng nhập.
         * Action "kcn_list" không cần X-KCN-ID/token (đọc từ DB master), nên
         * gọi được bằng 1 client tạm, không cần đăng nhập trước.
         */
        fun fetchKcnList(baseUrl: String): List<KcnItem> {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
            val url = baseUrl.trimEnd('/') + "/api/index.php?action=kcn_list"
            val req = Request.Builder().url(url).addHeader("Accept", "application/json").get().build()
            client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
                if (!r.isSuccessful || !json.optBoolean("ok", false)) throw ApiException(json.optString("message", "Không tải được danh sách KCN"), r.code)
                val arr = json.optJSONObject("data")?.optJSONArray("industrial_zones") ?: return emptyList()
                return (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    KcnItem(o.optInt("id"), o.optString("name"), o.optString("province", ""))
                }
            }
        }
    }
}
data class KcnItem(val id: Int, val name: String, val province: String) {
    override fun toString(): String = if (province.isNotBlank()) "$name ($province)" else name
}
class ApiException(message: String, val code: Int = 0): RuntimeException(message)
class UnauthorizedException(message: String): RuntimeException(message)
