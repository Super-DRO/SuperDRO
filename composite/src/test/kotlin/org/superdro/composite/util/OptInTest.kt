package org.superdro.composite.util

import kotlin.test.Test
import kotlin.test.assertEquals

class OptInTest {

    @Test
    fun `the default opt-ins include the serialization and coroutines markers`() {
        assertEquals(
            listOf(
                "kotlin.ExperimentalUnsignedTypes",
                "kotlin.uuid.ExperimentalUuidApi",
                "kotlinx.serialization.ExperimentalSerializationApi",
                "kotlinx.coroutines.ExperimentalCoroutinesApi",
            ),
            optInValues(serialization = true),
        )
    }

    @Test
    fun `a module without serialization or coroutines gets only the stdlib markers`() {
        assertEquals(
            listOf(
                "kotlin.ExperimentalUnsignedTypes",
                "kotlin.uuid.ExperimentalUuidApi",
            ),
            optInValues(serialization = false, coroutines = false),
        )
    }

    @Test
    fun `a pure module with serialization gets no coroutines marker`() {
        assertEquals(
            listOf(
                "kotlin.ExperimentalUnsignedTypes",
                "kotlin.uuid.ExperimentalUuidApi",
                "kotlinx.serialization.ExperimentalSerializationApi",
            ),
            optInValues(serialization = true, coroutines = false),
        )
    }

    @Test
    fun `a Compose module also gets the Material 3 marker`() {
        assertEquals(
            listOf(
                "kotlin.ExperimentalUnsignedTypes",
                "kotlin.uuid.ExperimentalUuidApi",
                "kotlinx.serialization.ExperimentalSerializationApi",
                "kotlinx.coroutines.ExperimentalCoroutinesApi",
                "androidx.compose.material3.ExperimentalMaterial3Api",
            ),
            optInValues(serialization = true, material3 = true),
        )
    }
}
