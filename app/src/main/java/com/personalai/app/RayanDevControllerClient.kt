package com.personalai.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Separate loopback client. Call network methods from Dispatchers.IO. */
object RayanDevControllerClient {
    private const val HOST = "http://127.0.0.1:8766"
    private const val KEY_ALIAS = "rayan_dev_controller_token_v1"
    private const val PREFS = "rayan_dev_controller_secure"
    private const val TOKEN_KEY = "encrypted_token"
    private const val MAX_RESPONSE_CHARS = 8192
    private val actions = setOf("status", "report", "backup_verify", "memory_test", "safe_audit", "build_apk")
    private val confirmationActions = setOf("safe_audit", "build_apk")
    private val noConfirmationActions = setOf("status", "report", "backup_verify", "memory_test")

    enum class State { COMPLETED, REJECTED, FAILED, UNKNOWN, NOT_FOUND }

    data class Result(
        val requestId: String?,
        val state: State,
        val message: String,
        val action: String? = null,
        val httpCode: Int? = null,
        val raw: String = ""
    )

    fun isConfigured(context: Context): Boolean =
        runCatching { readToken(context).isNotBlank() }.getOrDefault(false)

    /** Call only after the owner explicitly enters/confirms the separate token. */
    fun saveToken(context: Context, token: String) {
        require(token.length in 40..256) { "توکن کنترلر معتبر نیست." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(TOKEN_KEY, encoded).commit()) {
            "ذخیرهٔ امن توکن کنترلر ناموفق بود."
        }
    }

    fun clearToken(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(TOKEN_KEY).commit()
    }

    fun execute(context: Context, action: String, confirmation: Boolean = false): Result {
        if (action !in actions) return Result(null, State.REJECTED, "این فرمان در فهرست مجاز نیست.", action)
        if (action in noConfirmationActions && confirmation) {
            return Result(null, State.REJECTED, "برای این فرمان تأیید اضافه پذیرفته نمی‌شود.", action)
        }
        if (action in confirmationActions && !confirmation) {
            return Result(null, State.REJECTED, "تأیید جداگانه روی صفحه لازم است.", action)
        }
        val requestId = UUID.randomUUID().toString()
        val body = JSONObject().put("request_id", requestId)
            .put("action", action).put("confirmation", confirmation)
        return try {
            val token = readToken(context)
            if (token.isBlank()) Result(requestId, State.REJECTED, "توکن کنترلر تنظیم نشده است.", action)
            else parseResponse(request("POST", "/v1/controller/execute", body.toString(), token), requestId, action)
        } catch (_: SocketTimeoutException) {
            Result(requestId, State.UNKNOWN, "پاسخ به‌موقع نرسید؛ پیش از تکرار، وضعیت همین شناسه را بررسی کن.", action)
        } catch (e: Exception) {
            Result(requestId, State.UNKNOWN, "نتیجهٔ درخواست قطعی نیست: " + e.javaClass.simpleName + ".", action)
        }
    }

    /**
     * The only Executor operation exposed to Android in this version.
     * Capability, workspace, inputs, deadline and resource profile are constants;
     * callers cannot provide an executable, command-line arguments or arbitrary inputs.
     */
    fun executePythonVersion(context: Context): Result {
        val requestId = UUID.randomUUID().toString()
        val operationId = UUID.randomUUID().toString()
        val body = JSONObject()
            .put("protocol_version", 1)
            .put("request_id", requestId)
            .put("operation_id", operationId)
            .put("capability", "python_version")
            .put("argv", org.json.JSONArray())
            .put("cwd_id", "personalai2")
            .put("inputs", JSONObject())
            .put("approval_grant", JSONObject.NULL)
            .put("deadline_seconds", 5)
            .put("resource_profile", "tiny")
        return try {
            val token = readToken(context)
            if (token.isBlank()) {
                Result(requestId, State.REJECTED, "توکن کنترلر تنظیم نشده است.", "executor:python_version")
            } else {
                parseResponse(
                    request("POST", "/v1/executor/execute", body.toString(), token),
                    requestId,
                    "executor:python_version"
                )
            }
        } catch (_: SocketTimeoutException) {
            Result(requestId, State.UNKNOWN,
                "پاسخ به‌موقع نرسید؛ وضعیت همین شناسه را بررسی کن و فرمان را دوباره اجرا نکن.",
                "executor:python_version")
        } catch (e: Exception) {
            Result(requestId, State.UNKNOWN,
                "نتیجهٔ درخواست قطعی نیست: " + e.javaClass.simpleName + ".",
                "executor:python_version")
        }
    }

    /** Reconcile an interrupted request; do not blindly submit a new request. */
    fun query(context: Context, requestId: String): Result {
        val canonical = runCatching { UUID.fromString(requestId).toString() }.getOrNull()
            ?: return Result(null, State.REJECTED, "شناسهٔ درخواست معتبر نیست.")
        if (canonical != requestId) return Result(null, State.REJECTED, "شناسهٔ درخواست باید UUID استاندارد باشد.")
        return try {
            parseResponse(request("GET", "/v1/controller/requests/$canonical", null, readToken(context)),
                canonical, null)
        } catch (_: SocketTimeoutException) {
            Result(canonical, State.UNKNOWN, "پاسخ وضعیت هنوز نرسیده است.")
        } catch (e: Exception) {
            Result(canonical, State.UNKNOWN, "بررسی وضعیت ناموفق بود: " + e.javaClass.simpleName + ".")
        }
    }

    private data class HttpResult(val code: Int, val body: String)

    private fun request(method: String, path: String, body: String?, token: String): HttpResult {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(HOST + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 2500
                readTimeout = 3500
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (body != null) connection.outputStream.use {
                it.write(body.toByteArray(StandardCharsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { reader ->
                val out = StringBuilder()
                val buffer = CharArray(1024)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (out.length + count > MAX_RESPONSE_CHARS) throw IOException("response too large")
                    out.append(buffer, 0, count)
                }
                out.toString()
            }.orEmpty()
            return HttpResult(code, text)
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseResponse(http: HttpResult, fallbackId: String?, fallbackAction: String?): Result {
        if (http.code == 404) return Result(fallbackId, State.NOT_FOUND,
            "شناسهٔ درخواست در دفتر نتایج پیدا نشد.", fallbackAction, http.code, http.body)
        val json = runCatching { JSONObject(http.body) }.getOrNull()
            ?: return Result(fallbackId, State.FAILED, "پاسخ کنترلر قابل‌خواندن نیست.",
                fallbackAction, http.code, http.body)
        val id = json.optString("request_id").takeIf { it.isNotBlank() } ?: fallbackId
        val action = json.optString("action").takeIf { it.isNotBlank() } ?: fallbackAction
        val state = when (json.optString("status")) {
            "completed" -> State.COMPLETED
            "rejected" -> State.REJECTED
            "failed" -> State.FAILED
            "unknown" -> State.UNKNOWN
            "not_found" -> State.NOT_FOUND
            else -> if (http.code in 200..299) State.UNKNOWN else State.FAILED
        }
        val message = json.optString("message", json.optString("error_code", "پاسخ کنترلر دریافت شد."))
        return Result(id, state, message, action, http.code, http.body)
    }

    private fun readToken(context: Context): String {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(TOKEN_KEY, null) ?: return ""
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > 12) { "توکن ذخیره‌شده خراب است." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
            GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        return String(cipher.doFinal(packed.copyOfRange(12, packed.size)), StandardCharsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
}