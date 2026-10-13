package org.superdro.core.ui.adapter.identity

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.connection.AdapterIdentity
import org.superdro.core.connection.AdapterInfo
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceSession
import org.superdro.core.connection.IdentityCheck
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.DeclaredInput
import org.superdro.core.model.MachineProfile
import org.superdro.core.store.StoreResult
import org.superdro.core.ui.adapter.AdapterUiDeps
import org.superdro.core.ui.adapter.details.AdapterDetailsRules
import org.superdro.core.ui.adapter.profile.AdapterProfileActions
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/**
 * The state of the "new adapter" prompt.
 *
 * @property id The number of the request. Each new prompt has a new number.
 * @property matches The stored adapter profiles with the identity of the link: (ID, name).
 * @property useProfileId The stored profile that the user selected, or null for "create".
 * @property updateCurrent True when "create" changes the adapter profile of the tab.
 * @property declared The declared inputs of a legacy adapter.
 */
@Immutable
data class NewAdapterPromptUi(
    val id: Long,
    val reason: PromptReason,
    val model: String?,
    val identity: String?,
    val currentAlias: String?,
    val matches: List<Pair<String, String>>,
    val useProfileId: String?,
    val updateCurrent: Boolean,
    val name: String,
    val nameError: UiText? = null,
    val declared: Set<DeclaredInput> = emptySet(),
    val busy: Boolean = false,
) {
    /** True for a legacy adapter: the prompt shows the six check boxes X, Y, Z, W, Probe, Tach. */
    val legacy: Boolean get() = reason == PromptReason.Legacy
}

