package org.superdro.core.dataoutput

import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputDelimiter
import org.superdro.core.model.DataOutputFieldId
import org.superdro.core.model.DataOutputFormat
import org.superdro.core.model.DataOutputTransport
import org.superdro.core.model.MqttTopicLayout

/** One problem of a data output configuration. The settings screen shows it and refuses the save. */
sealed interface ConfigProblem {

    /** The label of an enabled field does not have 1 to 32 characters. */
    data class LabelLength(val field: DataOutputFieldId, val length: Int) : ConfigProblem

    /** More than one enabled field has the label [label]. */
    data class LabelDuplicate(val label: String, val fields: List<DataOutputFieldId>) : ConfigProblem

    /**
     * The label of an enabled field has a character that the Delimited format refuses: `"`,
     * `\`, `=`, the delimiter, a line feed, or a different control character.
     */
    data class LabelCharacter(val field: DataOutputFieldId, val character: Char) : ConfigProblem

    /**
     * The label of an enabled field is `timestamp` or `sequence`, the keys of the time stamp and
     * the record number. Such a label gives two members with the same key.
     */
    data class LabelReserved(val field: DataOutputFieldId, val label: String) : ConfigProblem

    /** The label of an enabled field cannot be an MQTT topic level: `/`, `+`, `#`, or U+0000. */
    data class LabelTopicCharacter(val field: DataOutputFieldId, val character: Char) : ConfigProblem

    /** The field list does not have each of the 25 fields one time. */
    data class FieldList(val missing: List<DataOutputFieldId>, val repeated: List<DataOutputFieldId>) : ConfigProblem

    /** The port is not in 1024 to 65535. */
    data class Port(val port: Int) : ConfigProblem

    /** The interval is not in 50 to 5000 ms. */
    data class Interval(val intervalMs: Long) : ConfigProblem

    /** The rate limit is not in 1 to 20 Hz. */
    data class MaxRate(val maxRateHz: Int) : ConfigProblem

    /** The precision is not in 0 to 6. */
    data class Precision(val precision: Int) : ConfigProblem

    /** The MQTT broker host is blank or has more than 253 characters. */
    data class BrokerHost(val host: String) : ConfigProblem

    /** The MQTT broker port is not in 1 to 65535. */
    data class BrokerPort(val port: Int) : ConfigProblem

    /** The MQTT client ID has more than 64 characters. */
    data class ClientId(val clientId: String) : ConfigProblem

    /** The MQTT machine name is empty, has more than 64 characters, or has `/`, `+`, `#`, or U+0000. */
    data class MachineName(val name: String) : ConfigProblem
}

/** The configuration rules of the data output. */
object DataOutputValidation {

    /** The broker port range. */
    val BROKER_PORT: IntRange = 1..65535

    /** The longest broker host name. */
    const val BROKER_HOST_MAX_LENGTH: Int = 253

    /** The longest client ID and machine name. */
    const val NAME_MAX_LENGTH: Int = 64

    /** The characters that each label of the Delimited format refuses, in addition to the delimiter. */
    private val DELIMITED_REFUSED: Set<Char> = setOf('"', '\\', '=', '\n')

    /** The keys of the time stamp and the record number. */
    private val RESERVED_LABELS: Set<String> = setOf("timestamp", "sequence")

    /** The characters that an MQTT topic level refuses. */
    private val TOPIC_REFUSED: Set<Char> = setOf('/', '+', '#', '\u0000')

    /** Gives the problems of [config]. An empty list means that the configuration is valid. */
    fun problems(config: DataOutputConfig): List<ConfigProblem> = buildList {
        if (config.port !in DataOutputLimits.PORT) add(ConfigProblem.Port(config.port))
        if (config.intervalMs !in DataOutputLimits.INTERVAL_MS) add(ConfigProblem.Interval(config.intervalMs))
        if (config.maxRateHz !in DataOutputLimits.MAX_RATE_HZ) add(ConfigProblem.MaxRate(config.maxRateHz))
        if (config.precision !in DataOutputLimits.PRECISION) add(ConfigProblem.Precision(config.precision))
        addAll(fieldListProblems(config))
        addAll(labelProblems(config))
        if (config.transport == DataOutputTransport.Mqtt) addAll(mqttProblems(config))
    }

