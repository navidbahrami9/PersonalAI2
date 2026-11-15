package com.rayan.controller

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class RayanControllerActivity : Activity() {
    private lateinit var state: TextView
    private lateinit var lockButton: Button
    private lateinit var unlockButton: Button
    private lateinit var notificationButton: Button
    private var locked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locked = store().getBoolean(KEY_LOCKED, false)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 36)
        }
        layout.addView(TextView(this).apply {
            text = "Rayan Controller"
            textSize = 25f
        })
        layout.addView(TextView(this).apply {
            text = "کنترلر مستقل رایان"
            textSize = 17f
            setPadding(0, 12, 0, 24)
        })

        state = TextView(this).apply { textSize = 16f }
        layout.addView(state)

        lockButton = makeButton("فعال‌کردن قفل اضطراری") {
            setLocked(true)
        }
        layout.addView(lockButton)

        unlockButton = makeButton("بازکردن قفل با رمز گوشی") {
            requestDeviceCredential()
        }
        layout.addView(unlockButton)

        notificationButton = makeButton("ارسال اعلان آزمایشی") {
            if (!locked) requestNotificationOrPost()
        }
        layout.addView(notificationButton)

        layout.addView(TextView(this).apply {
            text = "قفل این نسخه فقط قابلیت‌های محلی همین اپ را می‌بندد؛ هنوز پردازش‌های Termux را متوقف نمی‌کند."
            textSize = 13f
            setPadding(0, 24, 0, 0)
        })
        setContentView(layout)
        refreshState()
    }

    private fun makeButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    private fun store() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun setLocked(value: Boolean) {
        check(store().edit().putBoolean(KEY_LOCKED, value).commit())
        locked = value
        refreshState()
    }

    private fun refreshState() {
        state.text = if (locked) {
            "وضعیت: قفل اضطراری فعال است؛ برای بازکردن، رمز یا اثرانگشت قفل صفحه لازم است."
        } else {
            "وضعیت: کنترلر آماده است."
        }
        lockButton.isEnabled = !locked
        unlockButton.isEnabled = locked
        notificationButton.isEnabled = !locked
    }

    private fun requestDeviceCredential() {
        if (!locked) return
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) {
            state.text = "برای امنیت، ابتدا PIN، الگو یا گذرواژهٔ قفل صفحهٔ گوشی را فعال کن."
            return
        }
        val intent: Intent = keyguard.createConfirmDeviceCredentialIntent(
            "تأیید هویت",
            "برای بازکردن قفل رایان، قفل صفحهٔ گوشی را تأیید کن."
        ) ?: run {
            state.text = "امکان نمایش تأیید هویت دستگاه وجود ندارد؛ قفل همچنان فعال است."
            return
        }
        startActivityForResult(intent, REQUEST_DEVICE_CREDENTIAL)
    }

    @Deprecated("Deprecated by Android, retained for compatibility with minSdk 26")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DEVICE_CREDENTIAL && resultCode == RESULT_OK) {
            setLocked(false)
        } else if (requestCode == REQUEST_DEVICE_CREDENTIAL && locked) {
            state.text = "قفل همچنان فعال است؛ تأیید هویت انجام نشد."
        }
    }

    private fun requestNotificationOrPost() {
        if (locked) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } else {
            postTestNotification()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS && !locked &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) postTestNotification()
    }

    private fun postTestNotification() {
        if (locked) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = "rayan_controller_test"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(channel, "اعلان‌های رایان", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val notification = Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Rayan Controller")
            .setContentText("اعلان آزمایشی اپ مستقل کنترلر")
            .setAutoCancel(true)
            .build()
        manager.notify(7101, notification)
    }

    companion object {
        private const val PREFS = "rayan_controller"
        private const val KEY_LOCKED = "emergency_local_lock"
        private const val REQUEST_NOTIFICATIONS = 71
        private const val REQUEST_DEVICE_CREDENTIAL = 72
    }
}
