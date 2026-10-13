package org.superdro.core.store.json

import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import org.superdro.core.model.AppSettings
import org.superdro.core.model.SuperDroJson
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.store.storeIoDispatcher

/**
 * The global settings in `app-settings.json`: the "App" rows
 * of the settings, the shown hints, and the key map. The rules of [JsonFileStore] apply.
 */
class AppSettingsStore(
    storeIO: StoreIOProvider,
    json: Json = SuperDroJson,
    dispatcher: CoroutineDispatcher = storeIoDispatcher,
    clock: Clock = Clock.System,
) : JsonFileStore<AppSettings>(storeIO, FILE_NAME, AppSettings.serializer(), AppSettings(), json, dispatcher, clock) {

    /** The current settings. The same flow as [state]. */
    val settings: StateFlow<AppSettings> get() = state

    /** Records that the app showed the hint [hintId] one time. */
    fun markHintShown(hintId: String) {
        update { settings -> settings.copy(shownHints = settings.shownHints + hintId) }
    }

    companion object {
        /** The store name of the file. */
        const val FILE_NAME: String = "app-settings.json"
    }
}
