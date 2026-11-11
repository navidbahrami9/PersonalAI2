package com.personalai.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED

class RayanVoiceControlActivity : ComponentActivity() {
    private val requestAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) requestPermissionsAndStart()
        }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startVoiceCapture()
            else android.widget.Toast.makeText(this, "برای شفافیت، مجوز اعلان لازم است.", android.widget.Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                VoiceControlPanel(
                    onStart = { requestPermissionsAndStart() },
                    onStop = { stopVoiceCapture() },
                    onEnrollment = { startActivity(Intent(this, RayanVoiceEnrollmentActivity::class.java)) },
                    onCalibration = { startActivity(Intent(this, RayanVoiceCalibrationActivity::class.java)) }
                )
            }
        }
    }

    private fun requestPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startVoiceCapture()
    }

    private fun startVoiceCapture() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, RayanVoiceAssistantService::class.java)
                .setAction(RayanVoiceAssistantService.ACTION_START)
        )
    }

    private fun stopVoiceCapture() {
        startService(
            Intent(this, RayanVoiceAssistantService::class.java)
                .setAction(RayanVoiceAssistantService.ACTION_STOP)
        )
    }
}

@Composable
private fun VoiceControlPanel(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEnrollment: () -> Unit,
    onCalibration: () -> Unit
) {
    var status by remember { mutableStateOf("میکروفون خاموش است.") }
    var level by remember { mutableStateOf(-90f) }
    val context = androidx.compose.ui.platform.LocalContext.current

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    RayanVoiceCaptureService.ACTION_STATUS ->
                        status = intent.getStringExtra(RayanVoiceCaptureService.EXTRA_STATUS) ?: status
                    RayanVoiceCaptureService.ACTION_LEVEL ->
                        level = intent.getFloatExtra(RayanVoiceCaptureService.EXTRA_LEVEL_DB, -90f)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(RayanVoiceCaptureService.ACTION_STATUS)
            addAction(RayanVoiceCaptureService.ACTION_LEVEL)
        }
        ContextCompat.registerReceiver(context, receiver, filter, RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("کنترل دستیار صوتی رایان", style = MaterialTheme.typography.headlineSmall)
        Text(status)
        Text("سطح ورودی: ${level.toInt()} dBFS")
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("شروع میکروفون") }
        Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("توقف میکروفون") }
        Button(onClick = onEnrollment, modifier = Modifier.fillMaxWidth()) { Text("ثبت سه نمونه صدای من") }
        Button(onClick = onCalibration, modifier = Modifier.fillMaxWidth()) { Text("کالیبراسیون با صدای خانواده") }
        Text("ابتدا ثبت صدای مالک و سپس حداقل سه نمونهٔ مالک و سه نمونهٔ غیرمالک را انجام بده. برای ثبت نمونه‌ها، میکروفون دستیار را متوقف کن.")
        Text("برای توقف، دکمهٔ توقف یا اعلان دائمی را بزن. واژهٔ بیدارباش «رایان» است؛ پیش از هر فرمان، عبارت تصادفی و صدای مالک بررسی می‌شود. پردازش آفلاین است.")
    }
}
