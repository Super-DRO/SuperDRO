package org.superdro.core.functions.thread

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.ThreadClass
import org.superdro.core.model.ThreadCutter
import org.superdro.core.model.ThreadForm

class ThreadTablesTest {

    @Test
    fun theTablesHaveTheExpectedRanges() {
        val coarse = ThreadTables.sizes(ThreadForm.IsoMetricCoarse)
        assertEquals(31, coarse.size)
        assertEquals("M3 × 0.5", coarse.first().designation)
        assertEquals("M68 × 6", coarse.last().designation)
        val fine = ThreadTables.sizes(ThreadForm.IsoMetricFine)
        assertEquals("M3 × 0.35", fine.first().designation)
        assertEquals("M68 × 1.5", fine.last().designation)
        val unc = ThreadTables.sizes(ThreadForm.Unc)
        assertEquals(29, unc.size)
        assertEquals("#5-40", unc.first().designation)
        assertEquals("4-4", unc.last().designation)
        val unf = ThreadTables.sizes(ThreadForm.Unf)
        assertEquals(20, unf.size)
        assertEquals("#5-44", unf.first().designation)
        assertEquals("1 1/2-12", unf.last().designation)
        val unef = ThreadTables.sizes(ThreadForm.Unef)
        assertEquals(25, unef.size)
        assertEquals("#12-32", unef.first().designation)
        assertEquals("1 11/16-18", unef.last().designation)
        assertTrue(ThreadTables.sizes(ThreadForm.CustomMetric).isEmpty())
    }

    @Test
    fun theRowsKeepAllLengthsInMicrometers() {
        val m10 = assertNotNull(ThreadTables.find(ThreadForm.IsoMetricCoarse, "M10 × 1.5"))
        assertEquals(10000.0, m10.nominalDiameterUm)
        assertEquals(1500.0, m10.pitchUm)
        assertNull(m10.threadsPerInch)
        val quarter = assertNotNull(ThreadTables.find(ThreadForm.Unc, "1/4-20"))
        assertEquals(6350.0, quarter.nominalDiameterUm)
        assertEquals(1270.0, quarter.pitchUm)
        assertEquals(20.0, quarter.threadsPerInch)
        assertEquals(6350.0, quarter.lengthOfEngagementUm)
        val number10 = assertNotNull(ThreadTables.find(ThreadForm.Unf, "#10-32"))
        assertEquals(0.190 * 25400.0, number10.nominalDiameterUm, 1e-9)
        val unef = assertNotNull(ThreadTables.find(ThreadForm.Unef, "1/2-28"))
        assertEquals(9.0 * 25400.0 / 28.0, unef.lengthOfEngagementUm!!, 1e-9)
        assertNotNull(ThreadTables.find(ThreadForm.Unc, "2-4 1/2"))
        assertEquals("M3.5 × 0.6", ThreadTables.sizes(ThreadForm.IsoMetricCoarse)[1].designation)
    }

    @Test
    fun eachIsoRowHasTheValuesOfBothGrade6Classes() {
        val iso = ThreadTables.sizes(ThreadForm.IsoMetricCoarse) + ThreadTables.sizes(ThreadForm.IsoMetricFine)
        for (size in iso) {
            val external = ThreadCalculator.standard(size, internal = false, ThreadCutter.SharpV, ThreadClass.Class6g)
            val internal = ThreadCalculator.standard(size, internal = true, ThreadCutter.SharpV, ThreadClass.Class6H)
            assertNull(external.classStatus, size.designation)
            assertNull(internal.classStatus, size.designation)
            assertTrue(external.cutToDiameterUm < external.preOp.valueUm, size.designation)
            assertTrue(internal.cutToDiameterUm > internal.preOp.valueUm, size.designation)
        }
    }

