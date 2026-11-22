package com.personalai.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RayanModelManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                RayanModelManagerPanel()
            }
        }
    }
}

@Composable
private fun RayanModelManagerPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var whisperReady by remember { mutableStateOf(RayanModelStore.isWhisperInstalled(context)) }
    var speakerReady by remember { mutableStateOf(RayanModelStore.isSpeakerInstalled(context)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf("مدل‌ها اختیاری‌اند. فقط با انتخاب خودت دریافت می‌شوند و پس از دریافت، بدون اینترنت کار می‌کنند.")
    }

    fun downloadWhisper() {
        if (busy || whisperReady) return
        scope.launch {
            busy = true
            status = "دریافت مدل تشخیص گفتار فارسی آغاز شد..."
            try {
                withContext(Dispatchers.IO) {
                    RayanModelStore.downloadWhisper(context) { name, done, total ->
                        val percent = if (total > 0L) (done * 100L / total).toInt() else 0
                        mainHandler.post {
                            status = "دریافت $name: $percent٪"
                        }
                    }
                }
                whisperReady = true
                status = "مدل گفتار با موفقیت دریافت و اعتبارسنجی شد؛ از این پس آفلاین است."
            } catch (error: Exception) {
                status = "دریافت مدل گفتار ناموفق بود: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    fun downloadSpeaker() {
        if (busy || speakerReady) return
        scope.launch {
            busy = true
            status = "دریافت مدل تشخیص گوینده آغاز شد..."
            try {
                withContext(Dispatchers.IO) {
                    RayanModelStore.downloadSpeaker(context) { name, done, total ->
                        val percent = if (total > 0L) (done * 100L / total).toInt() else 0
                        mainHandler.post {
                            status = "دریافت $name: $percent٪"
                        }
                    }
                }
                speakerReady = true
                status = "مدل تشخیص گوینده با موفقیت دریافت و اعتبارسنجی شد؛ از این پس آفلاین است."
            } catch (error: Exception) {
                status = "دریافت مدل تشخیص گوینده ناموفق بود: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("مدیریت مدل‌های آفلاین رایان", style = MaterialTheme.typography.headlineSmall)
        Text("برنامه بدون این مدل‌ها هم اجرا می‌شود. دریافت مدل فقط با اینترنت و با فشردن دکمه انجام می‌شود؛ فایل‌ها در حافظهٔ خصوصی برنامه نگهداری می‌شوند.")

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("تشخیص گفتار فارسی — Whisper Tiny")
                Text("حدود ۱۰۳ مگابایت؛ تبدیل گفتار به متن روی گوشی")
                Text(if (whisperReady) "وضعیت: دریافت و اعتبارسنجی شده" else "وضعیت: دریافت نشده")
                Button(
                    onClick = { downloadWhisper() },
                    enabled = !busy && !whisperReady,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (whisperReady) "مدل گفتار آماده است" else "دریافت مدل گفتار")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("تشخیص گوینده — 3D-Speaker")
                Text("حدود ۲۹٫۶ مگابایت؛ برای ثبت و مقایسهٔ الگوی صدا")
                Text(if (speakerReady) "وضعیت: دریافت و اعتبارسنجی شده" else "وضعیت: دریافت نشده")
                Button(
                    onClick = { downloadSpeaker() },
                    enabled = !busy && !speakerReady,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (speakerReady) "مدل گوینده آماده است" else "دریافت مدل گوینده")
                }
            }
        }

        Text(status, style = MaterialTheme.typography.bodyMedium)
        if (busy) {
            Text("هنگام دریافت، برنامه را نبند؛ فایل‌ها پس از بررسی SHA-256 به‌صورت نهایی ذخیره می‌شوند.")
        }
        Text("برای استفادهٔ آفلاین، هر مدل موردنیاز را فقط یک‌بار دریافت کن. حذف یا دریافت مجدد خودکار انجام نمی‌شود.")
    }
}
