package com.openrad.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.net.Socket

class EngineCommandFailureTest {
    @Test fun dequeuedCommandsCompleteExactlyOnceWhenTheControlWriteFails() {
        val submitters: List<(Engine, (CommandResult) -> Unit) -> Unit> = listOf(
            { engine, done -> engine.search("synthetic", 0, done) },
            { engine, done -> engine.join("synthetic", "synthetic-password".toCharArray(), done) },
            { engine, done -> engine.create("synthetic", "synthetic-password".toCharArray(), done) },
        )
        for (submit in submitters) {
            val engine = Engine(EngineConfig(), MemoryIdentityStore(), object : EngineListener {})
            Engine::class.java.getDeclaredField("phase").apply { isAccessible = true }
                .set(engine, EngineState.Phase.ONLINE)
            val responses = ArrayList<CommandResult>()
            submit(engine) { responses.add(it) }
            val socket = object : Socket() {
                override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
                override fun getOutputStream() = object : OutputStream() {
                    override fun write(value: Int) { throw IOException("synthetic write failure") }
                    override fun write(value: ByteArray, off: Int, len: Int) { throw IOException("synthetic write failure") }
                }
            }
            val control = Control(Framed(socket), SessionCrypto(Protocol.SERVER_MODULUS,
                1, "synthetic-password".toByteArray(), 4), 0)
            val active = ArrayList<Any>()
            val start = Engine::class.java.declaredMethods.single { it.name == "startCommands" }
                .apply { isAccessible = true }
            try {
                start.invoke(engine, control, active)
                throw AssertionError("the broken transport must still close the control session")
            } catch (failure: InvocationTargetException) {
                assertTrue(failure.cause is IOException)
            } finally {
                control.close()
            }
            Engine::class.java.getDeclaredMethod("drainCommands", String::class.java)
                .apply { isAccessible = true }.invoke(engine, "engine stopped")
            assertTrue(active.isEmpty())
            assertEquals(1, responses.size)
            assertTrue(responses.single() is CommandResult.Failed)
        }
    }
}
