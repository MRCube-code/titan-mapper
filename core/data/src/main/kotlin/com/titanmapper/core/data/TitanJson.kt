package com.titanmapper.core.data

import kotlinx.serialization.json.Json

/**
 * The one Json configuration used for storage and export.
 *
 * - `ignoreUnknownKeys`: files written by a newer app version still load (extra fields dropped).
 * - `encodeDefaults`: written files are self-describing and don't depend on future default changes.
 * - discriminator `_type`: the default "type" collides with real properties named `type`
 *   (e.g. MappedAction.SystemAction.type), which makes serialization fail at runtime.
 */
object TitanJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "_type"
    }
}
