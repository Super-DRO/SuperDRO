package org.superdro.core.protocol

import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The list of all value keys has no null entry when the JVM loads [ValueKey.Dirty] first. The adapter probe found this
 * fault: a `$32` status before a query loaded `Dirty` first, and the eager list then held a null.
 */
class ValueKeyInitOrderTest {

    @Test
    fun theListOfAllKeysHasNoNullWhenDirtyLoadsFirst() {
        val own = ValueKey::class.java.protectionDomain.codeSource.location
        val stdlib = Unit::class.java.protectionDomain.codeSource.location
        URLClassLoader(arrayOf(own, stdlib), null).use { loader ->
            Class.forName("org.superdro.core.protocol.ValueKey\$Dirty", true, loader)
            val companion = Class.forName("org.superdro.core.protocol.ValueKey", true, loader).getField("Companion").get(null)
            val all = companion.javaClass.getMethod("getAll").invoke(companion) as List<*>
            assertTrue(all.none { key -> key == null }, "$all")
            assertEquals(ValueKey.all.size, all.size)
        }
    }
}
