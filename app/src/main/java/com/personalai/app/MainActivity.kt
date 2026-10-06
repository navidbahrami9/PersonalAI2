@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.personalai.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

    // 🔴 تغییر: ذخیره پیام‌ها برای حفظ چت هنگام چرخاندن گوشی
    val messages = rememberSaveable(
        saver = listSaver(
            save = { list ->
                list.map { msg ->
                    listOf(msg.role, msg.text)
                }
            },
            restore = { saved ->
                mutableStateListOf(
                    *saved.map { item ->
                        Msg(
                            role = item[0],
                            text = item[1]
                        )
                    }.toTypedArray()
                )
            }
        )
    ) {
        mutableStateListOf(
            Msg(
                "assistant",
                "سلام! من نسخه اول دستیار شخصی تو هستم.\nهر چیزی می‌خواهی بپرس."
            )
        )
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

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                items(
                    items = messages,
                    key = { msg ->
                        "${msg.role}_${msg.text}_${messages.indexOf(msg)}"
                    }
                ) { msg ->

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
                    enabled = !sending
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

                            // 🔴 تغییر: ارسال کل تاریخچه مکالمه
                            val answer = Api.chat(
                                messages.toList()
                            )

                            messages.add(
                                Msg("assistant", answer)
                            )

                            sending = false
                        }
                    },
                    enabled = !sending
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

                for (msg in messages) {

                    val message = JSONObject()

                    message.put(
                        "role",
                        if (msg.role == "user") {
                            "user"
                        } else {
                            "assistant"
                        }
                    )

                    message.put("content", msg.text)

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
                    64
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

            // 🔴 تغییر: فقط اگر content خالی بود،
            // reasoning به عنوان آخرین راه استفاده می‌شود.
            val reasoning =
                message
                    .optString("reasoning_content", "")
                    .trim()

            if (reasoning.isNotEmpty()) {
                return reasoning
            }

            "پاسخ خالی از مدل دریافت شد."

        } catch (e: Exception) {

            "خطا در خواندن پاسخ مدل\n" +
                    "${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
