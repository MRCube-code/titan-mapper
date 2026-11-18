package com.titanmapper.core.data.transfer

import com.titanmapper.core.common.DataResult
import com.titanmapper.core.data.repository.MacroRepository
import com.titanmapper.core.data.repository.OverlayRepository
import com.titanmapper.core.data.repository.ProfileRepository
import com.titanmapper.core.model.ExportEnvelope
import com.titanmapper.core.model.MacroCondition
import com.titanmapper.core.model.MacroDefinition
import com.titanmapper.core.model.MacroStep
import com.titanmapper.core.model.MacroValidator
import com.titanmapper.core.model.MappedAction
import com.titanmapper.core.model.MappingProfile
import com.titanmapper.core.model.OverlayLayout
import com.titanmapper.core.model.TitanExport
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ImportSummary(val profiles: Int, val macros: Int, val layouts: Int)

/**
 * JSON import/export for profiles, macros and overlay layouts, used by sharing and backup.
 *
 * Imported data is untrusted: it is size/shape validated, every id is regenerated (so an import
 * can never overwrite existing data or collide), and imported profiles start DISABLED so a shared
 * file can't silently start remapping input.
 */
@Singleton
class ProfileTransfer @Inject constructor(
    private val profiles: ProfileRepository,
    private val macros: MacroRepository,
    private val overlays: OverlayRepository,
    private val json: Json
) {
    /** @param profileIds null exports everything. Macros/layouts are included when referenced by or tied to the chosen profiles. */
    suspend fun buildExport(profileIds: Set<String>? = null): TitanExport {
        val allProfiles = profiles.observeProfilesOnce()
        val chosen = if (profileIds == null) allProfiles else allProfiles.filter { it.id in profileIds }
        val chosenIds = chosen.map { it.id }.toSet()
        val allMacros = macros.getAll()
        val referenced = if (profileIds == null) {
            allMacros
        } else {
            val ids = chosen.flatMap { p -> p.bindings.mapNotNull { (it.action as? MappedAction.MacroRun)?.macroId } }.toSet()
            allMacros.filter { it.id in ids }
        }
        val layouts = overlays.getAll().filter { it.profileId in chosenIds }
        return TitanExport(
            createdAtMillis = System.currentTimeMillis(),
            profiles = chosen,
            macros = referenced,
            layouts = layouts
        )
    }

    fun encode(export: TitanExport): String {
        val payload = json.encodeToString(TitanExport.serializer(), export)
        return json.encodeToString(ExportEnvelope.serializer(), ExportEnvelope(payload = payload, sha256 = sha256Hex(payload)))
    }

    fun decode(text: String): DataResult<TitanExport> {
        if (text.length > MAX_TEXT_CHARS) return DataResult.Error("File is too large to import", recoverable = false)
        val envelope = try {
            json.decodeFromString(ExportEnvelope.serializer(), text)
        } catch (e: SerializationException) {
            return DataResult.Error("This isn't a Titan Mapper file", e)
        } catch (e: IllegalArgumentException) {
            return DataResult.Error("This isn't a Titan Mapper file", e)
        }
        if (envelope.format != ExportEnvelope.FORMAT_ID) return DataResult.Error("This isn't a Titan Mapper file")
        if (envelope.formatVersion > TitanExport.CURRENT_FORMAT_VERSION) {
            return DataResult.Error("This file was made by a newer version of Titan Mapper. Update the app and try again.", recoverable = false)
        }
        if (sha256Hex(envelope.payload) != envelope.sha256) {
            return DataResult.Error("The file is damaged or was edited (checksum mismatch)", recoverable = false)
        }
        val export = try {
            json.decodeFromString(TitanExport.serializer(), envelope.payload)
        } catch (e: SerializationException) {
            return DataResult.Error("The file contents couldn't be read", e)
        } catch (e: IllegalArgumentException) {
            return DataResult.Error("The file contents couldn't be read", e)
        }
        return validate(export)
    }

    private fun validate(e: TitanExport): DataResult<TitanExport> {
        if (e.profiles.size > MAX_PROFILES) return DataResult.Error("Too many profiles in file")
        if (e.macros.size > MAX_MACROS) return DataResult.Error("Too many macros in file")
        for (p in e.profiles) {
            if (p.name.isBlank() || p.name.length > ProfileRepository.MAX_NAME_LENGTH) {
                return DataResult.Error("A profile in the file has an invalid name")
            }
            if (p.bindings.size > ProfileRepository.MAX_BINDINGS) {
                return DataResult.Error("Profile \"${p.name}\" has too many bindings")
            }
        }
        for (m in e.macros) {
            val errors = MacroValidator.validate(m)
            if (errors.isNotEmpty()) return DataResult.Error("Macro \"${m.name}\" is invalid: ${errors.first()}")
        }
        return DataResult.Success(e)
    }

    /** Saves [export] with fresh ids; profiles arrive disabled. */
    suspend fun import(export: TitanExport): DataResult<ImportSummary> {
        val profileIds = export.profiles.associate { it.id to newId() }
        val macroIds = export.macros.associate { it.id to newId() }
        val bindingIds = HashMap<String, String>()
        export.profiles.forEach { p -> p.bindings.forEach { bindingIds[it.id] = newId() } }

        var savedProfiles = 0
        var savedMacros = 0
        var savedLayouts = 0

        for (m in export.macros) {
            val remapped = m.copy(id = macroIds.getValue(m.id), steps = m.steps.map { remapStep(it, bindingIds) })
            macros.save(remapped).let { if (it is DataResult.Error) return it }
            savedMacros++
        }
        for (p in export.profiles) {
            val newProfileId = profileIds.getValue(p.id)
            val remapped = p.copy(
                id = newProfileId,
                isActive = false,
                bindings = p.bindings.map { b ->
                    b.copy(id = bindingIds.getValue(b.id), action = remapAction(b.action, macroIds))
                }
            )
            profiles.save(remapped).let { if (it is DataResult.Error) return it }
            savedProfiles++
        }
        for (l in export.layouts) {
            val newProfileId = profileIds[l.profileId] ?: continue
            val layout: OverlayLayout = l.copy(
                profileId = newProfileId,
                controls = l.controls.map { c -> c.copy(action = c.action?.let { remapAction(it, macroIds) }) }
            )
            overlays.save(layout).let { if (it is DataResult.Error) return it }
            savedLayouts++
        }
        return DataResult.Success(ImportSummary(savedProfiles, savedMacros, savedLayouts))
    }

    private fun remapAction(a: MappedAction, macroIds: Map<String, String>): MappedAction =
        if (a is MappedAction.MacroRun) a.copy(macroId = macroIds[a.macroId] ?: a.macroId) else a

    private fun remapStep(s: MacroStep, bindingIds: Map<String, String>): MacroStep =
        if (s is MacroStep.ConditionalIf) {
            s.copy(
                condition = remapCondition(s.condition, bindingIds),
                thenSteps = s.thenSteps.map { remapStep(it, bindingIds) },
                elseSteps = s.elseSteps.map { remapStep(it, bindingIds) }
            )
        } else s

    private fun remapCondition(c: MacroCondition, bindingIds: Map<String, String>): MacroCondition = when (c) {
        is MacroCondition.ToggleIsActive -> c.copy(bindingId = bindingIds[c.bindingId] ?: c.bindingId)
        is MacroCondition.Not -> c.copy(condition = remapCondition(c.condition, bindingIds))
        else -> c
    }

    private fun newId() = UUID.randomUUID().toString()

    private companion object {
        const val MAX_TEXT_CHARS = 8_000_000
        const val MAX_PROFILES = 500
        const val MAX_MACROS = 2_000

        fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/** Snapshot read used by export; kept out of the repository interface's streaming API. */
private suspend fun ProfileRepository.observeProfilesOnce(): List<MappingProfile> =
    observeProfiles().first()
