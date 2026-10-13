package org.superdro.core.functions.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.functions.tipcal.SpreadLimits
import org.superdro.core.model.AxisId
import org.superdro.core.model.Channels
import org.superdro.core.model.ProbeConfig
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.model.ReadoutFormat

/**
 * The origins, the samples, and the profile values of the edge finder.
 */
class ProbeTechniqueTest {

    @Test
    fun theThreeOriginFormulasForAnExternalFeature() {
        val a = 10000.0
        val b = 50000.0
        val r = 2000.0
        assertEquals(12000.0, EdgeOrigins.origin(OriginOption.SideA, a, b, FeatureType.External, r))
        assertEquals(30000.0, EdgeOrigins.origin(OriginOption.Center, a, b, FeatureType.External, r))
        assertEquals(48000.0, EdgeOrigins.origin(OriginOption.SideB, a, b, FeatureType.External, r))
    }

    @Test
    fun theThreeOriginFormulasForAnInternalFeature() {
        val a = 10000.0
        val b = 50000.0
        val r = 2000.0
        assertEquals(8000.0, EdgeOrigins.origin(OriginOption.SideA, a, b, FeatureType.Internal, r))
        assertEquals(30000.0, EdgeOrigins.origin(OriginOption.Center, a, b, FeatureType.Internal, r))
        assertEquals(52000.0, EdgeOrigins.origin(OriginOption.SideB, a, b, FeatureType.Internal, r))
    }

    @Test
    fun oneEdgeEnablesItsSideAndTwoEdgesEnableAllAndSelectCenter() {
        assertEquals(emptySet(), EdgeOrigins.enabledOptions(null, null))
        assertEquals(setOf(OriginOption.SideA), EdgeOrigins.enabledOptions(1.0, null))
        assertEquals(setOf(OriginOption.SideB), EdgeOrigins.enabledOptions(null, 1.0))
        assertEquals(OriginOption.entries.toSet(), EdgeOrigins.enabledOptions(1.0, 2.0))
        assertNull(EdgeOrigins.autoOption(null, null))
        assertEquals(OriginOption.SideA, EdgeOrigins.autoOption(1.0, null))
        assertEquals(OriginOption.SideB, EdgeOrigins.autoOption(null, 1.0))
        assertEquals(OriginOption.Center, EdgeOrigins.autoOption(1.0, 2.0))
        assertNull(EdgeOrigins.origin(OriginOption.Center, 1.0, null, FeatureType.External, 0.0))
    }

    @Test
    fun theSpreadIsMaxMinusMin() {
        // The first sample and the last sample are equal, but the spread is 50 µm.
        val samples = SampleSet().add(0.0).add(50.0).add(0.0)
        assertEquals(50.0, samples.spreadUm)
        assertEquals(50.0 / 3.0, samples.meanUm)
        assertEquals(3, samples.count)
        assertEquals(0.0, SampleSet().spreadUm)
        assertNull(SampleSet().meanUm)
    }

    @Test
    fun theEdgeFinderUsesTheOneSpreadLimitRule() {
        assertEquals(12.0, SpreadLimits.of(ProbeProfile(spreadLimitUm = 12.0, calibrationResidualUm = 9.0)).valueUm)
        assertEquals(27.0, SpreadLimits.of(ProbeProfile(spreadLimitUm = null, calibrationResidualUm = 9.0)).valueUm)
        assertEquals(10.0, SpreadLimits.of(ProbeProfile(spreadLimitUm = 0.0, calibrationResidualUm = 2.0)).valueUm)
        assertEquals(20.0, SpreadLimits.of(ProbeProfile(spreadLimitUm = null, calibrationResidualUm = null)).valueUm)
        assertEquals(20.0, SpreadLimits.of(ProbeProfile(spreadLimitUm = Double.NaN, calibrationResidualUm = -1.0)).valueUm)
        assertEquals(20.0, SpreadLimits.of(null).valueUm)
    }

