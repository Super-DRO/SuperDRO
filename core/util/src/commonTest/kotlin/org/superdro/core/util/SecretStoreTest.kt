package org.superdro.core.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class SecretStoreTest {

    @Test
    fun aSecretGoesInAndOutAndAwayAgain() {
        val store: SecretStore = InMemorySecretStore()
        assertNull(store.get("mqtt.password"))
        store.put("mqtt.password", byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), store.get("mqtt.password"))
        store.put("mqtt.password", byteArrayOf(4))
        assertContentEquals(byteArrayOf(4), store.get("mqtt.password"))
        store.delete("mqtt.password")
        store.delete("not.there")
        assertNull(store.get("mqtt.password"))
    }

    @Test
    fun theStoreKeepsACopy() {
        val store = InMemorySecretStore()
        val secret = byteArrayOf(9, 9)
        store.put("psk", secret)
        secret[0] = 0
        assertContentEquals(byteArrayOf(9, 9), store.get("psk"))
        store.get("psk")!![1] = 0
        assertContentEquals(byteArrayOf(9, 9), store.get("psk"))
    }
}
