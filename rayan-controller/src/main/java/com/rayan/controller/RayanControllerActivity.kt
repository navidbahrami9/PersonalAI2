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
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class RayanControllerActivity : Activity() {
    private lateinit var state: TextView
    private lateinit var lockButton: Button
    private lateinit var unlockButton: Button
    private lateinit var notificationButton: Button
    private lateinit var stopStatus: TextView
    private var locked = false

    private val ink = Color.rgb(19, 32, 54)
    private val muted = Color.rgb(103, 116, 137)
    private val accent = Color.rgb(40, 91, 145)
    private val page = Color.rgb(244, 247, 251)
    private val green = Color.rgb(32, 126, 91)
    private val amber = Color.rgb(155, 99, 20)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locked = store().getBoolean(KEY_LOCKED, false)
        buildScreen()
        refreshState()
    }

    private fun buildScreen() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
            setBackgroundColor(page)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
            background = rounded(ink, 22)
        }
        header.addView(label("RAYAN", 12f, Color.rgb(159, 190, 222), true))
        header.addView(label("کنترلر رایان", 27f, Color.WHITE, true).apply {
            setPadding(0, dp(7), 0, dp(5))
        })
        header.addView(label("مرکز کنترل محلی دستگاه", 14f, Color.rgb(213, 224, 238)))
        content.addView(header, matchWrap())

        content.addView(sectionTitle("وضعیت امنیتی"))
        val lockCard = card()
        state = label("", 16f, ink, true)
        lockCard.addView(state)
        lockCard.addView(label(
            "برای بازکردن قفل، تأیید هویت اندروید لازم است. قفل‌کردن کنترلر به اثر انگشت نیاز ندارد.",
            13f, muted
        ).apply { setPadding(0, dp(9), 0, 0) })
        content.addView(lockCard, marginParams(top = 8))

        content.addView(sectionTitle("توقف اضطراری"))
        val emergencyCard = card()
        val badge = label("قابلیت هنوز فعال نشده", 12f, amber, true).apply {
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(Color.rgb(255, 244, 224), 30)
        }
        emergencyCard.addView(badge, wrapParams())
        emergencyCard.addView(label(
            "توقف پردازش‌های Termux هنوز به کنترلر متصل نشده است.",
            16f, ink, true
        ).apply { setPadding(0, dp(12), 0, dp(5)) })
        stopStatus = label(
            "در این نسخه هیچ پردازشی با قفل‌کردن اپ متوقف نمی‌شود.",
            13f, muted
        )
        emergencyCard.addView(stopStatus)
        val unavailable = Button(this).apply {
            text = "توقف اضطراری — در دست ساخت"
            isEnabled = false
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(166, 174, 185), 14)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        emergencyCard.addView(unavailable, marginParams(top = 14))
        content.addView(emergencyCard, marginParams(top = 8))

        content.addView(sectionTitle("کنترل دسترسی"))
        val actions = card()
        lockButton = actionButton("قفل‌کردن کنترلر", accent) { setLocked(true) }
        unlockButton = actionButton("بازکردن با اثر انگشت یا الگو", green) {
            requestDeviceCredential()
        }
        actions.addView(lockButton, matchWrap())
        actions.addView(unlockButton, marginParams(top = 10))
        content.addView(actions, marginParams(top = 8))

        content.addView(sectionTitle("ابزارهای جانبی"))
        val tools = card()
        notificationButton = actionButton("ارسال اعلان آزمایشی", Color.rgb(83, 97, 119)) {
            if (!locked) requestNotificationOrPost()
        }
        tools.addView(notificationButton, matchWrap())
        content.addView(tools, marginParams(top = 8))

        content.addView(label(
            "نسخهٔ فعلی: قفل محلی و احراز هویت اندروید. توقف واقعی پردازش‌ها پس از تکمیل و آزمون لایهٔ امن Termux فعال خواهد شد.",
            12f, muted
        ).apply { setPadding(dp(5), dp(18), dp(5), 0) })

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(page)
            addView(content)
        })
    }

    private fun refreshState() {
        state.text = if (locked) "●  قفل محلی فعال است" else "●  قفل محلی غیرفعال است"
        state.setTextColor(if (locked) amber else green)
        lockButton.isEnabled = !locked
        unlockButton.isEnabled = locked
        notificationButton.isEnabled = !locked
        lockButton.alpha = if (locked) 0.45f else 1f
        unlockButton.alpha = if (locked) 1f else 0.45f
        notificationButton.alpha = if (locked) 0.45f else 1f
    }

    private fun setLocked(value: Boolean) {
        check(store().edit().putBoolean(KEY_LOCKED, value).commit())
        locked = value
        refreshState()
    }

    private fun requestDeviceCredential() {
        if (!locked) return
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) {
            state.text = "ابتدا PIN، الگو یا گذرواژهٔ قفل صفحهٔ گوشی را فعال کن."
            return
        }
        val intent: Intent = keyguard.createConfirmDeviceCredentialIntent(
            "تأیید هویت",
            "برای بازکردن قفل رایان، قفل صفحهٔ گوشی را تأیید کن."
        ) ?: run {
            state.text = "امکان نمایش تأیید هویت دستگاه وجود ندارد؛ قفل فعال می‌ماند."
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
            state.text = "●  احراز هویت لغو شد؛ قفل همچنان فعال است."
            state.setTextColor(amber)
        }
    }

    private fun requestNotificationOrPost() {
        if (locked) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } else postTestNotification()
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
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Rayan Controller")
            .setContentText("اعلان آزمایشی اپ مستقل کنترلر")
            .setAutoCancel(true)
            .build()
        manager.notify(7101, notification)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(17), dp(17), dp(17), dp(17))
        background = rounded(Color.WHITE, 18)
        elevation = dp(2).toFloat()
    }

    private fun sectionTitle(value: String) = label(value, 16f, ink, true).apply {
        setPadding(dp(3), dp(22), dp(3), 0)
    }

    private fun label(
        value: String, size: Float, color: Int, bold: Boolean = false
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.START
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun actionButton(
        title: String, color: Int, action: () -> Unit
    ) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        background = rounded(color, 14)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun wrapParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun marginParams(top: Int) = matchWrap().apply {
        topMargin = dp(top)
    }

    private fun store() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS = "rayan_controller"
        private const val KEY_LOCKED = "emergency_local_lock"
        private const val REQUEST_NOTIFICATIONS = 71
        private const val REQUEST_DEVICE_CREDENTIAL = 72
    }
}
