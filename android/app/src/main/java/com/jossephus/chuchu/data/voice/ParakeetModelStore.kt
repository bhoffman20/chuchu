package com.jossephus.chuchu.data.voice

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

class ParakeetModelStore(context: Context) {
    companion object {
        private const val TAG = "ParakeetModelStore"
        private const val IO_BUFFER_SIZE = 256 * 1024
        private const val STATUS_UPDATE_MIN_INTERVAL_MS = 150L
    }
    private val appContext = context.applicationContext
    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()
    private val _status = MutableStateFlow<InstallStatus>(InstallStatus.Idle)
    val status: StateFlow<InstallStatus> = _status.asStateFlow()

    sealed interface InstallStatus {
        data object Idle : InstallStatus
        data class Downloading(val progress: Float?) : InstallStatus
        data class Installing(val progress: Float?) : InstallStatus
    }

    fun isInstalled(model: VoiceModel = VoiceModels.parakeetV2): Boolean {
        val rootDir = installedRoot(model)
        return requiredFiles(model).all { it.exists() } && rootDir.exists()
    }

    suspend fun download(model: VoiceModel = VoiceModels.parakeetV2): Result<Unit> =
        withContext(Dispatchers.IO) {
            val partFile = File(cacheRoot(), "${model.id}.tar.bz2.part")
            val tempInstallDir = File(filesRoot(), "${model.id}.tmp")
            runCatching {
                val finalInstallDir = installedRoot(model)
                val installStartNs = SystemClock.elapsedRealtimeNanos()

                partFile.parentFile?.mkdirs()
                filesRoot().mkdirs()
                deleteRecursivelyIfExists(tempInstallDir)

                _status.value = InstallStatus.Downloading(progress = 0f)
                val downloadStartNs = SystemClock.elapsedRealtimeNanos()
                downloadToPartFile(model, partFile)
                logPhaseDuration(model.id, "download", downloadStartNs)
                _status.value = InstallStatus.Installing(progress = 0f)

                val checksumStartNs = SystemClock.elapsedRealtimeNanos()
                verifySha256(partFile, model.sha256)
                logPhaseDuration(model.id, "checksum", checksumStartNs)

                val extractStartNs = SystemClock.elapsedRealtimeNanos()
                extractTarBz2(partFile, tempInstallDir, model.downloadSizeBytes)
                logPhaseDuration(model.id, "extract", extractStartNs)

                val validateStartNs = SystemClock.elapsedRealtimeNanos()
                validateModelFiles(model, tempInstallDir)
                logPhaseDuration(model.id, "validate", validateStartNs)

                val renameStartNs = SystemClock.elapsedRealtimeNanos()
                deleteRecursivelyIfExists(finalInstallDir)
                if (!tempInstallDir.renameTo(finalInstallDir)) {
                    throw IllegalStateException("Failed to install voice model")
                }
                logPhaseDuration(model.id, "rename", renameStartNs)
                partFile.delete()
                logPhaseDuration(model.id, "total", installStartNs)
                Unit
            }.onFailure {
                deleteRecursivelyIfExists(tempInstallDir)
                partFile.delete()
                if (it is java.util.concurrent.CancellationException) {
                    Log.i(TAG, "Install cancelled; cleaned temp files for ${model.id}")
                }
            }.also {
                _downloadProgress.value = null
                _status.value = InstallStatus.Idle
            }
        }

    fun delete(model: VoiceModel = VoiceModels.parakeetV2) {
        deleteRecursivelyIfExists(installedRoot(model))
        deleteRecursivelyIfExists(File(filesRoot(), "${model.id}.tmp"))
    }

    fun installedModelDir(model: VoiceModel = VoiceModels.parakeetV2): File? {
        if (!isInstalled(model)) return null
        return File(installedRoot(model), model.internalDir)
    }

