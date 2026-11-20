package com.titanmapper.feature.settings

import com.titanmapper.core.common.DataResult
import com.titanmapper.core.common.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

@Serializable
data class UpdateManifest(val versionCode: Int, val versionName: String, val notes: String, val url: String)

/**
 * Update system (Architecture: "no Titan-Mapper-operated server" principle applies here too —
 * the manifest URL is whatever the user configures, defaulting to none). Fetches a small JSON
 * manifest over HTTPS only, compares versionCode, and at most opens the release page in the
 * browser: it never downloads or installs anything on its own.
 */
class UpdateChecker @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
    private val json: Json
) {
    suspend fun check(manifestUrl: String, installedVersionCode: Int): DataResult<UpdateManifest?> = withContext(io) {
        if (manifestUrl.isBlank()) return@withContext DataResult.Success(null)
        val url = try {
            URL(manifestUrl)
        } catch (e: Exception) {
            return@withContext DataResult.Error("That update URL isn't valid", e)
        }
        if (url.protocol != "https") return@withContext DataResult.Error("Update URL must use HTTPS")

        var connection: HttpURLConnection? = null
        try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
            }
            if (connection.responseCode !in 200..299) {
                return@withContext DataResult.Error("Couldn't reach the update server (HTTP ${connection.responseCode})")
            }
            val bytes = connection.inputStream.use { it.readNBytes(MAX_BYTES + 1) }
            if (bytes.size > MAX_BYTES) return@withContext DataResult.Error("Update manifest is too large")
            val manifest = json.decodeFromString(UpdateManifest.serializer(), bytes.toString(Charsets.UTF_8))
            DataResult.Success(if (manifest.versionCode > installedVersionCode) manifest else null)
        } catch (e: Exception) {
            DataResult.Error("Couldn't check for updates", e)
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 8_000
        const val MAX_BYTES = 64 * 1024
    }
}