    /**
     * Gives the first character of [label] that the Delimited format with [delimiter] refuses,
     * or null when the label is valid.
     */
    fun delimitedRefusedCharacter(label: String, delimiter: DataOutputDelimiter): Char? =
        label.firstOrNull { char -> char in DELIMITED_REFUSED || delimiter.text.contains(char) || char.isISOControl() }

    /** Gives the first character of [text] that an MQTT topic level refuses, or null. */
    fun topicRefusedCharacter(text: String): Char? = text.firstOrNull { char -> char in TOPIC_REFUSED }

    /**
     * Gives the ports that more than one enabled TCP output uses, with the owners (for example
     * the machine profile IDs). Two outputs cannot use the same port.
     */
    fun portConflicts(configs: Map<String, DataOutputConfig>): Map<Int, List<String>> =
        configs.filterValues { config -> config.enabled && config.transport == DataOutputTransport.Tcp }
            .entries
            .groupBy({ entry -> entry.value.port }, { entry -> entry.key })
            .filterValues { owners -> owners.size > 1 }
            .mapValues { (_, owners) -> owners.sorted() }

    private fun fieldListProblems(config: DataOutputConfig): List<ConfigProblem> {
        val ids = config.fields.map { field -> field.id }
        val missing = DataOutputFieldId.entries.filter { id -> id !in ids }
        val repeated = ids.groupingBy { id -> id }.eachCount().filterValues { count -> count > 1 }.keys.toList()
        return if (missing.isEmpty() && repeated.isEmpty()) emptyList() else listOf(ConfigProblem.FieldList(missing, repeated))
    }

    private fun labelProblems(config: DataOutputConfig): List<ConfigProblem> = buildList {
        val enabled = config.fields.filter { field -> field.enabled }
        val delimited = config.transport == DataOutputTransport.Tcp && config.format == DataOutputFormat.Delimited
        val topicLevels = config.transport == DataOutputTransport.Mqtt && config.mqttTopicLayout == MqttTopicLayout.TopicPerField
        enabled.forEach { field ->
            if (field.label.length !in DataOutputLimits.LABEL_LENGTH) add(ConfigProblem.LabelLength(field.id, field.label.length))
            if (field.label in RESERVED_LABELS) add(ConfigProblem.LabelReserved(field.id, field.label))
            if (delimited) {
                delimitedRefusedCharacter(field.label, config.delimiter)?.let { char -> add(ConfigProblem.LabelCharacter(field.id, char)) }
            }
            if (topicLevels) {
                topicRefusedCharacter(field.label)?.let { char -> add(ConfigProblem.LabelTopicCharacter(field.id, char)) }
            }
        }
        enabled.groupBy { field -> field.label }
            .filterValues { fields -> fields.size > 1 }
            .forEach { (label, fields) -> add(ConfigProblem.LabelDuplicate(label, fields.map { field -> field.id })) }
    }

    private fun mqttProblems(config: DataOutputConfig): List<ConfigProblem> = buildList {
        val host = config.mqttBrokerHost
        if (host.isBlank() || host.length > BROKER_HOST_MAX_LENGTH) add(ConfigProblem.BrokerHost(host))
        if (config.mqttBrokerPort !in BROKER_PORT) add(ConfigProblem.BrokerPort(config.mqttBrokerPort))
        if (config.mqttClientId.length > NAME_MAX_LENGTH) add(ConfigProblem.ClientId(config.mqttClientId))
        config.mqttMachineName?.let { name ->
            if (name.isEmpty() || name.length > NAME_MAX_LENGTH || topicRefusedCharacter(name) != null) {
                add(ConfigProblem.MachineName(name))
            }
        }
    }
}

/** The configuration has problems, so the output cannot use it. */
class InvalidConfigException(val problems: List<ConfigProblem>) :
    IllegalArgumentException("The data output configuration is not valid: $problems")
