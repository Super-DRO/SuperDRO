package org.superdro.app.android.sound

import android.media.ToneGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.superdro.core.ui.sound.UiSound
import org.superdro.core.util.sound.Beep

/**
 * The busy rule and the link tones of the Android players. The tone data
 * and the proximity tone are common code: `core:util` tests them (`org.superdro.core.util.sound`).
 */
class SoundRulesTest {

    @Test
    fun theLinkTonesAreToneGeneratorTones90And33For1000Ms() {
        assertEquals(90, SoundRules.TONE_LINK_UP)
        assertEquals(ToneGenerator.TONE_CDMA_PRESSHOLDKEY_LITE, SoundRules.TONE_LINK_UP)
        assertEquals(33, SoundRules.TONE_LINK_LOST)
        assertEquals(ToneGenerator.TONE_SUP_PIP, SoundRules.TONE_LINK_LOST)
        assertEquals(1000, SoundRules.LINK_LOST_MS)
        assertEquals(SoundKind.LinkTone, SoundRules.kindOf(UiSound.LinkUp))
        assertEquals(SoundKind.LinkTone, SoundRules.kindOf(UiSound.LinkLost))
        assertEquals(SoundKind.Beep, SoundRules.kindOf(UiSound.Error))
    }

    @Test
    fun theThreeBeepsAreTheBeepsOfCoreUtilAndTheLinkTonesAreNotBeeps() {
        assertEquals(Beep.Short, SoundRules.beepOf(UiSound.Short))
        assertEquals(Beep.Long, SoundRules.beepOf(UiSound.Long))
        assertEquals(Beep.Error, SoundRules.beepOf(UiSound.Error))
        assertFailsWith<IllegalArgumentException> { SoundRules.beepOf(UiSound.LinkUp) }
        assertFailsWith<IllegalArgumentException> { SoundRules.beepOf(UiSound.LinkLost) }
    }

    @Test
    fun aBusySoundDropsANewRequestOfTheSameKind() {
        var now = 0L
        val gate = SoundRules.gate { now }
        assertTrue(gate.tryStart(UiSound.Short))
        now = 50L
        assertFalse(gate.tryStart(UiSound.Short))
        now = 99L
        assertFalse(gate.tryStart(UiSound.Long))
        // A link tone uses a different player.
        assertTrue(gate.tryStart(UiSound.LinkLost))
        now = 100L
        assertTrue(gate.tryStart(UiSound.Long))
        now = 599L
        assertFalse(gate.tryStart(UiSound.Error))
        assertFalse(gate.tryStart(UiSound.LinkUp))
        now = 600L
        assertTrue(gate.tryStart(UiSound.Error))
        now = 850L
        assertTrue(gate.tryStart(UiSound.Short))
        // The link lost tone started at 99 ms for 1000 ms.
        now = 1098L
        assertFalse(gate.tryStart(UiSound.LinkUp))
        now = 1099L
        assertTrue(gate.tryStart(UiSound.LinkUp))
    }

    @Test
    fun theBusyTimeIsTheLengthOfTheSound() {
        assertEquals(100L, SoundRules.busyMs(UiSound.Short))
        assertEquals(500L, SoundRules.busyMs(UiSound.Long))
        assertEquals(250L, SoundRules.busyMs(UiSound.Error))
        assertEquals(1000L, SoundRules.busyMs(UiSound.LinkLost))
        assertEquals(400L, SoundRules.busyMs(UiSound.LinkUp))
    }
}
