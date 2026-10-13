package org.superdro.core.ui.dataoutput

import org.superdro.core.dataoutput.DataOutputLimits
import org.superdro.core.dataoutput.DataOutputValidation
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputFieldId
import org.superdro.core.model.DataOutputFormat
import org.superdro.core.model.DataOutputSecurity
import org.superdro.core.model.DataOutputTransport
import org.superdro.core.model.MqttTopicLayout

/** A dialog of the data output screen, or the open diagnostics ([Diagnostics]). */
sealed interface DataOutputDialog {

    /** The warning "Use only on trusted networks" before the plain text mode (the security rules). */
    data object PlainTextWarning : DataOutputDialog

    /** The same warning before a broker link with no TLS (the security rules). */
    data object MqttNoTlsWarning : DataOutputDialog

    /** The question before "Make a new key". */
    data object NewKey : DataOutputDialog

    /**
     * The diagnostics of the output. They show in the status section of the screen, not in a
     * dialog (the dialog rule).
     */
    data object Diagnostics : DataOutputDialog
}

/** The result of a label edit. */
sealed interface LabelEdit {

    /** The screen keeps [config]. */
    data class Accepted(val config: DataOutputConfig) : LabelEdit

    /** The screen refuses the edit, because the label cannot contain [character]. */
    data class Refused(val character: Char) : LabelEdit

    /** The screen refuses the edit, because the label has more than 32 characters. */
    data object TooLong : LabelEdit
}

/**
 * The rules of the data output screen. The presenter calls them. They
 * do no I/O, so that a unit test can examine each rule.
 */
object DataOutputForm {

    /** The broker port with no TLS. */
    const val MQTT_PORT: Int = 1883

    /**
     * Selects the security mode [mode]. The encrypted mode applies at once and
     * clears the confirmation of the plain text mode. The plain text mode applies only after the
     * warning: the result keeps [config] and gives [DataOutputDialog.PlainTextWarning].
     */
    fun selectSecurity(config: DataOutputConfig, mode: DataOutputSecurity): Pair<DataOutputConfig, DataOutputDialog?> = when (mode) {
        DataOutputSecurity.Psk -> config.copy(security = DataOutputSecurity.Psk, plainTextConfirmed = false) to null
        DataOutputSecurity.PlainText ->
            if (config.security == DataOutputSecurity.PlainText && config.plainTextConfirmed) config to null
            else config to DataOutputDialog.PlainTextWarning
    }

    /** The user confirmed the warning "Use only on trusted networks" for the plain text mode. */
    fun confirmPlainText(config: DataOutputConfig): DataOutputConfig =
        config.copy(security = DataOutputSecurity.PlainText, plainTextConfirmed = true)

    /**
     * Sets TLS to the broker (the security rules). TLS on applies at once and clears the
     * confirmation. TLS off applies only after the warning: the result keeps [config] and gives
     * [DataOutputDialog.MqttNoTlsWarning]. The default port of the old mode changes to the
     * default port of the new mode (8883 with TLS, 1883 with no TLS).
     */
    fun selectTls(config: DataOutputConfig, tls: Boolean): Pair<DataOutputConfig, DataOutputDialog?> = when {
        tls -> config.copy(
            mqttTls = true,
            mqttNoTlsConfirmed = false,
            mqttBrokerPort = if (config.mqttBrokerPort == MQTT_PORT) DataOutputConfig.DEFAULT_MQTT_TLS_PORT else config.mqttBrokerPort,
        ) to null
        !config.mqttTls && config.mqttNoTlsConfirmed -> config to null
        else -> config to DataOutputDialog.MqttNoTlsWarning
    }

    /** The user confirmed the warning for a broker link with no TLS. */
    fun confirmNoTls(config: DataOutputConfig): DataOutputConfig = config.copy(
        mqttTls = false,
        mqttNoTlsConfirmed = true,
        mqttBrokerPort = if (config.mqttBrokerPort == DataOutputConfig.DEFAULT_MQTT_TLS_PORT) MQTT_PORT else config.mqttBrokerPort,
    )

    /**
     * Changes the label of [field] to [label]. The Delimited format refuses `"`, `\`, `=`,
     * the delimiter, a line feed, and the other control characters. The topic layout "one topic
     * for each field" refuses `/`, `+`, `#`, and U+0000. The JSON format escapes the label. A
     * label has a maximum of 32 characters.
     */
    fun editLabel(config: DataOutputConfig, field: DataOutputFieldId, label: String): LabelEdit {
        if (label.length > DataOutputLimits.LABEL_LENGTH.last) return LabelEdit.TooLong
        refusedCharacter(config, label)?.let { character -> return LabelEdit.Refused(character) }
        val fields = config.fields.map { item -> if (item.id == field) item.copy(label = label) else item }
        return LabelEdit.Accepted(config.copy(fields = fields))
    }

    /** Gives the first character of [label] that the format of [config] refuses, or null. */
    fun refusedCharacter(config: DataOutputConfig, label: String): Char? = when {
        config.transport == DataOutputTransport.Tcp && config.format == DataOutputFormat.Delimited ->
            DataOutputValidation.delimitedRefusedCharacter(label, config.delimiter)
        config.transport == DataOutputTransport.Mqtt && config.mqttTopicLayout == MqttTopicLayout.TopicPerField ->
            DataOutputValidation.topicRefusedCharacter(label) ?: label.firstOrNull { character -> character.isISOControl() }
        else -> label.firstOrNull { character -> character.isISOControl() }
    }

    /** Sets the switch of [field]. */
    fun setField(config: DataOutputConfig, field: DataOutputFieldId, enabled: Boolean): DataOutputConfig =
        config.copy(fields = config.fields.map { item -> if (item.id == field) item.copy(enabled = enabled) else item })

    /**
     * Gives the profiles whose TCP output uses the port of [config]. [others] has the stored
     * configurations of the other
     * profiles. The check applies to the TCP transport of [config], also when its switch is off,
     * so that a later "on" does not fail. Only an enabled output of a different profile holds a
     * port.
     */
    fun portOwners(profileId: String, config: DataOutputConfig, others: Map<String, DataOutputConfig>): List<String> {
        if (config.transport != DataOutputTransport.Tcp) return emptyList()
        val all = others.filterKeys { id -> id != profileId } + (profileId to config.copy(enabled = true))
        return DataOutputValidation.portConflicts(all)[config.port].orEmpty().filter { id -> id != profileId }
    }

    /** Gives the integer of [text], or null when the text is not an integer. */
    fun parseInt(text: String): Int? = text.trim().takeIf { value -> value.isNotEmpty() && value.length <= 9 }?.toIntOrNull()

    /** Gives the integer of [text], or null when the text is not an integer. */
    fun parseLong(text: String): Long? = text.trim().takeIf { value -> value.isNotEmpty() && value.length <= 9 }?.toLongOrNull()
}
