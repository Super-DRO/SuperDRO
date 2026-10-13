package org.superdro.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The defaults of the data output. */
class DataOutputDefaultsTest {

    private val config = MachineProfile.create().dataOutput

    @Test
    fun theOutputIsOffAndBindsToWifiOnPort5800() {
        assertFalse(config.enabled)
        assertEquals(BindInterface.WiFi, config.bindInterface)
        assertEquals(5800, config.port)
        assertEquals(DataOutputTransport.Tcp, config.transport)
    }

    @Test
    fun theSecurityModeIsPsk() {
        assertEquals(DataOutputSecurity.Psk, config.security)
    }

    @Test
    fun noLowerSecurityModeIsConfirmed() {
        assertFalse(config.plainTextConfirmed)
        assertFalse(config.mqttNoTlsConfirmed)
    }

    @Test
    fun mqttUsesTlsAndOneJsonRecordForEachMessage() {
        assertTrue(config.mqttTls)
        assertEquals(MqttTopicLayout.JsonRecord, config.mqttTopicLayout)
    }

    @Test
    fun theRecordIsDelimitedWithASemicolonAnIsoTimeAndMillimetersWith4Decimals() {
        assertEquals(DataOutputFormat.Delimited, config.format)
        assertEquals(";", config.delimiter.text)
        assertFalse(config.labels)
        assertTrue(config.timestampEnabled)
        assertEquals(TimestampFormat.Iso8601, config.timestampFormat)
        assertEquals(DistanceUnit.Millimeter, config.distanceUnit)
        assertEquals(AngleUnit.DecimalDegrees, config.angleUnit)
        assertEquals(4, config.precision)
    }

    @Test
    fun thereAre25FieldsAndOnlyTheAbsolutePositionsAreOn() {
        assertEquals(25, config.fields.size)
        assertEquals(DataOutputFieldId.entries, config.fields.map { field -> field.id })
        assertEquals(listOf("x_absolute", "y_absolute", "z_absolute"), config.fields.filter { field -> field.enabled }.map { field -> field.label })
        assertEquals("probe_1_trigger_feed", config.fields.last().label)
    }

    @Test
    fun theCadenceIsOnChangeAndIntervalWith500MsAnd20Hz() {
        assertEquals(DataOutputCadence.OnChangeAndInterval, config.cadence)
        assertEquals(500L, config.intervalMs)
        assertEquals(20, config.maxRateHz)
    }

    @Test
    fun theActivationIsOnDemand() {
        assertEquals(DataOutputActivation.OnDemand, config.activation)
    }
}