    @Test
    fun theSampleCountAndTheDiameterComeFromTheProfile() {
        assertEquals(1, ProbeTechnique.sampleCount(null))
        assertEquals(3, ProbeTechnique.sampleCount(ProbeProfile(sampleCount = 3)))
        assertEquals(10, ProbeTechnique.sampleCount(ProbeProfile(sampleCount = 12)))
        assertEquals(1, ProbeTechnique.sampleCount(ProbeProfile(sampleCount = 0)))
        assertEquals(6000.0, ProbeTechnique.stylusDiameterUm(ProbeProfile(stylusDiameterUm = 6000.0)))
        assertEquals(5980.0, ProbeTechnique.stylusDiameterUm(ProbeProfile(stylusDiameterUm = 6000.0, effectiveDiameterUm = 5980.0)))
        assertEquals(0.0, ProbeTechnique.stylusDiameterUm(null))
    }

    @Test
    fun theSampleOffsetRemovesTheInstrumentOffset() {
        val profile = ProbeProfile(xOffsetUm = 100.0, yOffsetUm = -40.0, zOffsetUm = 7.0)
        assertEquals(100.0, ProbeTechnique.sampleOffsetUm(AxisId.X, profile))
        assertEquals(-40.0, ProbeTechnique.sampleOffsetUm(AxisId.Y, profile))
        assertEquals(0.0, ProbeTechnique.sampleOffsetUm(AxisId.Z, profile))
        assertEquals(70.0, ProbeTechnique.sampleOffsetUm(AxisId.X, profile, instrumentOffsetUm = 30.0))
        assertEquals(0.0, ProbeTechnique.sampleOffsetUm(AxisId.X, null))
        val settings = EdgeFinderSettings.from(
            ProbeConfig(enabled = true), profile, zEnabled = true, metricDisplay = true,
            instrumentOffsetXUm = 30.0, instrumentOffsetYUm = 10.0,
        )
        assertEquals(70.0, settings.xOffsetUm)
        assertEquals(-50.0, settings.yOffsetUm)
        assertEquals(7.0, settings.zOffsetUm)
    }

    @Test
    fun theSettingsOfAProfile() {
        val touch = ProbeProfile(type = ProbeType.TouchProbe, port = Channels.P, stylusDiameterUm = 4000.0, sampleCount = 2, spreadLimitUm = 15.0)
        val settings = EdgeFinderSettings.from(ProbeConfig(enabled = true), touch, zEnabled = true, metricDisplay = false, ReadoutFormat.Format5)
        assertEquals(2000.0, settings.stylusRadiusUm)
        assertEquals(2, settings.sampleCount)
        assertEquals(15.0, settings.spreadLimitUm)
        assertTrue(settings.probeOn)
        assertTrue(settings.autoVisible)
        assertTrue(settings.zCapture)
        val edgeFinder = touch.copy(type = ProbeType.EdgeFinder2D, port = null)
        val manual = EdgeFinderSettings.from(ProbeConfig(enabled = true), edgeFinder, zEnabled = true, metricDisplay = true)
        assertFalse(manual.probeOn)
        assertFalse(manual.zCapture)
        assertFalse(EdgeFinderSettings.from(ProbeConfig(enabled = false), touch, true, true).probeOn)
        assertFalse(EdgeFinderSettings.from(ProbeConfig(enabled = true, edgeFinderEnabled = false), touch, true, true).probeOn)
        assertTrue(ProbeTechnique.edgeFinderAccepts(ProbeType.Camera))
        assertFalse(ProbeTechnique.edgeFinderAccepts(ProbeType.ToolHeightSetter))
    }

    @Test
    fun theSpreadWarningShowsTheValueInTheDisplayUnit() {
        assertEquals(
            "Warning: maximum spread between the samples is 0.050 mm",
            EdgeFinderTexts.spreadWarning(50.0, metric = true, ReadoutFormat.Format3).text,
        )
        assertEquals(
            "Warning: maximum spread between the samples is 0.0020 in.",
            EdgeFinderTexts.spreadWarning(50.8, metric = false, ReadoutFormat.Format3).text,
        )
    }
}
