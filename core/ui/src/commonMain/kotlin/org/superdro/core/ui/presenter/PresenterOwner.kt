package org.superdro.core.ui.presenter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The presenters of one back stack entry. The entry owns them: [close]
 * closes each presenter when the entry leaves its back stack.
 *
 * An owner lives in the app-scope [PresenterOwners] holder, not in the composition. Thus a
 * configuration change gives the same presenters to the new composition.
 *
 * @property key The content key of the entry.
 */
class PresenterOwner internal constructor(val key: String) {

    private val presenters = LinkedHashMap<String, Presenter>()
    private var closed = false

    /** True after [close]. */
    val isClosed: Boolean get() = closed

    /** The number of presenters of the entry. */
    val size: Int get() = presenters.size

    /**
     * Gives the presenter with the name [name], or makes it with [create]. A closed owner makes a
     * new presenter each time and closes it at once, so a late composition cannot leak a scope.
     */
    @Suppress("UNCHECKED_CAST")
    fun <P : Presenter> getOrCreate(name: String, create: () -> P): P {
        if (closed) return create().also { presenter -> presenter.close() }
        return presenters.getOrPut(name) { create() } as P
    }

    /** Closes each presenter of the entry. A second call does nothing. */
    fun close() {
        if (closed) return
        closed = true
        presenters.values.reversed().forEach { presenter -> presenter.close() }
        presenters.clear()
    }
}

/**
 * The presenter owners of all entries, by the content key of the entry. The navigation holder
 * has one object. [isAlive] tells if a key is still in a back stack.
 */
class PresenterOwners(private val isAlive: (String) -> Boolean) {

    private val owners = LinkedHashMap<String, PresenterOwner>()

    /** Gives the owner of the entry [key]. A new key gets a new owner. */
    fun owner(key: String): PresenterOwner = owners.getOrPut(key) { PresenterOwner(key) }

    /** The keys that have an owner. */
    val keys: Set<String> get() = owners.keys.toSet()

    /**
     * The entry [key] left the composition of its back stack (the `onPop` of the entry
     * decorator). The owner closes only when no back stack holds the key, so a configuration
     * change or a hidden tab keeps the presenters.
     */
    fun onPopped(key: String) {
        if (!isAlive(key)) close(key)
    }

    /** Closes the owner of [key] and removes it. */
    fun close(key: String) {
        owners.remove(key)?.close()
    }

    /** Closes each owner whose key is not in [alive]. */
    fun retain(alive: Set<String>) {
        owners.keys.filter { key -> key !in alive }.forEach(::close)
    }
}

/** The presenter owner of the entry that is in composition. The entry decorator gives it. */
val LocalPresenterOwner = staticCompositionLocalOf<PresenterOwner> {
    error("No PresenterOwner: the content is not in a back stack entry.")
}

/**
 * Gives the presenter of the current entry with the name [name], or makes it with [create].
 * The entry owns the presenter and closes it when the entry leaves the back stack.
 */
@Composable
fun <P : Presenter> rememberPresenter(name: String = "main", create: () -> P): P =
    LocalPresenterOwner.current.getOrCreate(name, create)
