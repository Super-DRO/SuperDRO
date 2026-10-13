package org.superdro.core.protocol

/** The source of a reported value. */
enum class ValueSource {
    /** An info answer `?` (to `#get` or `#get_mode`, or with no request). */
    LegacyGet,

    /** A status message `$` that the adapter pushes: the dirty flags and the reference state. */
    Pushed,
}

/** The cause of a failed read. */
sealed interface FailureCause {
    /** The adapter gave the ESP status [code]. */
    data class Status(val code: Int) : FailureCause

    /** No answer came: the internal timeout (256). */
    data object Timeout : FailureCause

    /** No command exists for the value. The session sends nothing for it. */
    data object Unsupported : FailureCause

    /** The session dropped while the value was "Fetching" (rule 5). */
    data object SessionDropped : FailureCause
}

/** The read state of an adapter value. */
sealed interface ValueState {
    /** No value and no query. */
    data object Empty : ValueState

    /** A query is in the queue or on the wire. [attempt] starts at 1. */
    data class Fetching(val attempt: Int, val sinceMs: Long) : ValueState

    /** The adapter gave the value at [atMs]. */
    data class Reported(val atMs: Long, val source: ValueSource) : ValueState

    /** The last read failed at [atMs]. The last value stays. */
    data class Failed(val cause: FailureCause, val atMs: Long) : ValueState
}

/**
 * One adapter value with its read state.
 *
 * @property value The last valid value, or null. A failed or new read keeps the last value.
 */
data class AdapterValue<out T>(val value: T? = null, val state: ValueState = ValueState.Empty)

/** The result of a reference search on a channel. */
enum class ReferenceState {
    /** `$<ref>a0;` or `$<ref>aa;`. */
    InProgress,

    /** `$<ref>a1;`. */
    Finished,

    /** `$<ref>a9;`. */
    Cancelled,

    /** `$<ref>16;` or `!<ref>16;` (User timeout). */
    TimedOut,
}

/** The key of an adapter value. */
sealed interface ValueKey {

    /** The lifetime of the value. */
    val lifetime: Lifetime

    /** The query that reads the value, or null when no command exists ("unsupported", rule 2). */
    val command: AdapterCommand?

    /** True when the session can write the value. Only the six mode values are writable (rule 6). */
    val writable: Boolean get() = false

    /** A field of `"#get:<name>;"`. */
    data class Field(val field: AdapterField) : ValueKey {
        init {
            require(field.gettable) { "${field.name} is not a field of #get." }
        }

        override val lifetime: Lifetime get() = this.field.lifetime
        override val command: AdapterCommand get() = AdapterCommand.GetField(this.field)
    }

    /** The mode and the capabilities of a channel (`#get_mode`, `#set_mode`). */
    data class Mode(val channel: InputChannel) : ValueKey {
        override val lifetime: Lifetime get() = Lifetime.Session
        override val command: AdapterCommand get() = AdapterCommand.GetMode(channel)
        override val writable: Boolean get() = true
    }

    /** The reference state of a channel `x y z w`. Only `$` messages on 0x77 to 0x7A give it. */
    data class Reference(val channel: InputChannel) : ValueKey {
        init {
            require(channel.referenceAllowed) { "The channel ${channel.letter} has no reference search." }
        }

        override val lifetime: Lifetime get() = Lifetime.Dynamic
        override val command: AdapterCommand? get() = null
    }

    /** The dirty flags of the six channels. Only `$32<mask>;` gives them. */
    data object Dirty : ValueKey {
        override val lifetime: Lifetime get() = Lifetime.Session
        override val command: AdapterCommand? get() = null
    }

    companion object {
        /**
         * The keys of all values. The list is lazy: an eager list can get a null entry when the JVM loads [Dirty]
         * before this companion (a cycle in the class initialization).
         */
        val all: List<ValueKey> by lazy {
            AdapterField.entries.filter { it.gettable }.map { Field(it) } +
                InputChannel.entries.map { Mode(it) } +
                InputChannel.entries.filter { it.referenceAllowed }.map { Reference(it) } +
                Dirty
        }
    }
}

/**
 * The adapter values with their read states: the register model. The object is
 * immutable. Each change gives a new object, so the session can hold it in a `StateFlow`.
 *
 * Rules:
 *
 * 1. A value that is "Fetching" for less than 5000 ms gets no second query ([shouldQuery]). After
 *    5000 ms, a new query adds 1 to the attempt count ([fetching]).
 * 2. A value with no command is "unsupported". The session sends nothing for it ([read]).
 * 3. A value that is not valid is dropped. The value keeps its old state ([withInfo]).
 * 4. A smaller `uptime` clears all values that are not constant (an adapter restart).
 * 5. A session drop marks each "Fetching" value "Failed" ([dropped]).
 * 6. Only the six mode values are writable ([ValueKey.writable]).
 *
 * @property date The firmware date in seconds since 1970-01-01 UTC.
 * @property uptime The `uptime` value. The unit is not known.
 */