    @Test
    fun eachUnifiedRowHasTheValuesOfAllAsmeClasses() {
        val un = ThreadTables.sizes(ThreadForm.Unc) + ThreadTables.sizes(ThreadForm.Unf) + ThreadTables.sizes(ThreadForm.Unef)
        for (size in un) {
            for (threadClass in listOf(ThreadClass.Class2A, ThreadClass.Class3A)) {
                val g = ThreadCalculator.standard(size, internal = false, ThreadCutter.SharpV, threadClass)
                assertNull(g.classStatus, size.designation)
                val limits = assertNotNull(g.limits)
                assertTrue(limits.pitchMinUm < limits.pitchMaxUm, size.designation)
            }
            for (threadClass in listOf(ThreadClass.Class2B, ThreadClass.Class3B)) {
                val g = ThreadCalculator.standard(size, internal = true, ThreadCutter.SharpV, threadClass)
                assertNull(g.classStatus, size.designation)
                val limits = assertNotNull(g.limits)
                assertTrue(limits.crestMinUm < limits.crestMaxUm, size.designation)
            }
        }
    }

    @Test
    fun theKnownIsoClassLimitsComeOut() {
        // M6 × 1 6g: d2 5.324 to 5.212; M12 × 1.75 6H: D2 10.863 to 11.063, D1 10.106 to 10.441.
        val m6 = ThreadCalculator.standard(ThreadTables.find(ThreadForm.IsoMetricCoarse, "M6 × 1")!!, false, ThreadCutter.SharpV, ThreadClass.Class6g)
        assertEquals("5.324", ThreadText.length(m6.limits!!.pitchMaxUm, ThreadUnit.Millimeter))
        assertEquals("5.212", ThreadText.length(m6.limits.pitchMinUm, ThreadUnit.Millimeter))
        val m12 = ThreadCalculator.standard(ThreadTables.find(ThreadForm.IsoMetricCoarse, "M12 × 1.75")!!, true, ThreadCutter.SharpV, ThreadClass.Class6H)
        assertEquals("10.863", ThreadText.length(m12.limits!!.pitchMinUm, ThreadUnit.Millimeter))
        assertEquals("11.063", ThreadText.length(m12.limits.pitchMaxUm, ThreadUnit.Millimeter))
        assertEquals("10.106", ThreadText.length(m12.limits.crestMinUm, ThreadUnit.Millimeter))
        assertEquals("10.441", ThreadText.length(m12.limits.crestMaxUm, ThreadUnit.Millimeter))
    }

    @Test
    fun theTextFormatsFollowTheThreadUnit() {
        assertEquals("9.968", ThreadText.length(9968.0, ThreadUnit.Millimeter))
        assertEquals("0.2489", ThreadText.length(6322.06, ThreadUnit.Inch))
        assertEquals("-1.250", ThreadText.length(-1250.0, ThreadUnit.Millimeter))
        assertEquals("0.000", ThreadText.length(-0.4, ThreadUnit.Millimeter))
        assertEquals("20", ThreadText.threadsPerInch(1270.0))
        assertEquals("4.5", ThreadText.threadsPerInch(25400.0 / 4.5))
        assertEquals("0.7", ThreadText.cutLoad(0.6765705))
        assertEquals("—", ThreadText.cutLoad(null))
    }

    @Test
    fun theAdditionalStandardFormTablesHaveTheExpectedSizes() {
        val bspp = ThreadTables.sizes(ThreadForm.Bspp)
        assertEquals(14, bspp.size)
        assertEquals("G 1/16", bspp.first().designation)
        assertEquals("G 2", bspp.last().designation)

        val bsw = ThreadTables.sizes(ThreadForm.Bsw)
        assertEquals(17, bsw.size)
        assertEquals("1/8-40", bsw.first().designation)
        assertEquals("2-4.5", bsw.last().designation)

        val bsf = ThreadTables.sizes(ThreadForm.Bsf)
        assertEquals(14, bsf.size)
        assertEquals("3/16-32", bsf.first().designation)
        assertEquals("1 1/2-8", bsf.last().designation)

        val tr = ThreadTables.sizes(ThreadForm.IsoTrapezoidal)
        assertEquals(20, tr.size)
        assertEquals("Tr 8 × 1.5", tr.first().designation)
        assertEquals("Tr 60 × 9", tr.last().designation)

        val acme = ThreadTables.sizes(ThreadForm.Acme)
        assertEquals(15, acme.size)
        assertEquals("1/4-16", acme.first().designation)
        assertEquals("2-4", acme.last().designation)

        val npt = ThreadTables.sizes(ThreadForm.Npt)
        assertEquals(10, npt.size)
        assertEquals("1/16-27", npt.first().designation)
        assertEquals("2-11.5", npt.last().designation)
        val nptHalf = assertNotNull(ThreadTables.find(ThreadForm.Npt, "1/2-14"))
        assertEquals(0.840 * 25400.0, nptHalf.nominalDiameterUm, 1e-6)
        assertEquals(0.75843 * 25400.0, assertNotNull(nptHalf.e0PitchDiameterUm), 1e-6)
        assertEquals(0.77843 * 25400.0, assertNotNull(nptHalf.e1PitchDiameterUm), 1e-6)
        assertEquals(0.320 * 25400.0, assertNotNull(nptHalf.l1LengthUm), 1e-6)
        assertEquals(0.5337 * 25400.0, assertNotNull(nptHalf.l2LengthUm), 1e-6)

        val bspt = ThreadTables.sizes(ThreadForm.Bspt)
        assertEquals(10, bspt.size)
        assertEquals("R 1/16-28", bspt.first().designation)
        assertEquals("R 2-11", bspt.last().designation)
        val bsptQuarter = assertNotNull(ThreadTables.find(ThreadForm.Bspt, "R 1/4-19"))
        assertEquals(13157.0, bsptQuarter.nominalDiameterUm, 1e-6)
        assertEquals(12301.0, assertNotNull(bsptQuarter.e1PitchDiameterUm), 1e-6)
        assertEquals(6000.0, assertNotNull(bsptQuarter.l1LengthUm), 1e-6)
    }

