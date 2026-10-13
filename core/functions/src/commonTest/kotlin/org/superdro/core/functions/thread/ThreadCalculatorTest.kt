package org.superdro.core.functions.thread

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.superdro.core.model.ThreadClass
import org.superdro.core.model.ThreadCutter
import org.superdro.core.model.ThreadForm

class ThreadCalculatorTest {

    private val m10 = assertNotNull(ThreadTables.find(ThreadForm.IsoMetricCoarse, "M10 × 1.5"))
    private val quarter20 = assertNotNull(ThreadTables.find(ThreadForm.Unc, "1/4-20"))

    private fun mm(um: Double) = ThreadText.length(um, ThreadUnit.Millimeter)
    private fun inch(um: Double) = ThreadText.length(um, ThreadUnit.Inch)

    @Test
    fun theBasicProfileOfM10UsesHEqualTo0Point8660254TimesP() {
        val g = ThreadCalculator.standard(m10, internal = false, ThreadCutter.SharpV, ThreadClass.Class6g)
        assertEquals(1299.0381, g.heightUm, 1e-6)
        assertEquals(9025.721425, g.basicPitchDiameterUm, 1e-6)
        assertEquals(8376.202375, g.basicMinorDiameterUm, 1e-6)
        assertEquals("1.299", mm(g.heightUm))
        assertEquals("9.026", mm(g.basicPitchDiameterUm))
    }

    @Test
    fun m10By1Point5Class6gExternalSharpVTurnsTo9Point968AndCutsTo7Point695() {
        val g = ThreadCalculator.standard(m10, internal = false, ThreadCutter.SharpV, ThreadClass.Class6g)
        val t = assertNotNull(g.tolerances)
        assertEquals(-32.0, t.deviationUm)
        assertEquals(132.0, t.pitchDiameterToleranceUm)
        assertEquals(236.0, t.crestDiameterToleranceUm)
        assertNull(g.classStatus)
        assertEquals(9968.0, g.preOp.valueUm, 1e-9)
        assertEquals("9.968", mm(g.preOp.valueUm))
        assertEquals("9.732", mm(g.preOp.minUm))
        assertEquals("9.968", mm(g.preOp.maxUm))
        assertEquals(0.0, g.rootRadiusUm)
        assertEquals(7694.683325, g.cutToDiameterUm, 1e-6)
        assertEquals("7.695", mm(g.cutToDiameterUm))
        assertEquals(g.preOp.valueUm, g.majorDiameterUm)
        assertEquals(g.cutToDiameterUm, g.minorDiameterUm)
        assertEquals(g.preOp.valueUm, g.crestDiameterUm)
        assertEquals(g.cutToDiameterUm, g.rootDiameterUm)
    }

    @Test
    fun m10By1Point5Class6gExternalFullFormTurnsTo10AndCutsTo8Point128() {
        val g = ThreadCalculator.standard(m10, internal = false, ThreadCutter.FullForm, ThreadClass.Class6g)
        assertEquals("10.000", mm(g.preOp.valueUm))
        assertEquals("10.000", mm(g.preOp.minUm))
        assertEquals(10000.0 + 0.25 * g.heightUm, g.preOp.maxUm, 1e-9)
        assertEquals(g.heightUm / 6.0, g.rootRadiusUm, 1e-9)
        assertEquals(8127.696025, g.cutToDiameterUm, 1e-6)
        assertEquals("8.128", mm(g.cutToDiameterUm))
    }

    @Test
    fun m10By1Point5Class6HInternalSharpVBoresTo8Point376AndCutsTo10Point325() {
        val g = ThreadCalculator.standard(m10, internal = true, ThreadCutter.SharpV, ThreadClass.Class6H)
        val t = assertNotNull(g.tolerances)
        assertEquals(0.0, t.deviationUm)
        assertEquals(180.0, t.pitchDiameterToleranceUm)
        assertEquals(300.0, t.crestDiameterToleranceUm)
        assertEquals("8.376", mm(g.preOp.valueUm))
        assertEquals("8.376", mm(g.preOp.minUm))
        assertEquals("8.676", mm(g.preOp.maxUm))
        assertEquals(10324.759525, g.cutToDiameterUm, 1e-6)
        assertEquals("10.325", mm(g.cutToDiameterUm))
        assertEquals(g.preOp.valueUm, g.minorDiameterUm)
        assertEquals(g.cutToDiameterUm, g.majorDiameterUm)
    }

    @Test
    fun m10By1Point5Class6HInternalFullFormBoresTo8Point376AndCutsTo10() {
        val g = ThreadCalculator.standard(m10, internal = true, ThreadCutter.FullForm, ThreadClass.Class6H)
        assertEquals("8.376", mm(g.preOp.valueUm))
        assertEquals(g.basicMinorDiameterUm - 0.5 * g.heightUm, g.preOp.minUm, 1e-9)
        assertEquals(g.heightUm / 8.0, g.rootRadiusUm, 1e-9)
        assertEquals(10000.0, g.cutToDiameterUm, 1e-6)
        assertEquals("10.000", mm(g.cutToDiameterUm))
    }

