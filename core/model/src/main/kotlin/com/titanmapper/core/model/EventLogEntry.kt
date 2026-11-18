package com.titanmapper.core.model

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** One row of the diagnostics event log (ring-buffered in Room; see EventLogDao.trim). */
data class EventLogEntry(
    val id: Long = 0,
    val timestampMillis: Long,
    val level: LogLevel,
    val category: String,
    val message: String,
    val deviceName: String? = null,
    val latencyNanos: Long? = null
)

/** A device the app has seen, plus its user-facing state. */
data class DeviceRecord(
    val fingerprint: DeviceFingerprint,
    val nickname: String? = null,
    val connected: Boolean = false,
    val lastSeenMillis: Long = 0L
) {
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: fingerprint.deviceName
}
