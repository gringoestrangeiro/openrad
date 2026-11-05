package com.openrad.mobile.data

import com.openrad.mobile.engine.BlockDirection
import com.openrad.mobile.engine.Ip
import com.openrad.mobile.model.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PreferenceTransactionsTest {
    @Test fun failedCommitRestoresTheCacheBeforeReturningFailure() = runBlocking {
        for (throws in listOf(false, true)) {
            for (previous in listOf(null, "previous raw JSON")) {
                val writes = PreferenceTransactions()
                val state = MutableStateFlow(0)
                var cache = previous
                val attempts = ArrayList<String?>()
                var applied = false
                val result = writes.update(state, { next ->
                    commitPreferenceString(next.toString(), { cache }, {
                        // Android mutates its cache even when the disk write fails.
                        cache = it
                        attempts.add(it)
                        if (throws) throw IOException("synthetic failure after cache update")
                        false
                    })
                }, { it + 1 }, { applied = true })
                assertNull(result)
                assertEquals(previous, cache)
                assertEquals(listOf("1", previous), attempts)
                assertEquals(0, state.value)
                assertFalse(applied)
            }
        }
    }

    @Test fun successfulCommitKeepsTheNewCacheAndPublishesItsValue() = runBlocking {
        val writes = PreferenceTransactions()
        val state = MutableStateFlow(0)
        var cache: String? = "previous raw JSON"
        val attempts = ArrayList<String?>()
        var applied = false
        val result = writes.update(state, { next ->
            commitPreferenceString(next.toString(), { cache }, { cache = it; attempts.add(it); true })
        }, { it + 1 }, { applied = true })
        assertEquals(1, result?.current)
        assertEquals("1", cache)
        assertEquals(listOf("1"), attempts)
        assertEquals(1, state.value)
        assertTrue(applied)
    }

    @Test fun failedWriteKeepsThePreviousStateAndDoesNotApplyLiveSettings() = runBlocking {
        val writes = PreferenceTransactions()
        val state = MutableStateFlow(AppSettings())
        var applied = false
        val result = writes.update(state, { false }, { it.copy(forceRelay = true) }, { applied = true })
        assertNull(result)
        assertFalse(state.value.forceRelay)
        assertFalse(applied)
        assertNull(writes.update(state, { throw IOException("synthetic disk failure") }, { it.copy(forceRelay = true) }))
        assertFalse(state.value.forceRelay)
    }

    @Test fun overlappingSettingsAndBlockEditsPreserveBothDurableChanges() = runBlocking {
        withTimeout(5000) {
            val writes = PreferenceTransactions()
            val state = MutableStateFlow(AppSettings())
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val vip = Ip.parse("26.0.0.8")
            val persisted = ArrayList<AppSettings>()
            try {
                val settings = async {
                    writes.update(state, {
                        entered.complete(Unit)
                        check(release.await(3, TimeUnit.SECONDS))
                        persisted.add(it)
                        true
                    }, { it.copy(outgoingBroadcasts = false) })
                }
                entered.await()
                val block = async {
                    writes.update(state, { persisted.add(it); true }, { it.copy(blocked = it.blocked + (vip to BlockDirection.BOTH)) })
                }
                // Ensure the second transaction is waiting while the first disk write is paused.
                kotlinx.coroutines.yield()
                assertFalse(block.isCompleted)
                release.countDown()
                settings.await()
                block.await()
                assertEquals(2, persisted.size)
                assertFalse(state.value.outgoingBroadcasts)
                assertEquals(BlockDirection.BOTH, state.value.blocked[vip])
                assertEquals(state.value, persisted.last())
            } finally { release.countDown() }
        }
    }

    @Test fun cancellationDuringAWriteStillPublishesItsDurableValue() = runBlocking {
        withTimeout(5000) {
            val writes = PreferenceTransactions()
            val state = MutableStateFlow(0)
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            var applied = 0
            val job = launch {
                writes.update(state, {
                    entered.complete(Unit)
                    check(release.await(3, TimeUnit.SECONDS))
                    true
                }, { it + 1 }, { applied = it })
            }
            try {
                entered.await()
                job.cancel()
                release.countDown()
                job.cancelAndJoin()
                assertEquals(1, state.value)
                assertEquals(1, applied)
                assertTrue(job.isCancelled)
            } finally { release.countDown() }
        }
    }
}
