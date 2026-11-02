package com.openrad.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class IdentityStoreTest {
    @Test fun registrationIntentBlocksReuseUntilSaveOrExplicitReset() {
        val store = MemoryIdentityStore()
        store.markRegistrationPending()
        assertThrows(RegistrationPendingException::class.java) { store.load() }
        val identity = Identity(9, Ip.parse("26.0.0.9"), "fixture", ByteArray(16) { 9 },
            "synthetic-credential".toByteArray(), "127.0.0.1")
        store.save(identity)
        assertEquals(identity, store.load())
        assertThrows(IllegalStateException::class.java) { store.markRegistrationPending() }
        assertEquals(identity, store.load())
        store.clear()
        assertNull(store.load())
        store.markRegistrationPending()
        store.clear()
        assertNull(store.load())
    }
}
