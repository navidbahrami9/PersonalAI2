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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    val messages = remember {
        mutableStateListOf(
            Msg(
                "assistant",
                "سلام! من نسخه اول دستیار شخصی تو هستم.\nهر چیزی می‌خواهی بپرس."
            )
        )
    }

    var input by remember {
        mutableStateOf("")
    }

    var sending by remember {
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
                            Msg(
                                "user",
                                question
                            )
                        )

                        sending = true

                        scope.launch {

                            val answer = Api.chat(question)

                            messages.add(
                                Msg(
                                    "assistant",
                                    answer
                                )
                            )

                            sending = false
                        }
                    },
                    enabled = !sending
                ) {
                    Text(
                        if (sending) "..." else "ارسال"
                    )
                }
            }
        }
    }
}

object Api {

    private const val BASE =
        "http://127.0.0.1:8000/"

    suspend fun chat(question: String): String {

        return withContext(Dispatchers.IO) {

            var connection: HttpURLConnection? = null

            try {

                val url = URL(
                    BASE + "v1/chat/completions"
                )

                connection =
                    url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"

                connection.connectTimeout = 15_000

                // Qwen روی گوشی ممکن است زمان بیشتری برای تولید پاسخ بخواهد.
                connection.readTimeout = 120_000

                connection.doOutput = true

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=UTF-8"
                )

                connection.setRequestProperty(
                    "Accept",
                    "application/json"
                )

                val escapedQuestion =
                    question
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")

                val json = """
                    {
                      "messages": [
                        {
                          "role": "user",
                          "content": "$escapedQuestion"
                        }
                      ],
                      "temperature": 0.7,
                      "max_tokens": 256,
                      "stream": false
                    }
                """.trimIndent()

                connection.outputStream.use { output ->

                    output.write(
                        json.toByteArray(Charsets.UTF_8)
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

                "خطا در ارتباط با سرور\n${e.javaClass.simpleName}: ${e.message}"

            } finally {

                connection?.disconnect()
            }
        }
    }

    private fun extractAnswer(json: String): String {

        return try {

            val root =
                JSONObject(json)

            val choices =
                root.getJSONArray("choices")

            if (choices.length() == 0) {
                return "مدل پاسخی برنگرداند."
            }

            val message =
                choices
                    .getJSONObject(0)
                    .getJSONObject("message")

            // حالت عادی OpenAI-compatible
            val content =
                message.optString("content", "").trim()

            if (content.isNotEmpty()) {
                return content
            }

            // Qwen3 ممکن است پاسخ را اینجا قرار دهد.
            val reasoning =
                message
                    .optString("reasoning_content", "")
                    .trim()

            if (reasoning.isNotEmpty()) {
                return reasoning
            }

            "پاسخ خالی از مدل دریافت شد."

        } catch (e: Exception) {

            "خطا در خواندن پاسخ مدل\n${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