    @Test
    fun quarter20UncClass2AExternalUsesTheAsmeFormulas() {
        val g = ThreadCalculator.standard(quarter20, internal = false, ThreadCutter.SharpV, ThreadClass.Class2A)
        assertEquals(0.043301, g.heightUm / 25400.0, 5e-7)
        assertEquals(0.217524, g.basicPitchDiameterUm / 25400.0, 5e-7)
        val t = assertNotNull(g.tolerances)
        assertEquals(-0.0011 * 25400.0, t.deviationUm, 1e-9)
        assertEquals(0.0037 * 25400.0, t.pitchDiameterToleranceUm, 1e-9)
        assertEquals(0.0081 * 25400.0, t.crestDiameterToleranceUm, 1e-9)
        assertEquals("0.2489", inch(g.preOp.valueUm))
        assertEquals("0.2408", inch(g.preOp.minUm))
        assertEquals("0.2489", inch(g.preOp.maxUm))
        assertEquals("0.1731", inch(g.cutToDiameterUm))
        val full = ThreadCalculator.standard(quarter20, internal = false, ThreadCutter.FullForm, ThreadClass.Class2A)
        assertEquals("0.2500", inch(full.preOp.valueUm))
        assertEquals("0.1876", inch(full.cutToDiameterUm))
    }

    @Test
    fun quarter20UncClass2BInternalUsesTheAsmeFormulas() {
        val g = ThreadCalculator.standard(quarter20, internal = true, ThreadCutter.SharpV, ThreadClass.Class2B)
        val t = assertNotNull(g.tolerances)
        assertEquals(0.0, t.deviationUm)
        assertEquals(0.0048 * 25400.0, t.pitchDiameterToleranceUm, 1e-9)
        assertEquals(0.0115 * 25400.0, t.crestDiameterToleranceUm, 1e-9)
        assertEquals("0.1959", inch(g.preOp.valueUm))
        assertEquals("0.1959", inch(g.preOp.minUm))
        assertEquals("0.2074", inch(g.preOp.maxUm))
        assertEquals("0.2608", inch(g.cutToDiameterUm))
        val full = ThreadCalculator.standard(quarter20, internal = true, ThreadCutter.FullForm, ThreadClass.Class2B)
        assertEquals("0.1959", inch(full.preOp.valueUm))
        assertEquals("0.2500", inch(full.cutToDiameterUm))
    }

    @Test
    fun theClass3AHasNoDeviationAndThreeQuartersOfTheBase() {
        val t = AsmeB11.external(class3A = true, majorUm = 6350.0, pitchUm = 1270.0, engagementUm = 6350.0)
        assertEquals(0.0, t.deviationUm)
        assertEquals(AsmeB11.round4(0.75 * 0.003730754) * 25400.0, t.pitchDiameterToleranceUm, 1e-9)
        val b = AsmeB11.internal(class3B = true, majorUm = 6350.0, pitchUm = 1270.0, engagementUm = 6350.0)
        assertEquals(AsmeB11.round4(0.975 * 0.003730754) * 25400.0, b.pitchDiameterToleranceUm, 1e-9)
        // t = 0.03·P/D + 0.05·∛(P²) − 0.002 = 0.010786; max(t, 0.12·P = 0.006) = 0.010786.
        assertEquals(0.0108 * 25400.0, b.crestDiameterToleranceUm, 1e-9)
    }

    @Test
    fun round4RoundsAHalfAwayFromZero() {
        assertEquals(0.0012, AsmeB11.round4(0.00116))
        assertEquals(-0.0012, AsmeB11.round4(-0.00116))
        assertEquals(-0.0011, AsmeB11.round4(-0.00114))
        assertEquals(0.0011, AsmeB11.round4(0.00114))
    }

    @Test
    fun aPartialCutterWithNoTipRadiusStopsAtTheFullFormRoot() {
        val partial = ThreadCalculator.standard(m10, internal = false, ThreadCutter.Partial, ThreadClass.Class6g)
        val sharp = ThreadCalculator.standard(m10, internal = false, ThreadCutter.SharpV, ThreadClass.Class6g)
        val full = ThreadCalculator.standard(m10, internal = false, ThreadCutter.FullForm, ThreadClass.Class6g)
        assertEquals(sharp.preOp, partial.preOp)
        assertEquals(full.rootRadiusUm, partial.rootRadiusUm, 1e-9)
        assertEquals(full.cutToDiameterUm, partial.cutToDiameterUm, 1e-9)
    }

