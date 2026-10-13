package org.superdro.core.ui.dataoutput

import org.jetbrains.compose.resources.StringResource
import org.superdro.core.dataoutput.ConfigProblem
import org.superdro.core.dataoutput.DataOutputError
import org.superdro.core.dataoutput.DataOutputErrorType
import org.superdro.core.dataoutput.DataOutputStatus
import org.superdro.core.dataoutput.StartBlock
import org.superdro.core.dataoutput.TestConnectionResult
import org.superdro.core.dataoutput.hostAndPort
import org.superdro.core.dataoutput.nameKey
import org.superdro.core.model.BindInterface
import org.superdro.core.model.DataOutputFieldId
import org.superdro.core.resources.Res
import org.superdro.core.resources.data_output_error_accept
import org.superdro.core.resources.data_output_error_auth
import org.superdro.core.resources.data_output_error_bind
import org.superdro.core.resources.data_output_error_broker
import org.superdro.core.resources.data_output_error_handshake
import org.superdro.core.resources.data_output_error_lost
import org.superdro.core.resources.data_output_error_no_address
import org.superdro.core.resources.data_output_error_port_taken
import org.superdro.core.resources.data_output_error_publish
import org.superdro.core.resources.data_output_error_tls
import org.superdro.core.resources.data_output_error_write
import org.superdro.core.resources.data_output_interface_all
import org.superdro.core.resources.data_output_interface_localhost
import org.superdro.core.resources.data_output_interface_usb_ethernet
import org.superdro.core.resources.data_output_interface_vpn
import org.superdro.core.resources.data_output_interface_wifi
import org.superdro.core.resources.data_output_label_character
import org.superdro.core.resources.data_output_label_duplicate
import org.superdro.core.resources.data_output_label_length
import org.superdro.core.resources.data_output_label_reserved
import org.superdro.core.resources.data_output_status_ready
import org.superdro.core.resources.data_output_status_sending
import org.superdro.core.resources.data_output_status_starting
import org.superdro.core.resources.data_output_status_stopped
import org.superdro.core.resources.data_output_status_warning
import org.superdro.core.resources.data_output_test_broker_ok
import org.superdro.core.resources.data_output_test_port_busy
import org.superdro.core.resources.data_output_test_port_free
import org.superdro.core.resources.data_output_test_running
import org.superdro.core.resources.dataoutput_age_hours
import org.superdro.core.resources.dataoutput_age_minutes
import org.superdro.core.resources.dataoutput_age_seconds
import org.superdro.core.resources.dataoutput_block_invalid
import org.superdro.core.resources.dataoutput_block_mqtt_tls
import org.superdro.core.resources.dataoutput_block_not_requested
import org.superdro.core.resources.dataoutput_block_off
import org.superdro.core.resources.dataoutput_block_offline
import org.superdro.core.resources.dataoutput_block_plain
import org.superdro.core.resources.dataoutput_broker_host_problem
import org.superdro.core.resources.dataoutput_broker_port_range
import org.superdro.core.resources.dataoutput_client_id_length
import org.superdro.core.resources.dataoutput_correct_first
import org.superdro.core.resources.dataoutput_field_list
import org.superdro.core.resources.dataoutput_interval_range
import org.superdro.core.resources.dataoutput_label_topic_character
import org.superdro.core.resources.dataoutput_machine_name_problem
import org.superdro.core.resources.dataoutput_no_detail
import org.superdro.core.resources.dataoutput_port_range
import org.superdro.core.resources.dataoutput_rate_range
import org.superdro.core.ui.text.UiText

/** The field of the data output screen that a problem belongs to. */
sealed interface ProblemTarget {
    data object Port : ProblemTarget
    data object Interval : ProblemTarget
    data object MaxRate : ProblemTarget
    data object BrokerHost : ProblemTarget
    data object BrokerPort : ProblemTarget
    data object ClientId : ProblemTarget
    data object MachineName : ProblemTarget
    data object General : ProblemTarget

    /** The label of [field]. */
    data class Label(val field: DataOutputFieldId) : ProblemTarget
}

/** One problem of the settings, with the field that shows it. */
data class FormProblem(val target: ProblemTarget, val text: UiText)

