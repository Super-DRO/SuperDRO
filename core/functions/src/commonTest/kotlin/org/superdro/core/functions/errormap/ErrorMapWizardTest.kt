package org.superdro.core.functions.errormap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErrorMapWizardTest {

    private fun ErrorMapWizard.recordPass(count: Int, start: Long = 0) {
        startPass()
        for (i in 0 until count) offer(start + i, start + i, start + i)
        stop()
    }

    @Test
    fun theReferenceCannotBeTheTargetInput() {
        val wizard = ErrorMapWizard("a1", 'x')
        assertFalse(wizard.selectReference('X'))
        assertFalse(wizard.canStartPass)
        assertTrue(wizard.selectReference('y'))
        assertTrue(wizard.canStartPass)
    }

    @Test
    fun theWizardRecordsUpTo5PassesAndOneVerificationPass() {
        val wizard = ErrorMapWizard("a1", 'x')
        wizard.selectReference('y')
        assertFalse(wizard.canReview)
        assertFalse(wizard.canStartVerification)
        repeat(5) { wizard.recordPass(3, it * 100L) }
        assertEquals(5, wizard.passes.size)
        assertFalse(wizard.canStartPass)
        assertNull(wizard.startPass())
        assertTrue(wizard.canReview)
        assertEquals(WizardSlot.Verification, wizard.startVerification())
        wizard.offer(1, 2, 3)
        wizard.stop()
        assertEquals(listOf(SamplePair(1, 2, 3)), wizard.verification?.pairs)
    }

    @Test
    fun anEmptyPassDoesNotTakeAPlace() {
        val wizard = ErrorMapWizard("a1", 'x')
        wizard.selectReference('y')
        assertEquals(WizardSlot.Pass(0), wizard.startPass())
        wizard.stop()
        assertTrue(wizard.passes.isEmpty())
    }

    @Test
    fun reviewStopsThePassThatRecords() {
        val wizard = ErrorMapWizard("a1", 'x')
        wizard.selectReference('y')
        wizard.startPass()
        wizard.offer(1, 1, 1)
        wizard.offer(2, 2, 2)
        assertTrue(wizard.canReview)
        val passes = wizard.review()
        assertNotNull(passes)
        assertEquals(2, passes.single().pairs.size)
        assertNull(wizard.recording)
    }

    @Test
    fun discardDeletesAllPasses() {
        val wizard = ErrorMapWizard("a1", 'x')
        wizard.selectReference('y')
        wizard.recordPass(3)
        wizard.discard()
        assertTrue(wizard.passes.isEmpty())
        assertNull(wizard.review())
    }

    @Test
    fun anotherReferenceDeletesThePasses() {
        val wizard = ErrorMapWizard("a1", 'x')
        wizard.selectReference('y')
        wizard.recordPass(3)
        wizard.selectReference('z')
        assertTrue(wizard.passes.isEmpty())
    }

    @Test
    fun theSessionGoesToTextAndBack() {
        val wizard = ErrorMapWizard("4f2c", 'x')
        wizard.selectReference('w')
        wizard.recordPass(3)
        wizard.recordPass(2, 50)
        wizard.startVerification()
        wizard.offer(7, 8, 9)
        wizard.stop()
        val session = wizard.toSession()
        assertEquals("X", session.input)
        assertEquals("W", session.reference)
        assertEquals("adapter_4f2c_X.emw", session.fileName)
        val decoded = ErrorMapWizardSession.decode(session.encode())
        assertEquals(session, decoded)
        val restored = ErrorMapWizard.fromSession(decoded!!)
        assertEquals(wizard.passes, restored.passes)
        assertEquals(wizard.verification, restored.verification)
        assertEquals('W', restored.referenceInput)
        assertFalse(session.isEmpty)
    }

    @Test
    fun theSessionFileNameAndFolderFollowTheRule() {
        assertEquals("adapter_7_Z.emw", ErrorMapWizardSession.fileName("7", 'z'))
        assertEquals("error_map_wizard/adapter_7_Z.emw", ErrorMapWizardSession.relativePath("7", 'z'))
        assertEquals("error_map_wizard", ErrorMapWizardSession.FOLDER)
    }

    @Test
    fun anEmptySessionDeletesTheFile() {
        val wizard = ErrorMapWizard("a1", 'x')
        wizard.selectReference('y')
        assertTrue(wizard.toSession().isEmpty)
    }

    @Test
    fun aBadSessionTextGivesNull() {
        assertNull(ErrorMapWizardSession.decode("not json"))
        assertNull(ErrorMapWizardSession.decode("""{"format":2,"adapterId":"a","input":"X"}"""))
        assertNull(ErrorMapWizardSession.decode("""{"format":1,"adapterId":"a","input":""}"""))
        assertNull(
            ErrorMapWizardSession.decode(
                """{"format":1,"adapterId":"a","input":"X","passes":[{"targets":[1,2],"references":[1],"timesMs":[1,2]}]}""",
            ),
        )
        assertNotNull(ErrorMapWizardSession.decode("""{"format":1,"adapterId":"a","input":"X"}"""))
    }
}
