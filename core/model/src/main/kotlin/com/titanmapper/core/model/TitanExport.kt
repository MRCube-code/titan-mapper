package com.titanmapper.core.model

import kotlinx.serialization.Serializable

/** Everything that can travel in a `.titanprofile` / backup file. */
@Serializable
data class TitanExport(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val createdAtMillis: Long,
    val profiles: List<MappingProfile> = emptyList(),
    val macros: List<MacroDefinition> = emptyList(),
    val layouts: List<OverlayLayout> = emptyList()
) {
    companion object {
        const val CURRENT_FORMAT_VERSION = 1
    }
}

/**
 * Outer wrapper. [sha256] is an integrity checksum over [payload] that detects corruption and
 * accidental edits. It is NOT an authenticity guarantee: anyone can recompute it. Imports are
 * therefore always validated and never auto-activated (see ProfileTransfer).
 */
@Serializable
data class ExportEnvelope(
    val format: String = FORMAT_ID,
    val formatVersion: Int = TitanExport.CURRENT_FORMAT_VERSION,
    val payload: String,
    val sha256: String
) {
    companion object {
        const val FORMAT_ID = "titanmapper"
    }
}
