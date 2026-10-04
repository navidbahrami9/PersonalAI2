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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
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

    val messages = remember {
        mutableStateListOf(
            Msg(
                "دستیار",
                "سلام! من نسخه اول دستیار شخصی تو هستم. 🤖"
            )
        )
    }

    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
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
                                text = msg.role,
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
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text("پیامت را بنویس...")
                    },
                    enabled = !sending
                )

                Button(
                    onClick = {

                        val question = input.trim()

                        if (question.isEmpty() || sending) {
                            return@Button
                        }

                        messages.add(
                            Msg("شما", question)
                        )

                        input = ""
                        sending = true

                        scope.launch {

                            val answer = Api.chat(question)

                            messages.add(
                                Msg("دستیار", answer)
                            )

                            sending = false
                        }
                    },
                    enabled = !sending
                ) {
                    Text("ارسال")
                }
            }
        }
    }
}

object Api {

    /*
     * llama-server روی همین گوشی اجرا می‌شود.
     * بنابراین 127.0.0.1 یعنی همین گوشی.
     */
    private const val BASE =
        "http://127.0.0.1:8000/"

    suspend fun chat(question: String): String {

        return try {

            val url = URL(BASE + "v1/chat/completions")

            val connection =
                url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 120000

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            connection.doOutput = true

            val json = """
                {
                    "messages": [
                        {
                            "role": "user",
                            "content": ${jsonString(question)}
                        }
                    ],
                    "temperature": 0.7,
                    "max_tokens": 512
                }
            """.trimIndent()

            connection.outputStream.use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode

            val stream =
                if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val response =
                stream.bufferedReader().use {
                    it.readText()
                }

            connection.disconnect()

            if (responseCode !in 200..299) {
                "خطای سرور: HTTP $responseCode\n$response"
            } else {
                extractContent(response)
            }

        } catch (e: Exception) {

            "خطا در ارتباط با سرور محلی:\n${e.message}"
        }
    }

    private fun jsonString(value: String): String {

        return "\"" +
                value
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t") +
                "\""
    }

    private fun extractContent(json: String): String {

        val marker = "\"content\":\""

        val start = json.indexOf(marker)

        if (start == -1) {
            return json
        }

        val contentStart = start + marker.length

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
                    else -> result.append(c)
                }

                escaped = false

            } else {

                when (c) {

                    '\\' -> escaped = true

                    '"' -> break

                    else -> result.append(c)
                }
            }

            i++
        }

        return result.toString()
    }
}
