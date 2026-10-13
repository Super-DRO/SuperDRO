package org.superdro.core.ui.adapter.identity

import org.superdro.core.connection.AdapterIdentity
import org.superdro.core.connection.AdapterInfo
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterProfile
import org.superdro.core.protocol.AdapterField
import org.superdro.core.protocol.ValueKey
import org.superdro.core.protocol.ValueState
import org.superdro.core.ui.adapter.details.AdapterDetailsRules

/** The reason of the "new adapter" prompt. */
enum class PromptReason {
    /** The machine profile has no adapter profile. */
    NoProfile,

    /** The identity of the link is not the identity of the adapter profile of the tab. */
    Differs,

    /** The adapter profile of the tab has no identity, and other adapter profiles have the identity of the link. */
    Known,

    /** The adapter gives no identity (a legacy adapter). The user declares its inputs. */
    Legacy,
}

/** The result of the identity rules for one link. */
sealed interface IdentityDecision {
    /** Nothing to do: the identity confirms the adapter profile, or the rules cannot check it. */
    data object None : IdentityDecision

    /** The identity of the link is not known yet. The rules wait. */
    data object Wait : IdentityDecision

    /** The machine profile has no adapter profile, and one stored profile has the identity: the tab uses it. */
    data class Link(val profile: AdapterProfile) : IdentityDecision

    /** The adapter profile of the tab has no identity, and no other profile has it: the profile gets the identity of the link. */
    data object StoreIdentity : IdentityDecision

    /**
     * The prompt "It looks like you connected a new adapter" shows.
     *
     * @property matches The stored adapter profiles with the identity of the link, except the profile of the tab.
     * @property updateCurrent True when "create" changes the adapter profile of the tab (a legacy
     *   adapter with a new profile), false when it makes a new adapter profile.
     */
    data class Prompt(val reason: PromptReason, val matches: List<AdapterProfile>, val updateCurrent: Boolean) : IdentityDecision
}

/**
 * The adapter identity rules of SuperDRO.
 *
 * The identity of a link is the Bluetooth address of an SPP link, else the reported MAC + 2 with
 * the carry, else the reported UUID. No signature result is necessary. The tab selects the
 * adapter profile, and thus the encoder profiles: the readout is live before the check. The
 * rules only confirm the profile, fill in a missing identity, or ask the user:
 *
 * | Adapter profile of the tab | Identity of the link | Result |
 * |---|---|---|
 * | none | known, one stored profile has it | the tab uses that profile |
 * | none | known, no stored profile or more than one | prompt (no profile) |
 * | none | none (legacy adapter) | prompt (legacy): a new profile with the declared inputs |
 * | no identity | known, no other profile has it | the profile gets the identity |
 * | no identity | known, other profiles have it | prompt (known): use a stored profile or keep this one |
 * | no identity, not legacy, no declared inputs | none (legacy adapter) | prompt (legacy): declare the inputs |
 * | identity | the same identity | nothing (confirmed) |
 * | identity | a different identity | prompt (differs); the frame also shows the warning |
 */
object AdapterIdentityRules {

    /**
     * The time after the change to `Connected` after which a link with no identity is a legacy
     * adapter. The "id" group gets one more query 5000 ms after the queue start, and the queue
     * starts 2000 ms after `Connected`.
     */
    const val LEGACY_WAIT_MS: Long = 10_000L

    /** Gives the decision for one link. */
    fun decide(identity: AdapterIdentity?, tab: AdapterProfile?, matches: List<AdapterProfile>, legacyAdapter: Boolean): IdentityDecision {
        val others = matches.filter { profile -> profile.id != tab?.id }
        if (identity == null) {
            if (!legacyAdapter) return IdentityDecision.Wait
            return when {
                tab == null -> IdentityDecision.Prompt(PromptReason.Legacy, emptyList(), updateCurrent = false)
                tab.identity != null || tab.legacy || tab.declaredInputs.isNotEmpty() -> IdentityDecision.None
                else -> IdentityDecision.Prompt(PromptReason.Legacy, emptyList(), updateCurrent = true)
            }
        }
        if (tab == null) {
            return if (others.size == 1) IdentityDecision.Link(others.single()) else IdentityDecision.Prompt(PromptReason.NoProfile, others, updateCurrent = false)
        }
        if (tab.identity == null) {
            return if (others.isEmpty()) IdentityDecision.StoreIdentity else IdentityDecision.Prompt(PromptReason.Known, others, updateCurrent = false)
        }
        return if (identity.matches(tab.identity)) IdentityDecision.None else IdentityDecision.Prompt(PromptReason.Differs, others, updateCurrent = false)
    }

    /**
     * True when the link gives no identity: two-way communication is off (the values stay
     * "Empty"), or the link is `Connected` for [LEGACY_WAIT_MS] or more and no field of the "id"
     * group is "Fetching".
     */
    fun legacyAdapter(info: AdapterInfo, state: SessionState?, nowMs: Long): Boolean {
        if (info.identity != null) return false
        val connected = state as? SessionState.Connected ?: return false
        if (!info.twoWayCommunication) return true
        if (nowMs - connected.sinceMs < LEGACY_WAIT_MS) return false
        return AdapterField.idGroup.none { field -> info.values.state(ValueKey.Field(field)) is ValueState.Fetching }
    }

    /**
     * The adapter profile [tab] with the declared inputs and the model from the mode answers of a
     * two-way adapter, or null when nothing changes or
     * a mode answer is missing.
     */
    fun declaredUpdate(tab: AdapterProfile?, info: AdapterInfo): AdapterProfile? {
        if (tab == null || !info.twoWayCommunication) return null
        val declared = AdapterDetailsRules.declaredFromModes(info.values) ?: return null
        val model = info.values.model.value ?: tab.model
        if (declared == tab.declaredInputs && model == tab.model) return null
        return tab.copy(declaredInputs = declared, model = model)
    }
}
