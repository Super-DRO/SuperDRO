package org.superdro.composite.util

import kotlin.test.Test
import kotlin.test.assertEquals

class OptInTest {

    @Test
    fun `the default opt-ins include the serialization marker`() {
        assertEquals(
            listOf(
                "kotlin.ExperimentalUnsignedTypes",
                "kotlin.uuid.ExperimentalUuidApi",
                "kotlinx.serialization.ExperimentalSerializationApi",
            ),
            optInValues(serialization = true),
        )
    }

    @Test
    fun `a module without serialization gets only the stdlib markers`() {
        assertEquals(
            listOf(
                "kotlin.ExperimentalUnsignedTypes",
                "kotlin.uuid.ExperimentalUuidApi",
            ),
            optInValues(serialization = false),
        )
    }

    @Test
    fun `a Compose module also gets the Material 3 marker`() {
        assertEquals(
            listOf(
                "kotlin.ExperimentalUnsignedTypes",
                "kotlin.uuid.ExperimentalUuidApi",
                "kotlinx.serialization.ExperimentalSerializationApi",
                "androidx.compose.material3.ExperimentalMaterial3Api",
            ),
            optInValues(serialization = true, material3 = true),
        )
    }
}