    private suspend fun downloadToPartFile(model: VoiceModel, partFile: File) {
        val connection = URL(model.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.requestMethod = "GET"
        connection.connect()
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("Failed to download model (${connection.responseCode})")
        }

        val contentLength = connection.contentLengthLong.takeIf { it > 0L }
        connection.inputStream.use { rawInput ->
            BufferedInputStream(rawInput, IO_BUFFER_SIZE).use { input ->
                BufferedOutputStream(FileOutputStream(partFile), IO_BUFFER_SIZE).use { output ->
                val buffer = ByteArray(IO_BUFFER_SIZE)
                var totalRead = 0L
                var lastProgressEmitMs = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    totalRead += read
                    val nowMs = SystemClock.elapsedRealtime()
                    if (nowMs - lastProgressEmitMs >= STATUS_UPDATE_MIN_INTERVAL_MS) {
                        _downloadProgress.value =
                            contentLength?.let { len -> (totalRead.toFloat() / len.toFloat()).coerceIn(0f, 1f) }
                        _status.value = InstallStatus.Downloading(progress = _downloadProgress.value)
                        lastProgressEmitMs = nowMs
                    }
                }
                _downloadProgress.value = contentLength?.let { len -> (totalRead.toFloat() / len.toFloat()).coerceIn(0f, 1f) }
                _status.value = InstallStatus.Downloading(progress = _downloadProgress.value)
                }
            }
        }
        connection.disconnect()
    }

    private suspend fun verifySha256(file: File, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { rawInput ->
            BufferedInputStream(rawInput, IO_BUFFER_SIZE).use { input ->
            val buffer = ByteArray(IO_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
            }
        }
        val actual = digest.digest().joinToString(separator = "") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            throw IllegalStateException("Voice model checksum mismatch")
        }
    }

    private suspend fun extractTarBz2(archiveFile: File, outputDir: File, compressedBytesExpected: Long) {
        outputDir.mkdirs()
        var lastProgressEmitMs = 0L
        CountingFileInputStream(archiveFile).use { countingInput ->
            BufferedInputStream(countingInput, IO_BUFFER_SIZE).use { bufferedInput ->
            BZip2CompressorInputStream(bufferedInput).use { bzInput ->
                TarArchiveInputStream(bzInput).use { tarInput ->
                    val buffer = ByteArray(IO_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = tarInput.nextTarEntry ?: break
                        val output = safeResolve(outputDir, entry.name)
                        if (entry.isDirectory) {
                            output.mkdirs()
                            continue
                        }
                        output.parentFile?.mkdirs()
                        BufferedOutputStream(FileOutputStream(output), IO_BUFFER_SIZE).use { out ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = tarInput.read(buffer)
                                if (read <= 0) break
                                out.write(buffer, 0, read)
                                val nowMs = SystemClock.elapsedRealtime()
                                if (nowMs - lastProgressEmitMs >= STATUS_UPDATE_MIN_INTERVAL_MS) {
                                    val progress =
                                        if (compressedBytesExpected > 0L) {
                                            (countingInput.bytesRead.toFloat() / compressedBytesExpected.toFloat()).coerceIn(0f, 1f)
                                        } else {
                                            null
                                        }
                                    _status.value = InstallStatus.Installing(progress = progress)
                                    lastProgressEmitMs = nowMs
                                }
                            }
                        }
                    }
                }
            }
            }
        }
        _status.value = InstallStatus.Installing(progress = 1f)
    }

    private fun validateModelFiles(model: VoiceModel, outputDir: File) {
        val root = File(outputDir, model.internalDir)
        val required = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
        if (!required.all { File(root, it).exists() }) {
            throw IllegalStateException("Voice model archive is missing required files")
        }
    }

    private fun requiredFiles(model: VoiceModel): List<File> {
        val root = File(installedRoot(model), model.internalDir)
        return listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt").map { File(root, it) }
    }

    private fun safeResolve(baseDir: File, entryName: String): File {
        val file = File(baseDir, entryName)
        val basePath = baseDir.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        if (!filePath.startsWith(basePath)) {
            throw IllegalStateException("Blocked unsafe archive entry")
        }
        return file
    }

    private fun deleteRecursivelyIfExists(file: File) {
        if (file.exists()) file.deleteRecursively()
    }

    private fun logPhaseDuration(modelId: String, phase: String, startNs: Long) {
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000
        Log.i(TAG, "model=$modelId phase=$phase duration_ms=$elapsedMs")
    }

    private class CountingFileInputStream(file: File) : FileInputStream(file) {
        var bytesRead: Long = 0L
            private set

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) bytesRead += 1
            return value
        }

        override fun read(b: ByteArray): Int {
            val read = super.read(b)
            if (read > 0) bytesRead += read.toLong()
            return read
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = super.read(b, off, len)
            if (read > 0) bytesRead += read.toLong()
            return read
        }
    }

    private fun cacheRoot(): File = File(appContext.cacheDir, "voice-models")

    private fun filesRoot(): File = File(appContext.filesDir, "voice-models")

    private fun installedRoot(model: VoiceModel): File = File(filesRoot(), model.id)
}
