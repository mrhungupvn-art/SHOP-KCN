package com.com11h.partner

import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class Api(private val baseUrl: String, private val kcnId: Int, private var token: String? = null) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun setToken(t: String?) { token = t }

    fun call(action: String, body: JSONObject? = null): JSONObject {
        val url = baseUrl.trimEnd('/') + "/api/index.php?action=" + action
        val b = Request.Builder().url(url)
            .addHeader("X-KCN-ID", kcnId.toString())
            .addHeader("Accept", "application/json")
        token?.let { b.addHeader("Authorization", "Bearer $it") }
        if (body != null) b.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())) else b.get()
        client.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val j = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (r.code == 401) throw UnauthorizedException(j.optString("message", "Phiên đăng nhập đã hết hạn"))
            if (!r.isSuccessful || !j.optBoolean("ok", false)) {
                val msg = j.optString("message", "API error")
                throw ApiException(msg + if (r.code >= 500) " [HTTP ${r.code}]" else "", r.code)
            }
            return j
        }
    }

    /** Upload multipart giống form web: name/stock + image_file. */
    fun upload(action: String, fields: Map<String, String>, file: UploadFile? = null): JSONObject {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (k, v) -> builder.addFormDataPart(k, v) }
        if (file != null) {
            builder.addFormDataPart(
                "image_file",
                file.fileName,
                file.bytes.toRequestBody(file.mimeType.toMediaType())
            )
        }
        val url = baseUrl.trimEnd('/') + "/api/index.php?action=" + action
        val request = Request.Builder().url(url)
            .addHeader("X-KCN-ID", kcnId.toString())
            .addHeader("Accept", "application/json")
            .apply { token?.let { addHeader("Authorization", "Bearer $it") } }
            .post(builder.build())
            .build()
        client.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val j = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (r.code == 401) throw UnauthorizedException(j.optString("message", "Phiên đăng nhập đã hết hạn"))
            if (!r.isSuccessful || !j.optBoolean("ok", false)) {
                val msg = j.optString("message", "API error")
                throw ApiException(msg + if (r.code >= 500) " [HTTP ${r.code}]" else "", r.code)
            }
            return j
        }
    }

    companion object {
        fun fetchKcnList(baseUrl: String): List<KcnItem> {
            val c = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
            val req = Request.Builder().url(baseUrl.trimEnd('/') + "/api/index.php?action=kcn_list").get().build()
            c.newCall(req).execute().use { r ->
                val j = JSONObject(r.body?.string().orEmpty())
                if (!r.isSuccessful || !j.optBoolean("ok", false)) throw ApiException(j.optString("message", "Không tải được KCN"), r.code)
                val a = j.optJSONObject("data")?.optJSONArray("industrial_zones") ?: return emptyList()
                return (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    KcnItem(o.optInt("id"), o.optString("name"), o.optString("province"))
                }
            }
        }
    }
}

data class UploadFile(val fileName: String, val mimeType: String, val bytes: ByteArray)
data class KcnItem(val id: Int, val name: String, val province: String) {
    override fun toString() = if (province.isBlank()) name else "$name ($province)"
}
class ApiException(message: String, val code: Int = 0) : RuntimeException(message)
class UnauthorizedException(message: String) : RuntimeException(message)