data class AdapterValues(
    val uuid: AdapterValue<String> = AdapterValue(),
    val name: AdapterValue<String> = AdapterValue(),
    val model: AdapterValue<String> = AdapterValue(),
    val mac: AdapterValue<String> = AdapterValue(),
    val version: AdapterValue<String> = AdapterValue(),
    val build: AdapterValue<String> = AdapterValue(),
    val date: AdapterValue<Long> = AdapterValue(),
    val uptime: AdapterValue<Long> = AdapterValue(),
    val features: AdapterValue<Long> = AdapterValue(),
    val modes: Map<InputChannel, AdapterValue<ChannelModes>> = InputChannel.entries.associateWith { AdapterValue() },
    val references: Map<InputChannel, AdapterValue<ReferenceState>> =
        InputChannel.entries.filter { it.referenceAllowed }.associateWith { AdapterValue() },
    val dirty: AdapterValue<Set<InputChannel>> = AdapterValue(),
) {

    /**
     * The adapter identity that the adapter reports: the MAC + 2 with the carry, else the
     * UUID, else null. A Bluetooth link uses its own address before this value.
     */
    val reportedIdentity: String?
        get() = mac.value?.let { AdapterFieldValues.macPlusTwo(it) } ?: uuid.value

    /** The "Bluetooth ID" text: the MAC + 2 with the carry, or null. */
    val bluetoothId: String? get() = mac.value?.let { AdapterFieldValues.macPlusTwo(it) }

    /** Gives the value of [key]. */
    operator fun get(key: ValueKey): AdapterValue<*> = when (key) {
        is ValueKey.Field -> field(key.field)
        is ValueKey.Mode -> modes.getValue(key.channel)
        is ValueKey.Reference -> references.getValue(key.channel)
        ValueKey.Dirty -> dirty
    }

    /** Gives the read state of [key]. */
    fun state(key: ValueKey): ValueState = get(key).state

    /**
     * True when the session can send a query for [key] at [nowMs] (rules 1 and 2). A value with no
     * command gives false. A value that is "Fetching" for less than [readAgainLimitMs] gives false.
     */
    fun shouldQuery(key: ValueKey, nowMs: Long, readAgainLimitMs: Long = Lifetime.DYNAMIC_AGE_LIMIT_MS): Boolean {
        if (key.command == null) return false
        val state = state(key)
        return state !is ValueState.Fetching || nowMs - state.sinceMs >= readAgainLimitMs
    }

    /**
     * True when a reported value of [key] is old at [nowMs]: the lifetime is [Lifetime.Dynamic] and
     * the report is 5000 ms old or more. A value that is not reported gives false.
     */
    fun isStale(key: ValueKey, nowMs: Long): Boolean {
        val state = state(key) as? ValueState.Reported ?: return false
        return key.lifetime == Lifetime.Dynamic && nowMs - state.atMs >= Lifetime.DYNAMIC_AGE_LIMIT_MS
    }

    /**
     * Gives the values after the start of a read of [key] at [nowMs]. A value with no command
     * becomes "Failed" with the cause "unsupported" (rule 2). Else the value becomes "Fetching".
     */
    fun read(key: ValueKey, nowMs: Long): AdapterValues =
        if (key.command == null) failed(key, FailureCause.Unsupported, nowMs) else fetching(key, nowMs)

    /**
     * Gives the values after a query of [key] at [nowMs] (rule 1): "Fetching(attempt + 1, now)"
     * after a "Fetching" state, else "Fetching(1, now)". The last value stays.
     */
    fun fetching(key: ValueKey, nowMs: Long): AdapterValues = withState(key) { state ->
        val attempt = if (state is ValueState.Fetching) state.attempt + 1 else 1
        ValueState.Fetching(attempt, nowMs)
    }

    /** Gives the values after a failed read of [key]. The last value stays. */
    fun failed(key: ValueKey, cause: FailureCause, nowMs: Long): AdapterValues =
        withState(key) { ValueState.Failed(cause, nowMs) }

    /** Gives the values after a session drop (rule 5): each "Fetching" value becomes "Failed". */
    fun dropped(nowMs: Long): AdapterValues = ValueKey.all.fold(this) { values, key ->
        if (values.state(key) is ValueState.Fetching) values.failed(key, FailureCause.SessionDropped, nowMs) else values
    }

    /** Gives the values after an adapter restart (rule 4): each value that is not constant becomes empty. */
    fun clearedNonConstant(): AdapterValues = AdapterValues(
        uuid = uuid,
        model = model,
        mac = mac,
        version = version,
        build = build,
        date = date,
    )

    /**
     * Gives the values after an info answer `?<field><text>;` at [nowMs]. The value
     * goes into the field that the answer names. A value that is not valid, a field that is not
     * known, and the signature give the same values (rule 3).
     *
     * A new `uptime` that is smaller than the stored value tells that the adapter restarted. Then
     * all values that are not constant become empty before the new `uptime` goes in (rule 4).
     */
    fun withInfo(fieldId: Int, text: String, nowMs: Long): AdapterValues {
        val field = AdapterField.forId(fieldId) ?: return this
        return when (field) {
            AdapterField.Mode -> ChannelModes.parse(text)?.let { modes -> withModes(modes, nowMs) } ?: this
            AdapterField.Signature -> this
            AdapterField.Uptime -> {
                val uptime = AdapterFieldValues.parseHex(text) ?: return this
                val base = if (isRestart(uptime)) clearedNonConstant() else this
                base.reported(ValueKey.Field(field), uptime, nowMs, ValueSource.LegacyGet)
            }
            else -> {
                val value: Any = parseField(field, text) ?: return this
                reported(ValueKey.Field(field), value, nowMs, ValueSource.LegacyGet)
            }
        }
    }

    /** True when [uptime] is smaller than the stored `uptime`: the adapter restarted. */
    fun isRestart(uptime: Long): Boolean {
        val stored = this.uptime.value
        return stored != null && uptime < stored
    }

    /** Gives the values with the reported modes of a channel. */
    fun withModes(channelModes: ChannelModes, nowMs: Long): AdapterValues =
        reported(ValueKey.Mode(channelModes.channel), channelModes, nowMs, ValueSource.LegacyGet)

    /**
     * Gives the values before a `#get_mode` of [channel]: the mode becomes "Unknown", and the
     * supported list becomes empty. The read state stays.
     */
    fun withModeUnknown(channel: InputChannel): AdapterValues {
        val old = modes.getValue(channel)
        return copy(modes = modes + (channel to AdapterValue(ChannelModes.unknown(channel), old.state)))
    }

    /** Gives the values with the dirty flags of a `$32<mask>;` status. */
    fun withDirty(mask: Int, nowMs: Long): AdapterValues =
        reported(ValueKey.Dirty, InputChannel.dirtyChannels(mask), nowMs, ValueSource.Pushed)

    /** Gives the values with the reference state of [channel] from a status message. */
    fun withReference(channel: InputChannel, state: ReferenceState, nowMs: Long): AdapterValues =
        reported(ValueKey.Reference(channel), state, nowMs, ValueSource.Pushed)

    private fun parseField(field: AdapterField, text: String): Any? = when (field) {
        AdapterField.Uuid -> AdapterFieldValues.parseUuid(text)
        AdapterField.Mac -> AdapterFieldValues.parseMac(text)
        AdapterField.Date -> AdapterFieldValues.parseHex(text)
        AdapterField.Uptime -> AdapterFieldValues.parseHex(text)
        AdapterField.Features -> AdapterFieldValues.parseDecimal(text)
        AdapterField.Name, AdapterField.Model, AdapterField.Version, AdapterField.Build -> text
        AdapterField.Mode, AdapterField.Signature -> null
    }

    private fun field(field: AdapterField): AdapterValue<*> = when (field) {
        AdapterField.Uuid -> uuid
        AdapterField.Name -> name
        AdapterField.Model -> model
        AdapterField.Mac -> mac
        AdapterField.Version -> version
        AdapterField.Build -> build
        AdapterField.Date -> date
        AdapterField.Uptime -> uptime
        AdapterField.Features -> features
        AdapterField.Mode, AdapterField.Signature -> throw IllegalArgumentException("${field.name} is not a field of #get.")
    }

    private fun reported(key: ValueKey, value: Any, nowMs: Long, source: ValueSource): AdapterValues =
        put(key, AdapterValue(value, ValueState.Reported(nowMs, source)))

    private fun withState(key: ValueKey, transform: (ValueState) -> ValueState): AdapterValues {
        val old = get(key)
        return put(key, AdapterValue(old.value, transform(old.state)))
    }

    @Suppress("UNCHECKED_CAST")
    private fun put(key: ValueKey, value: AdapterValue<Any?>): AdapterValues = when (key) {
        is ValueKey.Field -> when (key.field) {
            AdapterField.Uuid -> copy(uuid = value as AdapterValue<String>)
            AdapterField.Name -> copy(name = value as AdapterValue<String>)
            AdapterField.Model -> copy(model = value as AdapterValue<String>)
            AdapterField.Mac -> copy(mac = value as AdapterValue<String>)
            AdapterField.Version -> copy(version = value as AdapterValue<String>)
            AdapterField.Build -> copy(build = value as AdapterValue<String>)
            AdapterField.Date -> copy(date = value as AdapterValue<Long>)
            AdapterField.Uptime -> copy(uptime = value as AdapterValue<Long>)
            AdapterField.Features -> copy(features = value as AdapterValue<Long>)
            AdapterField.Mode, AdapterField.Signature -> this
        }
        is ValueKey.Mode -> copy(modes = modes + (key.channel to value as AdapterValue<ChannelModes>))
        is ValueKey.Reference -> copy(references = references + (key.channel to value as AdapterValue<ReferenceState>))
        ValueKey.Dirty -> copy(dirty = value as AdapterValue<Set<InputChannel>>)
    }
}