    @Test
    fun aPartialCutterUsesItsTipRadiusLimitedToTheFullFormRadius() {
        val partial = ThreadCalculator.standard(m10, internal = false, ThreadCutter.Partial, ThreadClass.Class6g, cutterTipRadiusUm = 100.0)
        assertEquals(100.0, partial.rootRadiusUm)
        assertEquals(7694.683325 + 200.0, partial.cutToDiameterUm, 1e-6)
        val large = ThreadCalculator.standard(m10, internal = false, ThreadCutter.Partial, ThreadClass.Class6g, cutterTipRadiusUm = 900.0)
        assertEquals(large.heightUm / 6.0, large.rootRadiusUm, 1e-9)
        val negative = ThreadCalculator.rootRadius(ThreadCutter.Partial, internal = true, heightUm = 800.0, cutterTipRadiusUm = -5.0)
        assertEquals(0.0, negative)
        assertEquals(0.0, ThreadCalculator.rootRadius(ThreadCutter.SharpV, internal = true, heightUm = 800.0, cutterTipRadiusUm = 50.0))
    }

    @Test
    fun theThreeWireValuesOfAnExternalThreadAreShown() {
        val g = ThreadCalculator.standard(m10, internal = false, ThreadCutter.SharpV, ThreadClass.Class6g)
        val wires = assertNotNull(g.threeWire)
        assertEquals(866.02545, wires.wireDiameterUm, 1e-6)
        assertEquals(10292.759675, wires.measurementUm, 1e-6)
        assertEquals(wires.measurementUm, assertNotNull(wires.measurementMaxUm), 1e-9)
        assertEquals(wires.measurementUm - 132.0, assertNotNull(wires.measurementMinUm), 1e-6)
        assertNull(ThreadCalculator.standard(m10, internal = true, ThreadCutter.SharpV, ThreadClass.Class6H).threeWire)
    }

    @Test
    fun aSizeOutsideTheIsoTablesGivesTheBasicProfile() {
        val odd = ThreadSize("M3 × 1.5", ThreadForm.IsoMetricFine, 3000.0, 1500.0)
        val g = ThreadCalculator.standard(odd, internal = false, ThreadCutter.SharpV, ThreadClass.Class6g)
        assertEquals(ClassStatus.OutsideTable, g.classStatus)
        assertNull(g.limits)
        assertEquals(g.basicPitchDiameterUm, g.targetPitchDiameterUm)
        assertEquals(DiameterTarget(3000.0, 3000.0, 3000.0), g.preOp)
        assertEquals(true, g.showsNoToleranceNotice)
    }

    @Test
    fun aClassOfTheWrongTypeOrUnitGivesItsStatus() {
        assertEquals(ClassStatus.WrongType, ThreadCalculator.standard(m10, internal = true, ThreadCutter.SharpV, ThreadClass.Class6g).classStatus)
        assertEquals(ClassStatus.WrongUnit, ThreadCalculator.standard(m10, internal = false, ThreadCutter.SharpV, ThreadClass.Class2A).classStatus)
        assertEquals(ClassStatus.WrongUnit, ThreadCalculator.standard(quarter20, internal = true, ThreadCutter.SharpV, ThreadClass.Class6H).classStatus)
        val none = ThreadCalculator.standard(m10, internal = true, ThreadCutter.SharpV, null)
        assertNull(none.classStatus)
        assertEquals(none.basicPitchDiameterUm, none.targetPitchDiameterUm)
        assertEquals(none.basicMinorDiameterUm, none.preOp.valueUm)
    }

    @Test
    fun theIsoRangesExcludeTheLowerLimit() {
        assertEquals(1, Iso965.rangeIndex(5600.0))
        assertEquals(2, Iso965.rangeIndex(5600.1))
        assertEquals(-1, Iso965.rangeIndex(1500.0))
        assertEquals(-1, Iso965.rangeIndex(400000.0))
    }

    @Test
    fun aCustomThreadUsesTheTypedDiameters() {
        val g = ThreadCalculator.custom(ThreadForm.CustomMetric, internal = false, ThreadCutter.FullForm, 12000.0, 10000.0, 1500.0)
        assertEquals(12000.0, g.preOp.valueUm)
        assertEquals(10000.0, g.cutToDiameterUm)
        assertEquals(12000.0, g.majorDiameterUm)
        assertEquals(10000.0, g.minorDiameterUm)
        assertNull(g.classStatus)
        val i = ThreadCalculator.custom(ThreadForm.CustomImperial, internal = true, ThreadCutter.SharpV, 12700.0, 10800.0, 1953.8)
        assertEquals(10800.0, i.preOp.valueUm)
        assertEquals(12700.0, i.cutToDiameterUm)
        assertEquals(ThreadUnit.Inch, i.unit)
    }

    @Test
    fun theClassListDependsOnTheFormAndTheType() {
        assertEquals(listOf(ThreadClass.Class6H), classesFor(ThreadForm.IsoMetricFine, internal = true))
        assertEquals(listOf(ThreadClass.Class6g), classesFor(ThreadForm.IsoMetricCoarse, internal = false))
        assertEquals(listOf(ThreadClass.Class2B, ThreadClass.Class3B), classesFor(ThreadForm.Unef, internal = true))
        assertEquals(listOf(ThreadClass.Class2A, ThreadClass.Class3A), classesFor(ThreadForm.Unc, internal = false))
        assertEquals(emptyList(), classesFor(ThreadForm.CustomMetric, internal = false))
    }
}