    @Test
    fun eachNewFormRowHasValidTolerancesAndCutToDiameters() {
        for (size in ThreadTables.sizes(ThreadForm.Bspp)) {
            for (cls in listOf(ThreadClass.ClassA, ThreadClass.ClassB)) {
                val ext = ThreadCalculator.standard(size, internal = false, ThreadCutter.FullForm, cls)
                assertNull(ext.classStatus, size.designation)
                assertTrue(ext.cutToDiameterUm < ext.preOp.valueUm, size.designation)
            }
            val intG = ThreadCalculator.standard(size, internal = true, ThreadCutter.FullForm, null)
            assertNull(intG.classStatus, size.designation)
            assertNotNull(intG.limits, size.designation)
            assertTrue(intG.cutToDiameterUm > intG.preOp.valueUm, size.designation)
        }
        for (size in ThreadTables.sizes(ThreadForm.Bsw) + ThreadTables.sizes(ThreadForm.Bsf)) {
            val ext = ThreadCalculator.standard(size, internal = false, ThreadCutter.FullForm, ThreadClass.ClassMedium)
            val intG = ThreadCalculator.standard(size, internal = true, ThreadCutter.FullForm, ThreadClass.ClassMedium)
            assertNull(ext.classStatus, size.designation)
            assertNull(intG.classStatus, size.designation)
            assertTrue(ext.cutToDiameterUm < ext.preOp.valueUm, size.designation)
            assertTrue(intG.cutToDiameterUm > intG.preOp.valueUm, size.designation)
        }
        for (size in ThreadTables.sizes(ThreadForm.IsoTrapezoidal)) {
            val ext = ThreadCalculator.standard(size, internal = false, ThreadCutter.FullForm, ThreadClass.Class7e)
            val intG = ThreadCalculator.standard(size, internal = true, ThreadCutter.FullForm, ThreadClass.Class7H)
            assertNull(ext.classStatus, size.designation)
            assertNull(intG.classStatus, size.designation)
            assertTrue(ext.cutToDiameterUm < ext.preOp.valueUm, size.designation)
            assertTrue(intG.cutToDiameterUm > intG.preOp.valueUm, size.designation)
        }
        for (size in ThreadTables.sizes(ThreadForm.Acme)) {
            for (cls in listOf(ThreadClass.Class2G, ThreadClass.Class3G)) {
                val ext = ThreadCalculator.standard(size, internal = false, ThreadCutter.FullForm, cls)
                val intG = ThreadCalculator.standard(size, internal = true, ThreadCutter.FullForm, cls)
                assertNull(ext.classStatus, size.designation)
                assertNull(intG.classStatus, size.designation)
                assertTrue(ext.cutToDiameterUm < ext.preOp.valueUm, size.designation)
                assertTrue(intG.cutToDiameterUm > intG.preOp.valueUm, size.designation)
            }
        }
    }
}
