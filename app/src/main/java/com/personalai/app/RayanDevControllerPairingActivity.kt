package com.personalai.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RayanDevControllerPairingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { DevControllerPairingPanel() } }
    }
}

@Composable
private fun DevControllerPairingPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var status by remember {
        mutableStateOf("توکن مستقل کنترلر را از خروجی امن Termux وارد کن. توکن پل حافظه اینجا قابل استفاده نیست.")
    }
    var busy by remember { mutableStateOf(false) }
    var lastRequestId by remember { mutableStateOf<String?>(null) }

    fun showResult(prefix: String, result: RayanDevControllerClient.Result) {
        if (result.requestId != null) lastRequestId = result.requestId
        status = prefix + result.state + ": " + result.message
    }

    fun runReadOnlyAction(action: String) {
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { RayanDevControllerClient.execute(context, action) }
            }
            status = result.fold(
                onSuccess = { response ->
                    if (response.requestId != null) lastRequestId = response.requestId
                    response.state.toString() + ": " + response.message
                },
                onFailure = { failure -> "درخواست ناموفق بود: " + failure.javaClass.simpleName }
            )
            busy = false
        }
    }

    fun runPythonVersionProbe() {
        if (busy || !RayanDevControllerClient.isConfigured(context)) return
        busy = true
        status = "در حال اجرای آزمون ثابت python --version؛ هیچ فرمان دلخواهی ارسال نمی‌شود."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { RayanDevControllerClient.executePythonVersion(context) }
            }
            status = result.fold(
                onSuccess = { response ->
                    if (response.requestId != null) lastRequestId = response.requestId
                    "آزمون نسخهٔ Python — " + response.state + ": " + response.message
                },
                onFailure = { failure -> "آزمون ناموفق بود: " + failure.javaClass.simpleName }
            )
            busy = false
        }
    }

    fun reconcileLastRequest() {
        val requestId = lastRequestId ?: return
        if (busy || !RayanDevControllerClient.isConfigured(context)) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { RayanDevControllerClient.query(context, requestId) }
            }
            status = result.fold(
                onSuccess = { response ->
                    "وضعیت درخواست " + requestId + " — " + response.state + ": " + response.message
                },
                onFailure = { failure -> "بررسی وضعیت ناموفق بود: " + failure.javaClass.simpleName }
            )
            busy = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("اتصال امن کنترلر رایان", style = MaterialTheme.typography.headlineSmall)
        Text("این توکن فقط برای کنترلر 127.0.0.1:8766 است و با Android Keystore رمز می‌شود.")
        OutlinedTextField(
            value = token,
            onValueChange = { token = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("توکن مستقل کنترلر") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = !busy
        )
        Button(
            onClick = {
                if (token.length !in 40..256) {
                    status = "طول توکن معتبر نیست."
                } else {
                    busy = true
                    val suppliedToken = token
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching {
                                RayanDevControllerClient.saveToken(context, suppliedToken)
                                RayanDevControllerClient.execute(context, "status")
                            }
                        }
                        status = outcome.fold(
                            onSuccess = { result ->
                                when (result.state) {
                                    RayanDevControllerClient.State.COMPLETED ->
                                        "اتصال و احراز هویت موفق است. کنترلر پاسخ داد."
                                    RayanDevControllerClient.State.UNKNOWN ->
                                        "توکن ذخیره شد؛ پاسخ کنترلر نامشخص است. اجرای Termux را بررسی کن."
                                    else -> "توکن ذخیره شد، اما آزمون اتصال موفق نبود: " + result.message
                                }
                            },
                            onFailure = { failure ->
                                "ذخیره یا آزمون ناموفق بود: " + failure.javaClass.simpleName
                            }
                        )
                        token = ""
                        busy = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && token.length in 40..256
        ) {
            Text(if (busy) "در حال آزمون..." else "ذخیره امن و آزمون اتصال")
        }
        Text("فرمان‌های فقط‌خواندنی کنترلر")
        Button(
            onClick = { runReadOnlyAction("status") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && RayanDevControllerClient.isConfigured(context)
        ) { Text("دریافت وضعیت کنترلر") }
        Button(
            onClick = { runReadOnlyAction("report") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && RayanDevControllerClient.isConfigured(context)
        ) { Text("دریافت گزارش") }
        Button(
            onClick = { runReadOnlyAction("backup_verify") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && RayanDevControllerClient.isConfigured(context)
        ) { Text("بررسی آخرین پشتیبان") }
        Text("آزمون محدود Executor")
        Text("فقط نسخهٔ مفسر Python در Termux خوانده می‌شود. مسیر فایل، آرگومان دلخواه، shell و فرمان عمومی در این رابط وجود ندارد.")
        Button(
            onClick = { runPythonVersionProbe() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && RayanDevControllerClient.isConfigured(context)
        ) { Text(if (busy) "در حال اجرا..." else "اجرای آزمون ثابت نسخهٔ Python") }
        Button(
            onClick = { reconcileLastRequest() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && lastRequestId != null && RayanDevControllerClient.isConfigured(context)
        ) { Text("بررسی وضعیت آخرین درخواست") }
        Button(
            onClick = {
                RayanDevControllerClient.clearToken(context)
                token = ""
                lastRequestId = null
                status = "توکن کنترلر از حافظهٔ امن برنامه پاک شد."
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy
        ) { Text("قطع جفت‌سازی و پاک‌کردن توکن") }
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Text("ذخیرهٔ توکن فقط وضعیت کنترلر را می‌خواند؛ اجرای آزمون Python فقط با فشردن دکمهٔ اختصاصی انجام می‌شود.")
    }
}
