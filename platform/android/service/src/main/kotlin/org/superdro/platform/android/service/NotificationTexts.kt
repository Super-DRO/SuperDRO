package org.superdro.platform.android.service

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * The texts of the notification that the service loads after its start: the name of the channel
 * and the action "Disconnect all".
 */
internal data class NotificationTexts(val channelName: String, val disconnectAll: String) {

    companion object {
        /**
         * Loads the texts from [texts] on [dispatcher]. Gives null when a text does not load. The
         * notification then keeps the label of the app.
         */
        suspend fun load(texts: ServiceTexts, dispatcher: CoroutineContext): NotificationTexts? = try {
            withContext(dispatcher) { NotificationTexts(texts.channelName(), texts.disconnectAll()) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }
}