/**
 * The texts of the data output. The texts use the strings of
 * `strings_data_output.xml` and `strings_dataoutput_screen.xml`.
 */
object DataOutputTexts {

    /** The status text. */
    fun status(status: DataOutputStatus): StringResource = when (status) {
        DataOutputStatus.Stopped -> Res.string.data_output_status_stopped
        DataOutputStatus.Starting -> Res.string.data_output_status_starting
        DataOutputStatus.Ready -> Res.string.data_output_status_ready
        DataOutputStatus.Sending -> Res.string.data_output_status_sending
        DataOutputStatus.Warning -> Res.string.data_output_status_warning
    }

    /** The name of a bind interface. */
    fun bindInterface(bind: BindInterface): StringResource = when (bind) {
        BindInterface.WiFi -> Res.string.data_output_interface_wifi
        BindInterface.Localhost -> Res.string.data_output_interface_localhost
        BindInterface.Vpn -> Res.string.data_output_interface_vpn
        BindInterface.UsbEthernet -> Res.string.data_output_interface_usb_ethernet
        BindInterface.All -> Res.string.data_output_interface_all
    }

    /** The reason why the output does not run, or null. */
    fun block(block: StartBlock?): UiText? = when (block) {
        null -> null
        StartBlock.NotEnabled -> UiText.of(Res.string.dataoutput_block_off)
        StartBlock.MachineOffline -> UiText.of(Res.string.dataoutput_block_offline)
        StartBlock.NotRequested -> UiText.of(Res.string.dataoutput_block_not_requested)
        StartBlock.PlainTextNotConfirmed -> UiText.of(Res.string.dataoutput_block_plain)
        StartBlock.MqttWithoutTlsNotConfirmed -> UiText.of(Res.string.dataoutput_block_mqtt_tls)
        is StartBlock.InvalidConfig -> UiText.of(Res.string.dataoutput_block_invalid)
        is StartBlock.PortTaken -> UiText.of(Res.string.data_output_error_port_taken, block.port)
    }

    /** The message of an error of the output. [mqtt] selects the broker texts. */
    fun error(error: DataOutputError, mqtt: Boolean): UiText {
        val where = error.host?.let { host -> hostAndPort(host, error.port) } ?: "?"
        val detail: Any = error.detail ?: UiText.of(Res.string.dataoutput_no_detail)
        return when (error.type) {
            DataOutputErrorType.PortBindFailed -> UiText.of(Res.string.data_output_error_bind, where, detail)
            DataOutputErrorType.AcceptFailed -> UiText.of(Res.string.data_output_error_accept, detail)
            DataOutputErrorType.NoAddress -> UiText.of(Res.string.data_output_error_no_address)
            DataOutputErrorType.PortTaken -> UiText.of(Res.string.data_output_error_port_taken, error.port ?: 0)
            DataOutputErrorType.HandshakeFailed -> UiText.of(Res.string.data_output_error_handshake, error.host ?: "?")
            DataOutputErrorType.PublishFailed ->
                if (mqtt) UiText.of(Res.string.data_output_error_publish, where, detail)
                else UiText.of(Res.string.data_output_error_write, error.host ?: "?", detail)
            DataOutputErrorType.BrokerUnreachable -> UiText.of(Res.string.data_output_error_broker, where, detail)
            DataOutputErrorType.AuthRejected -> UiText.of(Res.string.data_output_error_auth, where)
            DataOutputErrorType.TlsFailure -> UiText.of(Res.string.data_output_error_tls, where, detail)
            DataOutputErrorType.ConnectionLost -> UiText.of(Res.string.data_output_error_lost, where, detail)
        }
    }

    /** The result text of "Test connection". */
    fun testResult(result: TestConnectionResult): UiText = when (result) {
        is TestConnectionResult.PortFree -> UiText.of(Res.string.data_output_test_port_free, result.port, result.addresses.joinToString(", "))
        is TestConnectionResult.PortNotFree ->
            UiText.of(Res.string.data_output_test_port_busy, result.port, result.host, result.detail ?: UiText.of(Res.string.dataoutput_no_detail))
        is TestConnectionResult.Running ->
            UiText.of(Res.string.data_output_test_running, result.addresses.joinToString(", ").ifEmpty { "?" }, result.clients)
        is TestConnectionResult.PortTaken -> UiText.of(Res.string.data_output_error_port_taken, result.port)
        is TestConnectionResult.NoAddress -> UiText.of(Res.string.data_output_error_no_address)
        is TestConnectionResult.BrokerReachable -> UiText.of(Res.string.data_output_test_broker_ok, hostAndPort(result.host, result.port))
        is TestConnectionResult.BrokerFailed -> error(result.error, mqtt = true)
        is TestConnectionResult.InvalidConfig -> UiText.of(Res.string.dataoutput_correct_first)
    }

