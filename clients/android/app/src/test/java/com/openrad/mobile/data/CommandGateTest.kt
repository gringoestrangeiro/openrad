package com.openrad.mobile.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandGateTest {
    @Test fun cancelledCallerDoesNotDelayTheNextQueryButItsWireCommandKeepsTheGate() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = CommandGate(owner)
        val firstEntered = CompletableDeferred<Unit>()
        val firstReply = CompletableDeferred<Int>()
        val secondEntered = CompletableDeferred<Unit>()
        val first = async {
            gate.await({ true }) { firstEntered.complete(Unit); firstReply.await() }
        }
        try {
            withTimeout(3000) { firstEntered.await() }
            // collectLatest uses cancelAndJoin before starting the new query.
            // Cancellation must finish without waiting for the server's reply.
            withTimeout(1000) { first.cancelAndJoin() }
            val second = async {
                gate.await({ true }) { secondEntered.complete(Unit); 2 }
            }
            // The next caller is responsive, but it cannot overtake the old wire command.
            assertNull(withTimeoutOrNull(100) { secondEntered.await() })
            firstReply.complete(1)
            assertEquals(2, withTimeout(3000) { second.await() })
        } finally {
            firstReply.complete(1)
            owner.cancel()
        }
    }

    @Test fun cancelledQueuedQueryIsNeverSentAndSupersededQueriesAreSkipped() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = CommandGate(owner)
        val entered = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Int>()
        var obsoleteCalls = 0
        val first = async { gate.await({ true }) { entered.complete(Unit); reply.await() } }
        try {
            withTimeout(3000) { entered.await() }
            val obsolete = async { gate.await({ true }) { obsoleteCalls++; 2 } }
            withTimeout(1000) { obsolete.cancelAndJoin() }
            reply.complete(1)
            assertEquals(1, withTimeout(3000) { first.await() })
            assertNull(withTimeout(3000) { gate.await({ false }) { obsoleteCalls++; 3 } })
            assertEquals(4, withTimeout(3000) { gate.await({ true }) { 4 } })
            assertEquals(0, obsoleteCalls)
        } finally {
            reply.complete(1)
            owner.cancel()
        }
    }
}
