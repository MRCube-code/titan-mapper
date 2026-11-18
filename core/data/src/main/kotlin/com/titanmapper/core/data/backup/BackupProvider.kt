package com.titanmapper.core.data.backup

import android.content.Context
import android.net.Uri
import com.titanmapper.core.common.DataResult
import com.titanmapper.core.common.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Where a backup goes or comes from. For SAF providers this is a document Uri string. */
data class BackupTarget(val location: String)

/**
 * Pluggable backup destination (cloud backup architecture). Implement this for a specific
 * cloud API (Drive REST, WebDAV, S3, ...) and bind it in Hilt; the BackupManager doesn't care.
 */
interface BackupProvider {
    val id: String
    val displayName: String
    suspend fun upload(target: BackupTarget, data: ByteArray): DataResult<Unit>
    suspend fun download(source: BackupTarget): DataResult<ByteArray>
}

/**
 * Works with any storage app through the system document picker (ACTION_CREATE_DOCUMENT /
 * ACTION_OPEN_DOCUMENT): local storage, Google Drive, Dropbox, OneDrive, Nextcloud... with no
 * network permission or API key in Titan Mapper itself.
 */
@Singleton
class SafBackupProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher
) : BackupProvider {
    override val id = "saf"
    override val displayName = "File or cloud storage (system picker)"

    override suspend fun upload(target: BackupTarget, data: ByteArray): DataResult<Unit> = withContext(io) {
        try {
            val out = context.contentResolver.openOutputStream(Uri.parse(target.location), "wt")
                ?: return@withContext DataResult.Error("Couldn't open the destination")
            out.use { it.write(data) }
            DataResult.Success(Unit)
        } catch (e: IOException) {
            DataResult.Error("Couldn't write the backup", e)
        } catch (e: SecurityException) {
            DataResult.Error("Access to the destination was denied", e, recoverable = false)
        }
    }

    override suspend fun download(source: BackupTarget): DataResult<ByteArray> = withContext(io) {
        try {
            val input = context.contentResolver.openInputStream(Uri.parse(source.location))
                ?: return@withContext DataResult.Error("Couldn't open the backup file")
            input.use {
                val bytes = it.readNBytes(MAX_BYTES + 1)
                if (bytes.size > MAX_BYTES) DataResult.Error("Backup file is too large", recoverable = false)
                else DataResult.Success(bytes)
            }
        } catch (e: IOException) {
            DataResult.Error("Couldn't read the backup", e)
        } catch (e: SecurityException) {
            DataResult.Error("Access to the file was denied", e, recoverable = false)
        }
    }

    private companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
    }
}
