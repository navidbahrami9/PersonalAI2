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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

@androidx.compose.runtime.Composable
fun PersonalAIApp() {

    var messages by remember {
        mutableStateOf(
            listOf(
                Msg(
                    "assistant",
                    "سلام! من نسخه اول دستیار شخصی تو هستم.\nهر چیزی می‌خواهی بپرس."
                )
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

                items(messages) { msg ->

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

                        messages = messages + Msg(
                            "user",
                            question
                        )

                        sending = true

                        scope.launch {

                            val answer = Api.chat(question)

                            messages = messages + Msg(
                                "assistant",
                                answer
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

                connection.connectTimeout = 10_000
                connection.readTimeout = 30_000

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
                      "max_tokens": 128,
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

                        return@withContext "خطای سرور: HTTP $responseCode\n$errorText"
                    }

                extractContent(responseText)

            } catch (e: Exception) {

                "خطا در ارتباط با سرور\n${e.javaClass.simpleName}: ${e.message}"

            } finally {

                connection?.disconnect()
            }
        }
    }

    private fun extractContent(json: String): String {

        val marker = "\"content\":\""

        val start = json.indexOf(marker)

        if (start == -1) {
            return "پاسخ نامعتبر از سرور:\n$json"
        }

        val contentStart =
            start + marker.length

        val result = StringBuilder()

        var escaped = false
        var i = contentStart

        while (i < json.length) {

            val c = json[i]

            if (escaped) {

                when (c) {

                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    '"' -> result.append('"')
                    '\\' -> result.append('\\')

                    else -> {
                        result.append('\\')
                        result.append(c)
                    }
                }

                escaped = false

            } else {

                when (c) {

                    '\\' -> {
                        escaped = true
                    }

                    '"' -> {
                        break
                    }

                    else -> {
                        result.append(c)
                    }
                }
            }

            i++
        }

        return result.toString()
            .trim()
            .ifEmpty {
                "پاسخ خالی از مدل دریافت شد."
            }
    }
}
