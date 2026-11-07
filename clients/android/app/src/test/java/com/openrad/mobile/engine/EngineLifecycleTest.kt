package com.openrad.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class EngineLifecycleTest {
    @Test fun terminalErrorIsPublishedOnlyAfterTheEngineStopsAcceptingWork() {
        val firstErrorRunning = CompletableFuture<Boolean>()
        val store = object : IdentityStore {
            override fun load(): Identity? = throw IOException("synthetic storage failure")
            override fun save(identity: Identity) = error("must not save")
            override fun markRegistrationPending() = error("must not register")
            override fun clear() = Unit
        }
        lateinit var engine: Engine
        engine = Engine(EngineConfig(autoReconnect = false), store, object : EngineListener {
            override fun onState(state: EngineState) {
                if (state.phase == EngineState.Phase.ERROR) firstErrorRunning.complete(engine.isRunning)
            }
        })
        try {
            engine.start()
            assertFalse("the service must see a stopped engine on its first ERROR notification",
                firstErrorRunning.get(5, TimeUnit.SECONDS))
        } finally {
            engine.stop()
        }
    }

    @Test fun commandDuringShutdownFailsWithoutWaitingForAnotherControlLoop() {
        val engine = Engine(EngineConfig(), MemoryIdentityStore(), object : EngineListener {})
        // stop() clears running before the last ONLINE snapshot has been replaced.
        Engine::class.java.getDeclaredField("phase").apply { isAccessible = true }
            .set(engine, EngineState.Phase.ONLINE)
        val responses = ArrayList<CommandResult>()
        val password = "synthetic-password".toCharArray()
        engine.join("synthetic", password) { responses.add(it) }
        assertEquals(1, responses.size)
        assertTrue(responses.single() is CommandResult.Failed)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test fun throwingCallbackDuringShutdownDrainStillWipesAndFailsLaterCommands() {
        val engine = Engine(EngineConfig(), MemoryIdentityStore(), object : EngineListener {})
        Engine::class.java.getDeclaredField("phase").apply { isAccessible = true }
            .set(engine, EngineState.Phase.ONLINE)
        (Engine::class.java.getDeclaredField("running").apply { isAccessible = true }
            .get(engine) as java.util.concurrent.atomic.AtomicBoolean).set(true)
        val first = "synthetic-first".toCharArray()
        val second = "synthetic-second".toCharArray()
        val responses = ArrayList<CommandResult>()
        engine.join("first", first) { throw IllegalStateException("synthetic callback failure") }
        engine.join("second", second) { responses.add(it) }
        engine.stop()
        assertTrue(first.all { it == '\u0000' })
        assertTrue(second.all { it == '\u0000' })
        assertEquals(1, responses.size)
        assertTrue(responses.single() is CommandResult.Failed)
    }
}
