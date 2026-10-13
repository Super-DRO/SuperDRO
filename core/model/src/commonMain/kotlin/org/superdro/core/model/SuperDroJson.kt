package org.superdro.core.model

import kotlinx.serialization.json.Json

/**
 * The [Json] configuration of all stored models: the JSON files (`app-settings.json`,
 * `tabs.json`), the JSON text columns of the database, and the export files of SuperDRO.
 * `core:di` binds this one object.
 *
 * - `ignoreUnknownKeys`: a file of a newer app version loads in an older version.
 * - `coerceInputValues`: an enum value that a newer version wrote, or that a later version
 *   removed, gives the default of the field. One bad value does not stop the full load.
 * - `encodeDefaults`: the file holds each value. A change of a default in a later version
 *   then does not change the stored settings of the user.
 *
 * Each field of a stored model has a default value, so an old
 * file loads.
 */
val SuperDroJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
    prettyPrint = false
}
