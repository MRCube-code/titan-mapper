package com.titanmapper.core.data.backup

import com.titanmapper.core.common.DataResult
import com.titanmapper.core.common.DefaultDispatcher
import com.titanmapper.core.data.transfer.ImportSummary
import com.titanmapper.core.data.transfer.ProfileTransfer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

/** Creates and restores full backups (all profiles, macros, layouts), optionally passphrase-encrypted. */
@Singleton
class BackupManager @Inject constructor(
    private val transfer: ProfileTransfer,
    private val provider: SafBackupProvider,
    @DefaultDispatcher private val cpu: CoroutineDispatcher
) {
    suspend fun createBackup(passphrase: CharArray?): DataResult<ByteArray> = withContext(cpu) {
        try {
            val plain = transfer.encode(transfer.buildExport(null)).toByteArray(Charsets.UTF_8)
            DataResult.Success(if (passphrase != null && passphrase.isNotEmpty()) PassphraseCrypto.encrypt(plain, passphrase) else plain)
        } catch (e: GeneralSecurityException) {
            DataResult.Error("Couldn't encrypt the backup", e, recoverable = false)
        } catch (e: Exception) {
            DataResult.Error("Couldn't create the backup", e)
        }
    }

    fun needsPassphrase(data: ByteArray): Boolean = PassphraseCrypto.isEncrypted(data)

    suspend fun restore(data: ByteArray, passphrase: CharArray?): DataResult<ImportSummary> = withContext(cpu) {
        val plain = if (PassphraseCrypto.isEncrypted(data)) {
            if (passphrase == null || passphrase.isEmpty()) return@withContext DataResult.Error("This backup is encrypted. Enter its passphrase.")
            try {
                PassphraseCrypto.decrypt(data, passphrase)
            } catch (e: GeneralSecurityException) {
                return@withContext DataResult.Error("Wrong passphrase, or the backup is damaged", e)
            }
        } else data
        when (val decoded = transfer.decode(plain.toString(Charsets.UTF_8))) {
            is DataResult.Error -> decoded
            is DataResult.Success -> transfer.import(decoded.value)
        }
    }

    suspend fun backupTo(target: BackupTarget, passphrase: CharArray?): DataResult<Unit> =
        when (val data = createBackup(passphrase)) {
            is DataResult.Error -> data
            is DataResult.Success -> provider.upload(target, data.value)
        }

    suspend fun restoreFrom(source: BackupTarget, passphrase: CharArray?): DataResult<ImportSummary> =
        when (val data = provider.download(source)) {
            is DataResult.Error -> data
            is DataResult.Success -> restore(data.value, passphrase)
        }
}
