package com.vaishnavi.theweatherwithin

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

sealed class ModelDownloadState {
    object Idle : ModelDownloadState()
    data class Downloading(val progress: Float) : ModelDownloadState()
    object Success : ModelDownloadState()
    data class Failed(val message: String) : ModelDownloadState()
}

/**
 * Locates, downloads, and verifies the on-device LLM model file.
 *
 * Targets Phi-4-mini-instruct — validated against the real test harness
 * (F1 72%, 100% "good days left alone," matching the original Gemma 4B
 * spike exactly). Non-gated, so this is a genuine background download —
 * no Hugging Face login, no license click.
 *
 * As of Session 11: the actual download now runs inside
 * ModelDownloadService (a real Foreground Service), not tied to any
 * screen's lifecycle. downloadState is the shared source of truth both
 * the Service and the UI observe.
 */
object ModelManager {

    const val MODEL_FILENAME = "phi4-mini-instruct.litertlm"
    const val MODEL_SIZE_BYTES = 3_910_000_000L

    private const val MODEL_URL =
        "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm?download=true"

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    fun modelFile(context: Context): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, MODEL_FILENAME)
    }

    fun isModelAvailable(context: Context): Boolean {
        val file = modelFile(context)
        return file.exists() && file.length() > 1_000_000_000L
    }

    suspend fun downloadModel(context: Context): Result<Unit> {
        _downloadState.value = ModelDownloadState.Downloading(0f)
        val target = modelFile(context)
        val tempFile = File(target.parentFile, "${target.name}.part")

        return try {
            val url = URL(MODEL_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.connect()

            if (connection.responseCode !in 200..299) {
                connection.disconnect()
                val msg = "Server returned ${connection.responseCode} — the model may have moved."
                _downloadState.value = ModelDownloadState.Failed(msg)
                return Result.failure(Exception(msg))
            }

            val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: MODEL_SIZE_BYTES
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastReportedPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                        if (percent != lastReportedPercent) {
                            _downloadState.value = ModelDownloadState.Downloading(
                                downloadedBytes.toFloat() / totalBytes.toFloat()
                            )
                            lastReportedPercent = percent
                        }
                    }
                }
            }
            connection.disconnect()

            if (tempFile.length() < 1_000_000_000L) {
                tempFile.delete()
                val msg = "Download finished but the file looks incomplete — try again."
                _downloadState.value = ModelDownloadState.Failed(msg)
                return Result.failure(Exception(msg))
            }

            if (target.exists()) target.delete()
            tempFile.renameTo(target)
            _downloadState.value = ModelDownloadState.Success
            Result.success(Unit)
        } catch (e: Exception) {
            tempFile.delete()
            val msg = e.message ?: "Unknown error"
            _downloadState.value = ModelDownloadState.Failed(msg)
            Result.failure(e)
        }
    }

    /** Lets the UI clear an old failure/success state before starting a fresh attempt. */
    fun resetState() {
        _downloadState.value = ModelDownloadState.Idle
    }
}