@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.personalai.app

import android.os.Bundle
import android.content.Intent
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                PersonalAIApp()
            }
        }
    }
}

data class Msg(
    val role: String,
    val text: String
)

@Composable
fun PersonalAIApp() {

    val context = LocalContext.current
    val messages = remember { mutableStateListOf<Msg>() }
    var historyLoaded by remember { mutableStateOf(false) }
    var historyError by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            runCatching { RayanConversationArchive.readAll(context) }
        }
        result.onSuccess { archived ->
            messages.clear()
            if (archived.isEmpty()) {
                messages.add(
                    Msg(
                        "assistant",
                        "سلام! من نسخه اول دستیار شخصی تو هستم.\nهر چیزی می‌خواهی بپرس."
                    )
                )
            } else {
                messages.addAll(archived.map { Msg(it.role, it.text) })
            }
        }.onFailure { error ->
            historyError = "بازیابی تاریخچه ناموفق بود: ${error.message ?: error.javaClass.simpleName}"
            messages.clear()
            messages.add(Msg("assistant", "تاریخچه قابل بازیابی نیست؛ برای جلوگیری از بازنویسی، ارسال پیام موقتاً غیرفعال است."))
        }
        historyLoaded = result.isSuccess
    }

    var input by rememberSaveable {
        mutableStateOf("")
    }

    var sending by rememberSaveable {
        mutableStateOf(false)
    }

    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("دستیار شخصی")
                }
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
        ) {

            Button(
                onClick = {
                    context.startActivity(Intent(context, RayanVoiceControlActivity::class.java))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("کنترل صوتی رایان")
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                itemsIndexed(
                    items = messages,
                    key = { index, msg -> "${msg.role}_$index" }
                ) { _, msg ->

                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {

                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {

                            Text(
                                text = if (msg.role == "user") {
                                    "شما"
                                } else {
                                    "دستیار"
                                },
                                style = MaterialTheme.typography.labelLarge
                            )

                            Text(
                                text = msg.text,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text("پیامت را بنویس...")
                    },
                    singleLine = true,
                    enabled = !sending && historyLoaded
                )

                Button(
                    onClick = {

                        val question = input.trim()

                        if (question.isEmpty() || sending) {
                            return@Button
                        }

                        input = ""

                        messages.add(
                            Msg("user", question)
                        )

                        sending = true

                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    RayanConversationArchive.append(
                                        context = context,
                                        role = "user",
                                        text = question,
                                        origin = "typed",
                                        status = "sent"
                                    )
                                }

                                val answer = Api.chat(context, messages.toList())
                                messages.add(Msg("assistant", answer))

                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        RayanConversationArchive.append(
                                            context = context,
                                            role = "assistant",
                                            text = answer,
                                            origin = "assistant",
                                            status = "complete"
                                        )
                                    }
                                }.onFailure { error ->
                                    messages.add(
                                        Msg("assistant", "هشدار: پاسخ دریافت شد، اما ذخیرهٔ آن در آرشیو ناموفق بود: ${error.message ?: error.javaClass.simpleName}")
                                    )
                                }
                            } catch (error: Exception) {
                                messages.add(
                                    Msg("assistant", "ارسال پیام ناموفق بود: ${error.message ?: error.javaClass.simpleName}")
                                )
                            } finally {
                                sending = false
                            }
                        }
                    },
                    enabled = !sending && historyLoaded
                ) {

                    Text(
                        if (sending) {
                            "..."
                        } else {
                            "ارسال"
                        }
                    )
                }
            }
        }
    }
}

object Api {

    private const val BASE =
        "http://127.0.0.1:8000/"

    // 🔴 تغییر: دستور ثابت برای پاسخ فارسی
    private const val SYSTEM_PROMPT =
        "تو دستیار شخصی من هستی. " +
        "همیشه به زبان فارسی پاسخ بده. " +
        "پاسخ را مستقیم، طبیعی و کوتاه ارائه کن. " +
        "متن فکر کردن یا reasoning داخلی خودت را نمایش نده."

