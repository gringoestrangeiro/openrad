package com.openrad.mobile.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes commands that remain on the wire until their callback/deadline completes. */
internal class CommandGate(private val scope: CoroutineScope) {
    private val mutex = Mutex()

    suspend fun <T> await(isCurrent: () -> Boolean, command: suspend () -> T): T? {
        // The owner keeps an admitted command alive; cancelling the caller must
        // return immediately so collectLatest can invalidate the old generation.
        val pending = scope.async {
            mutex.withLock {
                if (!isCurrent()) null else withContext(NonCancellable) { command() }
            }
        }
        return try {
            pending.await()
        } finally {
            // Remove cancelled waiters. A command already on the wire still
            // holds the gate inside NonCancellable until its callback/deadline.
            pending.cancel()
        }
    }
}
