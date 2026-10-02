package com.personalai.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            PersonalAIApp()
        }
    }
}

data class Msg(
    val role: String,
    val text: String
)

@Composable
fun PersonalAIApp() {

    val scope = rememberCoroutineScope()

    var input by remember {
        mutableStateOf("")
    }

    var busy by remember {
        mutableStateOf(false)
    }

    val messages = remember {

        mutableStateListOf(
            Msg(
                "assistant",
                "سلام! من نسخه اول دستیار شخصی تو هستم.\n\nچه کمکی از دستم برمیاد؟"
            )
        )
    }

    MaterialTheme {

        Scaffold(

            topBar = {
                TopAppBar(
                    title = {
                        Text("دستیار شخصی")
                    }
                )
            }

        ) { pad ->

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(12.dp)
            ) {

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {

                    items(messages) { message ->

                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {

                            Text(
                                text =
                                    if (message.role == "user") {
                                        "شما: ${message.text}"
                                    } else {
                                        "دستیار: ${message.text}"
                                    },

                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                        }
                    )

                    Button(

                        enabled = input.isNotBlank() && !busy,

                        onClick = {

                            val question = input

                            input = ""

                            messages.add(
                                Msg("user", question)
                            )

                            busy = true

                            scope.launch(Dispatchers.IO) {

                                val answer = try {

                                    Api.chat(question)

                                } catch (e: Exception) {

                                    "خطا در ارتباط با سرور:\n${e.message}"
                                }

                                launch(Dispatchers.Main) {

                                    messages.add(
                                        Msg("assistant", answer)
                                    )

                                    busy = false
                                }
                            }
                        }

                    ) {

                        Text("ارسال")
                    }
                }
            }
        }
    }
}

object Api {

    /*
     * فعلاً آدرس سرور توسعه.
     *
     * اگر سرور روی کامپیوتر خودت اجرا شود،
     * 10.0.2.2 برای شبیه‌ساز اندروید استفاده می‌شود.
     */

    private const val BASE =
        "http://10.0.2.2:8000"

    fun chat(message: String): String {

        val url =
            URL("$BASE/chat")

        val connection =
            (url.openConnection() as HttpURLConnection).apply {

                requestMethod = "POST"

                doOutput = true

                setRequestProperty(
                    "Content-Type",
                    "application/json"
                )
            }

        val body =
            """{"message":${json(message)}}"""

        connection.outputStream.use {

            it.write(
                body.toByteArray()
            )
        }

        val stream =
            if (connection.responseCode in 200..299) {

                connection.inputStream

            } else {

                connection.errorStream
            }

        val result =
            stream.bufferedReader().readText()

        return Regex(
            """"answer"\s*:\s*"((?:\\.|[^"])*)""""
        )
            .find(result)
            ?.groupValues
            ?.get(1)
            ?.replace("\\n", "\n")
            ?.replace("\\\"", "\"")
            ?: result
    }

    private fun json(s: String): String {

        return "\"" +
                s
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r") +
                "\""
    }
}
