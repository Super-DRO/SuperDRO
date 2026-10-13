package org.superdro.core.model

import kotlinx.serialization.Serializable

/** The network interface of the data output server. */
@Serializable
enum class BindInterface { WiFi, Localhost, Vpn, UsbEthernet, All }

/** The security mode of the data output. */
@Serializable
enum class DataOutputSecurity {
    /**
     * The stream is encrypted with a pre-shared key ("PSK mode"). The default.
     */
    Psk,

    /** The TouchDRO compatible records with no encryption. Only after the warning "Use only on trusted networks". */
    PlainText,
}

/** The transport of the data output. */
@Serializable
enum class DataOutputTransport { Tcp, Mqtt }

/** The MQTT topic layout. */
@Serializable
enum class MqttTopicLayout {
    /** One JSON record for each message on `superdro/<machine>`. */
    JsonRecord,

    /** One topic for each field, `superdro/<machine>/<field>`, with the number as the payload. */
    TopicPerField,
}

/** The record format. */
@Serializable
enum class DataOutputFormat { Delimited, Json }

/** The delimiter of the format [DataOutputFormat.Delimited]. */
@Serializable
enum class DataOutputDelimiter(val text: String) {
    Semicolon(";"),
    Comma(","),
    Tab("\t"),
    Pipe("|"),
}

/** The time stamp format. */
@Serializable
enum class TimestampFormat { Iso8601, EpochMs }

/** The unit of the length fields. */
@Serializable
enum class DistanceUnit { Inch, Mil, Millimeter, Micron, Meter }

/** The unit of the angle fields. */
@Serializable
enum class AngleUnit { DecimalDegrees, DegreesMinutesSeconds, Radians }

/** When the output sends a record. */
@Serializable
enum class DataOutputCadence {
    /** A record after each interval. */
    FixedInterval,

    /** A record after each change, with the rate limit. */
    OnChange,

    /** A record after each change, with the rate limit, and after an interval with no record. */
    OnChangeAndInterval,
}

/** The start of the data output. */
@Serializable
enum class DataOutputActivation {
    /** The output runs while its machine is online. */
    AlwaysOn,

    /** The output runs after a tap on the toolbar button. The default. */
    OnDemand,
}

/**
 * The 25 fields of a record, in the fixed order of the
 * records. [wireId] is the default label.
 */
@Serializable
enum class DataOutputFieldId(val wireId: String) {
    XAbsolute("x_absolute"),
    XIncremental("x_incremental"),
    XFeedRate("x_feed_rate"),
    YAbsolute("y_absolute"),
    YIncremental("y_incremental"),
    YFeedRate("y_feed_rate"),
    ZAbsolute("z_absolute"),
    ZIncremental("z_incremental"),
    ZFeedRate("z_feed_rate"),
    AngularAbsolute("angular_absolute"),
    AngularIncremental("angular_incremental"),
    AngularFeedRate("angular_feed_rate"),
    SpindleRpm("spindle_rpm"),
    SurfaceSpeed("surface_speed"),
    Probe1("probe_1"),
    Probe2("probe_2"),
    Probe1TriggerX("probe_1_trigger_x"),
    Probe1TriggerY("probe_1_trigger_y"),
    Probe1TriggerZ("probe_1_trigger_z"),
    Probe1TriggerDirX("probe_1_trigger_dir_x"),
    Probe1TriggerDirY("probe_1_trigger_dir_y"),
    Probe1TriggerDirZ("probe_1_trigger_dir_z"),
    Probe1TriggerTime("probe_1_trigger_time"),
    Probe1TriggerSeq("probe_1_trigger_seq"),
    Probe1TriggerFeed("probe_1_trigger_feed"),
}

/** One field of a record. The label has 1 to 32 characters. The labels of the enabled fields are unique. */
@Serializable
data class DataOutputField(
    val id: DataOutputFieldId = DataOutputFieldId.XAbsolute,
    val enabled: Boolean = false,
    val label: String = id.wireId,
)

/**
 * The data output configuration of one machine profile.
 * The output is off by default, and no server socket is open while it is off. The MQTT
 * credentials and the PSK stay in the `SecretStore`, not here.
 */
@Serializable
data class DataOutputConfig(
    val enabled: Boolean = false,
    val bindInterface: BindInterface = BindInterface.WiFi,
    val port: Int = DEFAULT_PORT,
    val security: DataOutputSecurity = DataOutputSecurity.Psk,
    /**
     * True after the user confirmed the warning "Use only on trusted networks" for
     * [DataOutputSecurity.PlainText]. The server does not start in the plain text mode
     * while this is false. The UI sets it to false when the user selects a different mode.
     */
    val plainTextConfirmed: Boolean = false,
    val transport: DataOutputTransport = DataOutputTransport.Tcp,
    val mqttBrokerHost: String = "",
    val mqttBrokerPort: Int = DEFAULT_MQTT_TLS_PORT,
    val mqttClientId: String = "",
    /** The `<machine>` part of the topic. Null gives the name of the profile. */
    val mqttMachineName: String? = null,
    val mqttTls: Boolean = true,
    /**
     * True after the user confirmed the same warning for a broker link with no TLS. The MQTT client
     * does not start without TLS while this is
     * false.
     */
    val mqttNoTlsConfirmed: Boolean = false,
    val mqttTopicLayout: MqttTopicLayout = MqttTopicLayout.JsonRecord,
    val format: DataOutputFormat = DataOutputFormat.Delimited,
    val delimiter: DataOutputDelimiter = DataOutputDelimiter.Semicolon,
    /** Include the field labels (`label=value`, Delimited only). */
    val labels: Boolean = false,
    val timestampEnabled: Boolean = true,
    val timestampFormat: TimestampFormat = TimestampFormat.Iso8601,
    val distanceUnit: DistanceUnit = DistanceUnit.Millimeter,
    val angleUnit: AngleUnit = AngleUnit.DecimalDegrees,
    /** The decimals of a number: 0 to 6. */
    val precision: Int = DEFAULT_PRECISION,
    val fields: List<DataOutputField> = defaultFields(),
    val cadence: DataOutputCadence = DataOutputCadence.OnChangeAndInterval,
    /** 50 to 5000 ms. */
    val intervalMs: Long = DEFAULT_INTERVAL_MS,
    /** 1 to 20 Hz. */
    val maxRateHz: Int = DEFAULT_MAX_RATE_HZ,
    val activation: DataOutputActivation = DataOutputActivation.OnDemand,
) {
    companion object {
        const val DEFAULT_PORT: Int = 5800
        const val DEFAULT_MQTT_TLS_PORT: Int = 8883
        const val DEFAULT_PRECISION: Int = 4
        const val DEFAULT_INTERVAL_MS: Long = 500L
        const val DEFAULT_MAX_RATE_HZ: Int = 20

        /** The fields that are on in a new configuration. */
        val DEFAULT_ENABLED_FIELDS: Set<DataOutputFieldId> =
            setOf(DataOutputFieldId.XAbsolute, DataOutputFieldId.YAbsolute, DataOutputFieldId.ZAbsolute)

        /** The 25 fields in the record order, with the default switches and labels. */
        fun defaultFields(): List<DataOutputField> = DataOutputFieldId.entries.map { id ->
            DataOutputField(id = id, enabled = id in DEFAULT_ENABLED_FIELDS, label = id.wireId)
        }
    }
}