    suspend fun chat(
        context: Context,
        messages: List<Msg>
    ): String {

        return withContext(Dispatchers.IO) {

            var connection: HttpURLConnection? = null

            try {

                val url =
                    URL(BASE + "v1/chat/completions")

                connection =
                    url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"

                connection.connectTimeout = 30_000

                // 🔴 تغییر: حداکثر ۳ دقیقه برای پاسخ مدل
                connection.readTimeout = 180_000

                connection.doOutput = true

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=UTF-8"
                )

                connection.setRequestProperty(
                    "Accept",
                    "application/json"
                )

                // 🔴 تغییر: ساخت JSON با JSONArray/JSONObject
                // تا مشکل Escape شدن متن فارسی و علامت‌ها کمتر شود.
                val jsonMessages = JSONArray()

                val systemMessage = JSONObject()
                systemMessage.put("role", "system")
                systemMessage.put("content", SYSTEM_PROMPT)
                jsonMessages.put(systemMessage)

                // Keep the encrypted archive complete, but send only a bounded recent window.
                // The local Qwen server is configured with a 1024-token context.
                val requestHistory = boundedHistory(messages)
                val latestUserIndex = requestHistory.indexOfLast { it.role == "user" }

                for ((index, msg) in requestHistory.withIndex()) {

                    val message = JSONObject()

                    message.put(
                        "role",
                        if (msg.role == "user") {
                            "user"
                        } else {
                            "assistant"
                        }
                    )

                    val content = if (index == latestUserIndex) {
                        RayanMemoryContext.withMemory(context, msg.text)
                    } else {
                        msg.text
                    }
                    message.put("content", content)

                    jsonMessages.put(message)
                }

                val request = JSONObject()

                request.put(
                    "messages",
                    jsonMessages
                )

                request.put(
                    "temperature",
                    0.7
                )

                // 🔴 تغییر: پاسخ کوتاه‌تر و سریع‌تر
                request.put(
                    "max_tokens",
                    256
                )

                request.put(
                    "stream",
                    false
                )

                connection.outputStream.use { output ->

                    output.write(
                        request
                            .toString()
                            .toByteArray(Charsets.UTF_8)
                    )

                    output.flush()
                }

                val responseCode =
                    connection.responseCode

                val responseText =
                    if (responseCode in 200..299) {

                        connection.inputStream
                            .bufferedReader(Charsets.UTF_8)
                            .use {
                                it.readText()
                            }

                    } else {

                        val errorText =
                            connection.errorStream
                                ?.bufferedReader(Charsets.UTF_8)
                                ?.use {
                                    it.readText()
                                }
                                ?: ""

                        return@withContext(
                            "خطای سرور: HTTP $responseCode\n$errorText"
                        )
                    }

                extractAnswer(responseText)

            } catch (e: Exception) {

                // 🔴 تغییر: خطای شبکه به متن تبدیل می‌شود
                // و نباید باعث بسته شدن برنامه شود.
                "خطا در ارتباط با سرور\n" +
                        "${e.javaClass.simpleName}: ${e.message}"

            } finally {

                connection?.disconnect()
            }
        }
    }

    private const val MAX_HISTORY_MESSAGES = 8
    private const val MAX_HISTORY_CHARS = 1800

    /**
     * Preserve the newest context while preventing an ever-growing archive
     * from overflowing the local model's small context window.
     */
    private fun boundedHistory(messages: List<Msg>): List<Msg> {
        var remaining = MAX_HISTORY_CHARS
        val newestFirst = ArrayList<Msg>()

        for (message in messages.asReversed().take(MAX_HISTORY_MESSAGES)) {
            if (remaining <= 0) break
            val boundedText = message.text.take(remaining)
            if (boundedText.isNotEmpty()) {
                newestFirst.add(message.copy(text = boundedText))
                remaining -= boundedText.length
            }
        }

        return newestFirst.asReversed()
    }

    private fun extractAnswer(
        json: String
    ): String {

        return try {

            val root =
                JSONObject(json)

            val choices =
                root.optJSONArray("choices")

            if (choices == null || choices.length() == 0) {
                return "مدل پاسخی برنگرداند."
            }

            val message =
                choices
                    .optJSONObject(0)
                    ?.optJSONObject("message")

            if (message == null) {
                return "پاسخ مدل قابل خواندن نبود."
            }

            val content =
                message
                    .optString("content", "")
                    .trim()

            if (content.isNotEmpty()) {
                return content
            }

            "پاسخ متنی از مدل دریافت نشد."

        } catch (e: Exception) {

            "خطا در خواندن پاسخ مدل\n" +
                    "${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
