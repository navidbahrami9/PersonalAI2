package com.personalai.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Downloads optional on-device speech models into private app storage.
 * Downloads are user-triggered, HTTPS-only, size-bounded and SHA-256 verified.
 */
object RayanModelStore {
    private data class ModelFile(
        val name: String,
        val url: String,
        val size: Long,
        val sha256: String
    )

    private val whisperFiles = listOf(
        ModelFile(
            "tiny-encoder.int8.onnx",
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/65176e2deb88badc814a94058666cadccc29b61c/tiny-encoder.int8.onnx",
            12_937_772L,
            "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434"
        ),
        ModelFile(
            "tiny-decoder.int8.onnx",
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/65176e2deb88badc814a94058666cadccc29b61c/tiny-decoder.int8.onnx",
            89_855_401L,
            "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925"
        ),
        ModelFile(
            "tiny-tokens.txt",
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/65176e2deb88badc814a94058666cadccc29b61c/tiny-tokens.txt",
            816_730L,
            "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"
        )
    )

    private val speakerFile = ModelFile(
        "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx",
        "https://huggingface.co/csukuangfj/speaker-embedding-models/resolve/0743f301363dec56491a490f6d6cbc9d67f9a3bf/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx",
        29_596_978L,
        "357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b"
    )

    fun whisperDirectory(context: Context): File =
        File(context.applicationContext.filesDir, "models/whisper-tiny")

    fun speakerModel(context: Context): File =
        File(context.applicationContext.filesDir, "models/${speakerFile.name}")

    fun hasWhisperFiles(context: Context): Boolean =
        whisperFiles.all {
            val file = File(whisperDirectory(context), it.name)
            file.isFile && file.length() == it.size
        }

    fun hasSpeakerFile(context: Context): Boolean {
        val file = speakerModel(context)
        return file.isFile && file.length() == speakerFile.size
    }

    fun isWhisperInstalled(context: Context): Boolean =
        whisperFiles.all { isValid(File(whisperDirectory(context), it.name), it) }

    fun isSpeakerInstalled(context: Context): Boolean =
        isValid(speakerModel(context), speakerFile)

    @Synchronized
    fun downloadWhisper(
        context: Context,
        onProgress: (String, Long, Long) -> Unit = { _, _, _ -> }
    ) {
        val directory = whisperDirectory(context)
        if (!directory.exists() && !directory.mkdirs()) {
            error("ساخت پوشهٔ مدل گفتار ممکن نشد.")
        }
        whisperFiles.forEach { file ->
            downloadIfNeeded(File(directory, file.name), file, onProgress)
        }
        check(isWhisperInstalled(context)) { "اعتبارسنجی مدل گفتار کامل نشد." }
    }

    @Synchronized
    fun downloadSpeaker(
        context: Context,
        onProgress: (String, Long, Long) -> Unit = { _, _, _ -> }
    ) {
        val target = speakerModel(context)
        if (!target.parentFile!!.exists() && !target.parentFile!!.mkdirs()) {
            error("ساخت پوشهٔ مدل تشخیص گوینده ممکن نشد.")
        }
        downloadIfNeeded(target, speakerFile, onProgress)
        check(isSpeakerInstalled(context)) { "اعتبارسنجی مدل تشخیص گوینده کامل نشد." }
    }

    private fun downloadIfNeeded(
        target: File,
        model: ModelFile,
        onProgress: (String, Long, Long) -> Unit
    ) {
        if (isValid(target, model)) {
            onProgress(model.name, model.size, model.size)
            return
        }

        target.parentFile?.let {
            if (!it.exists() && !it.mkdirs()) error("ساخت پوشهٔ ذخیره‌سازی ناموفق بود.")
        }
        val temporary = File(target.parentFile, target.name + ".download")
        temporary.delete()

        var connection: HttpURLConnection? = null
        try {
            val url = URL(model.url)
            require(url.protocol == "https") { "فقط دریافت امن HTTPS مجاز است." }
            connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 90_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "PersonalAI2/1.0")
                setRequestProperty("Accept", "application/octet-stream")
            }

            val response = connection.responseCode
            check(response in 200..299) { "دریافت مدل ناموفق بود: HTTP $response" }

            var total = 0L
            var lastProgress = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= model.size) { "حجم فایل دریافتی بیشتر از مقدار مورد انتظار است." }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        if (total == model.size || total - lastProgress >= 1_048_576L) {
                            lastProgress = total
                            onProgress(model.name, total, model.size)
                        }
                    }
                    output.fd.sync()
                }
            }

            check(total == model.size) { "حجم فایل مدل با مقدار معتبر مطابقت ندارد." }
            val actualHash = digest.digest().joinToString("") {
                "%02x".format(Locale.US, it.toInt() and 0xff)
            }
            check(actualHash == model.sha256) { "هش SHA-256 مدل معتبر نیست." }

            try {
                java.nio.file.Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                java.nio.file.Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
            check(isValid(target, model)) { "بررسی نهایی مدل ناموفق بود." }
        } finally {
            connection?.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun isValid(file: File, model: ModelFile): Boolean {
        if (!file.isFile || file.length() != model.size) return false
        return runCatching { sha256(file) == model.sha256 }.getOrDefault(false)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") {
            "%02x".format(Locale.US, it.toInt() and 0xff)
        }
    }
}
