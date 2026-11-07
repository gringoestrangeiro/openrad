package com.openrad.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** SharedPreferences commits to memory before reporting its disk-write result. */
internal fun commitPreferenceString(
    next: String,
    read: () -> String?,
    write: (String?) -> Boolean,
): Boolean {
    val previous = try { read() } catch (_: Exception) { return false }
    val saved = try { write(next) } catch (_: Exception) { false }
    if (saved) return true
    // Restore the process cache as well as attempting to restore the disk. A
    // failed rollback cannot establish durability; the original save still fails.
    try { write(previous) } catch (_: Exception) { }
    return false
}

/** Serializes preference read/modify/write operations and publishes only durable values. */
internal class PreferenceTransactions {
    data class Change<T>(val previous: T, val current: T)

    private val mutex = Mutex()

    suspend fun <T> update(
        state: MutableStateFlow<T>,
        save: (T) -> Boolean,
        transform: (T) -> T?,
        // Local state/config changes only; network commands run after the transaction.
        onSaved: (T) -> Unit = {},
    ): Change<T>? = mutex.withLock {
        // Once the write starts, finish publishing the same value even if the UI closes.
        withContext(NonCancellable) {
            val previous = state.value
            val next = transform(previous) ?: return@withContext null
            val saved = withContext(Dispatchers.IO) {
                try { save(next) } catch (_: Exception) { false }
            }
            if (!saved) return@withContext null
            state.value = next
            onSaved(next)
            Change(previous, next)
        }
    }
}
