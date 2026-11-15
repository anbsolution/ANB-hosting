package com.anbhosting.app

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class HostingApi {
    @Volatile private var origin = ""
    @Volatile private var csrfToken = ""
    private val jar = object : CookieJar {
        private val lock = Any()
        private val saved = mutableListOf<Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(lock) {
                saved.removeAll { old -> cookies.any { it.name == old.name && it.domain == old.domain && it.path == old.path } }
                saved.addAll(cookies.filter { it.expiresAt > System.currentTimeMillis() })
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
            saved.removeAll { it.expiresAt <= System.currentTimeMillis() }
            saved.filter { it.matches(url) }
        }
        fun clear() = synchronized(lock) { saved.clear() }
    }
    private val client = OkHttpClient.Builder().cookieJar(jar)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun setServer(value: String) {
        origin = value.trim().trimEnd('/')
        csrfToken = ""
    }

    private fun url(path: String): HttpUrl {
        val base = origin.toHttpUrlOrNull() ?: throw IOException("Enter a valid HTTPS server URL.")
        if (base.scheme != "https") throw IOException("HTTPS is required for secure sign-in.")
        return base.newBuilder().addPathSegments("api/v1/$path").build()
    }

    @Synchronized private fun call(path: String, method: String = "GET", data: JSONObject? = null, csrf: Boolean = false): String {
        if (origin.isBlank()) throw IOException("Set the server URL first.")
        if (csrf) refreshCsrf()
        val builder = Request.Builder().url(url(path)).header("Accept", "application/json")
        if (csrf) builder.header("X-CSRF-Token", csrfToken)
        when (method) {
            "POST" -> builder.post((data ?: JSONObject()).toString().toRequestBody(jsonType))
            "DELETE" -> builder.delete()
            else -> builder.get()
        }
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = try { JSONObject(body).optString("message", "Request failed (${response.code})") }
                    catch (_: Exception) { "Request failed (${response.code})" }
                throw IOException(message)
            }
            return body
        }
    }

    @Synchronized private fun refreshCsrf() {
        csrfToken = JSONObject(call("auth/csrf")).optString("csrfToken")
        if (csrfToken.isBlank()) throw IOException("The server did not provide a CSRF token.")
    }

    fun login(email: String, password: String): JSONObject {
        refreshCsrf()
        val result = JSONObject(call("auth/login", "POST", JSONObject().put("email", email.trim()).put("password", password), true))
        refreshCsrf()
        return result
    }
    fun register(email: String, password: String, workspace: String): JSONObject {
        refreshCsrf()
        val body = JSONObject().put("email", email.trim()).put("password", password).put("workspaceName", workspace.trim())
        val result = JSONObject(call("auth/register", "POST", body, true))
        refreshCsrf()
        return result
    }
    fun profile() = JSONObject(call("auth/me"))
    fun summary() = JSONObject(call("dashboard/summary"))
    fun websites() = JSONArray(call("websites"))
    fun websiteAction(id: String, action: String) = JSONObject(call("websites/$id/$action", "POST", csrf = true))
    fun logout() {
        try { call("auth/logout", "POST", csrf = true) } finally { jar.clear(); csrfToken = "" }
    }
}