/**
 * The identity watcher of one tab and the presenter of its "new adapter" prompt. The tab owns it
 * ([org.superdro.core.ui.navigation.TabEntryScope.tabPresenter]),
 * so it lives as long as the tab.
 *
 * For each `Connected` link, the presenter applies [AdapterIdentityRules] one time: it links a
 * stored profile, stores the identity in the adapter profile of the tab, or opens the prompt. The
 * prompt shows at most one time for each link. With the mode answers of a two-way adapter, the
 * presenter also writes the declared inputs and the model into the adapter profile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdapterIdentityPresenter(
    private val deviceId: DeviceId,
    private val deps: AdapterUiDeps,
) : ScopedPresenter(deps.context) {

    private val mutablePrompt = MutableStateFlow<NewAdapterPromptUi?>(null)
    private val tick = MutableStateFlow(0L)
    private var requests = 0L
    private var shown: Long? = null
    private val handledLinks = HashSet<Long>()
    private val seenLinks = HashSet<Long>()
    private var legacyTimer: Job? = null

    /** The prompt that waits for the user, or null. */
    val prompt: StateFlow<NewAdapterPromptUi?> = mutablePrompt.asStateFlow()

    private val session: StateFlow<DeviceSession?> =
        deps.session(deviceId).stateIn(scope, SharingStarted.Eagerly, deps.connections.sessions.value[deviceId])

    private val state: StateFlow<SessionState?> =
        session.flatMapLatest { current -> current?.state ?: flowOf(null) }.stateIn(scope, SharingStarted.Eagerly, null)

    private val info: StateFlow<AdapterInfo> =
        session.flatMapLatest { current -> current?.adapterInfo ?: flowOf(AdapterInfo()) }.stateIn(scope, SharingStarted.Eagerly, AdapterInfo())

    private val machine: StateFlow<MachineProfile?> = deps.machine(deviceId).stateIn(scope, SharingStarted.Eagerly, null)

    private val tabAdapter: StateFlow<AdapterProfile?> = deps.tabAdapter(deviceId).stateIn(scope, SharingStarted.Eagerly, null)

    private class Facts(val state: SessionState?, val info: AdapterInfo, val machine: MachineProfile?, val tab: AdapterProfile?)

    init {
        scope.launch {
            combine(combine(state, info) { current, adapterInfo -> current to adapterInfo }, machine, tabAdapter, tick) { (current, adapterInfo), profile, tab, _ ->
                Facts(current, adapterInfo, profile, tab)
            }.collect(::evaluate)
        }
    }

    /**
     * Opens the prompt by the user, for example from the Adapter Details screen when the tab has
     * no adapter profile.
     */
    fun openManual() {
        val facts = Facts(state.value, info.value, machine.value, tabAdapter.value)
        if (facts.machine == null) return
        val reason = if (facts.info.identity == null) PromptReason.Legacy else PromptReason.NoProfile
        scope.launch { showPrompt(reason, emptyList(), updateCurrent = false, facts) }
    }

    /** The tab effect pushed the prompt of request [id]. */
    fun onShown(id: Long) {
        shown = id
    }

    /** True when the prompt of request [id] still needs a push. */
    fun needsPush(id: Long): Boolean = shown != id

    /** The user typed the name of the new adapter profile. */
    fun onName(text: String) = mutablePrompt.update { current -> current?.copy(name = text, nameError = null) }

    /** The user selected the stored profile [profileId], or "create" for null. */
    fun onUse(profileId: String?) = mutablePrompt.update { current -> current?.copy(useProfileId = profileId) }

    /** The user changed the check box of the declared input [input]. */
    fun onToggleDeclared(input: DeclaredInput) = mutablePrompt.update { current ->
        current?.copy(declared = if (input in current.declared) current.declared - input else current.declared + input)
    }

    /** "Create Adapter Profile" or "Use": writes the choice. The prompt closes after the write. */
    fun onConfirm() {
        val current = mutablePrompt.value ?: return
        if (current.busy) return
        mutablePrompt.value = current.copy(busy = true)
        scope.launch {
            val use = current.useProfileId
            val error: UiText? = if (use != null) select(use) else create(current)
            if (error == null) {
                mutablePrompt.value = null
            } else {
                mutablePrompt.update { prompt -> prompt?.copy(busy = false, nameError = error) }
            }
        }
    }

    /** "Disconnect": pauses the machine. The prompt closes. */
    fun onDisconnect() {
        mutablePrompt.value = null
        deps.connections.disconnect(deviceId)
    }

    /** The prompt closed with no choice. It does not show again for this link. */
    fun onDismiss() {
        mutablePrompt.value = null
    }

    // ---------------------------------------------------------------------------------------
    // Private parts

    private suspend fun evaluate(facts: Facts) {
        val connected = facts.state as? SessionState.Connected ?: return
        val machine = facts.machine ?: return
        // The adapter profile of the tab loads after the machine profile: wait for it.
        val tab = facts.tab?.takeIf { profile -> profile.id == machine.adapterProfileId }
        if (machine.adapterProfileId != null && tab == null) return
        val link = connected.sinceMs
        AdapterIdentityRules.declaredUpdate(tab, facts.info)?.let { updated -> deps.adapters.saveAdapterProfile(updated) }
        if (facts.info.identityCheck == IdentityCheck.Confirmed && tab != null && seenLinks.add(link)) {
            deps.adapters.setLastSeen(tab.id, deps.wallClock.now())
        }
        if (link in handledLinks) return
        val identity = facts.info.identity
        val legacy = AdapterIdentityRules.legacyAdapter(facts.info, facts.state, clock.nowMs())
        val matches = if (identity == null) emptyList() else deps.adapters.findByIdentity(identity.value, identity.source)
        when (val decision = AdapterIdentityRules.decide(identity, tab, matches, legacy)) {
            IdentityDecision.None -> handledLinks += link
            IdentityDecision.Wait -> if (identity == null) scheduleLegacyCheck(connected)
            is IdentityDecision.Link -> {
                handledLinks += link
                deps.machines.updateProfile(machine.id) { profile -> profile.copy(adapterProfileId = decision.profile.id) }
            }
            IdentityDecision.StoreIdentity -> {
                handledLinks += link
                storeIdentity(tab!!, identity!!, facts.info)
            }
            is IdentityDecision.Prompt -> {
                handledLinks += link
                showPrompt(decision.reason, decision.matches, decision.updateCurrent, Facts(facts.state, facts.info, machine, tab))
            }
        }
    }

    private fun scheduleLegacyCheck(connected: SessionState.Connected) {
        if (legacyTimer?.isActive == true) return
        val wait = connected.sinceMs + AdapterIdentityRules.LEGACY_WAIT_MS - clock.nowMs()
        legacyTimer = scope.launch {
            if (wait > 0L) delay(wait)
            tick.update { value -> value + 1 }
        }
    }

    private suspend fun storeIdentity(tab: AdapterProfile, identity: AdapterIdentity, info: AdapterInfo) {
        deps.adapters.saveAdapterProfile(
            tab.copy(
                identity = identity.value,
                identitySource = identity.source,
                model = info.values.model.value ?: tab.model,
                lastSeenOn = deps.wallClock.now(),
            ),
        )
    }

    private suspend fun showPrompt(reason: PromptReason, matches: List<AdapterProfile>, updateCurrent: Boolean, facts: Facts) {
        val values = facts.info.values
        val name = when {
            updateCurrent && facts.tab != null -> facts.tab.alias
            else -> {
                val base = listOfNotNull(values.name.value, session.value?.device?.value?.name, values.model.value)
                    .firstOrNull { text -> text.isNotBlank() } ?: DEFAULT_NAME
                AdapterProfileActions.uniqueAlias(deps, base, null)
            }
        }
        mutablePrompt.value = NewAdapterPromptUi(
            id = ++requests,
            reason = reason,
            model = AdapterDetailsRules.model(values, null),
            identity = facts.info.identity?.value,
            currentAlias = facts.tab?.alias,
            matches = matches.map { profile -> profile.id to profile.alias },
            useProfileId = null,
            updateCurrent = updateCurrent,
            name = name.take(AdapterProfile.MAX_ALIAS_LENGTH),
            declared = facts.tab?.declaredInputs.orEmpty(),
        )
    }

    private suspend fun select(profileId: String): UiText? =
        when (val result = deps.machines.updateProfile(deviceId.value) { profile -> profile.copy(adapterProfileId = profileId) }) {
            is StoreResult.Ok -> null
            is StoreResult.Refused -> result.refusal.toUiText()
        }

    private suspend fun create(prompt: NewAdapterPromptUi): UiText? {
        val facts = Facts(state.value, info.value, machine.value, tabAdapter.value)
        val tab = facts.tab
        val linkIdentity = facts.info.identity
        if (prompt.reason == PromptReason.Known && tab != null && linkIdentity != null) {
            storeIdentity(tab, linkIdentity, facts.info)
            return null
        }
        if (prompt.updateCurrent && tab != null) {
            val saved = deps.adapters.saveAdapterProfile(tab.copy(alias = prompt.name, legacy = true, declaredInputs = prompt.declared))
            return (saved as? StoreResult.Refused)?.refusal?.toUiText()
        }
        val identity = facts.info.identity
        val legacy = prompt.reason == PromptReason.Legacy || identity == null
        val now = deps.wallClock.now()
        val alias = if (prompt.name.isBlank()) AdapterProfileActions.uniqueAlias(deps, DEFAULT_NAME, null) else prompt.name
        val profile = AdapterProfile(
            alias = alias,
            model = facts.info.values.model.value,
            identity = identity?.value,
            identitySource = identity?.source,
            legacy = legacy,
            lastLink = facts.machine?.adapter,
            declaredInputs = if (legacy) prompt.declared else AdapterDetailsRules.declaredFromModes(facts.info.values).orEmpty(),
            lastSeenOn = now,
            createdOn = now,
        )
        return when (val saved = deps.adapters.saveAdapterProfile(profile)) {
            is StoreResult.Refused -> saved.refusal.toUiText()
            is StoreResult.Ok -> select(saved.value.id)
        }
    }

    companion object {
        /** The name of the tab presenter in [org.superdro.core.ui.navigation.TabEntryScope.tabPresenter]. */
        const val NAME: String = "adapter.identity"

        /** The name of a new adapter profile when no name is known. */
        const val DEFAULT_NAME: String = "Adapter"
    }
}