    /** True when [result] tells that the test passed. */
    fun testPassed(result: TestConnectionResult): Boolean =
        result is TestConnectionResult.PortFree || result is TestConnectionResult.Running || result is TestConnectionResult.BrokerReachable

    /** The problem of a configuration rule, with its field. */
    fun problem(problem: ConfigProblem): FormProblem = when (problem) {
        is ConfigProblem.LabelLength -> FormProblem(ProblemTarget.Label(problem.field), UiText.of(Res.string.data_output_label_length))
        is ConfigProblem.LabelDuplicate -> FormProblem(
            problem.fields.firstOrNull()?.let { field -> ProblemTarget.Label(field) } ?: ProblemTarget.General,
            UiText.of(Res.string.data_output_label_duplicate, problem.label),
        )
        is ConfigProblem.LabelCharacter ->
            FormProblem(ProblemTarget.Label(problem.field), UiText.of(Res.string.data_output_label_character, characterText(problem.character)))
        is ConfigProblem.LabelReserved -> FormProblem(ProblemTarget.Label(problem.field), UiText.of(Res.string.data_output_label_reserved))
        is ConfigProblem.LabelTopicCharacter ->
            FormProblem(ProblemTarget.Label(problem.field), UiText.of(Res.string.dataoutput_label_topic_character, characterText(problem.character)))
        is ConfigProblem.FieldList -> FormProblem(ProblemTarget.General, UiText.of(Res.string.dataoutput_field_list))
        is ConfigProblem.Port -> FormProblem(ProblemTarget.Port, UiText.of(Res.string.dataoutput_port_range))
        is ConfigProblem.Interval -> FormProblem(ProblemTarget.Interval, UiText.of(Res.string.dataoutput_interval_range))
        is ConfigProblem.MaxRate -> FormProblem(ProblemTarget.MaxRate, UiText.of(Res.string.dataoutput_rate_range))
        is ConfigProblem.Precision -> FormProblem(ProblemTarget.General, UiText.of(Res.string.dataoutput_correct_first))
        is ConfigProblem.BrokerHost -> FormProblem(ProblemTarget.BrokerHost, UiText.of(Res.string.dataoutput_broker_host_problem))
        is ConfigProblem.BrokerPort -> FormProblem(ProblemTarget.BrokerPort, UiText.of(Res.string.dataoutput_broker_port_range))
        is ConfigProblem.ClientId -> FormProblem(ProblemTarget.ClientId, UiText.of(Res.string.dataoutput_client_id_length))
        is ConfigProblem.MachineName -> FormProblem(ProblemTarget.MachineName, UiText.of(Res.string.dataoutput_machine_name_problem))
    }

    /**
     * The text of a refused character: the character in quotes, or the code `U+000A` for a
     * character that does not show (a line feed, a tab, or a different control character).
     */
    fun characterText(character: Char): String =
        if (character.isISOControl() || character.isWhitespace()) {
            "U+" + character.code.toString(16).uppercase().padStart(4, '0')
        } else {
            "\"$character\""
        }

    /** The age text of [ms]: seconds below 2 minutes, minutes below 2 hours, else hours. */
    fun age(ms: Long): UiText {
        val seconds = (ms.coerceAtLeast(0L) / 1000L).toInt()
        return when {
            seconds < 120 -> UiText.of(Res.string.dataoutput_age_seconds, seconds)
            seconds < 7200 -> UiText.of(Res.string.dataoutput_age_minutes, seconds / 60)
            else -> UiText.of(Res.string.dataoutput_age_hours, seconds / 3600)
        }
    }

    /** The key of the name of [field] in `strings_data_output.xml`. */
    fun fieldNameKey(field: DataOutputFieldId): String = field.nameKey
}
