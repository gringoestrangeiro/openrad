package com.openrad.mobile.data

import com.openrad.mobile.engine.CommandResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class PublicCatalog<S : Any> {
    data class Request<S>(val id: Long, val session: S, val query: String, val cursor: Long)
    data class State<S>(
        val session: S? = null,
        val query: String = "",
        val results: List<Pair<String, Long>> = emptyList(),
        val cursor: Long = 0,
        val pending: Request<S>? = null,
    ) {
        val busy get() = pending != null
        val hasMore get() = cursor != 0L
    }

    private val mutableState = MutableStateFlow(State<S>())
    val state = mutableState.asStateFlow()
    private var nextId = 0L

    @Synchronized fun invalidate(session: S?) {
        val old = mutableState.value
        if (old.session != session) mutableState.value = State(query = old.query, session = session)
    }

    @Synchronized fun begin(session: S?, query: String): Request<S>? {
        val request = session?.let { Request(++nextId, it, query.trim(), 0) }
        mutableState.value = State(session = session, query = query, pending = request)
        return request
    }

    @Synchronized fun more(session: S?): Request<S>? {
        val old = mutableState.value
        if (session == null || session != old.session || old.busy || !old.hasMore) return null
        val request = Request(++nextId, session, old.query.trim(), old.cursor)
        mutableState.value = old.copy(pending = request)
        return request
    }

    @Synchronized fun isCurrent(request: Request<S>, session: S?): Boolean =
        request.session == session && mutableState.value.pending == request

    @Synchronized fun complete(request: Request<S>, session: S?, result: CommandResult?): Boolean {
        val old = mutableState.value
        if (old.pending != request) return false
        if (request.session != session) {
            mutableState.value = State(query = old.query, session = session)
            return false
        }
        if (result is CommandResult.Listing && result.query == request.query) {
            val results = (old.results + result.networks).distinctBy { it.first }.take(MAX_RESULTS)
            val cursor = result.cursor.takeIf {
                result.networks.isNotEmpty() && it != request.cursor && results.size < MAX_RESULTS &&
                    (request.cursor == 0L || results.size > old.results.size)
            } ?: 0
            mutableState.value = old.copy(results = results, cursor = cursor, pending = null)
        } else {
            mutableState.value = old.copy(pending = null)
        }
        return true
    }

    companion object {
        const val MAX_RESULTS = 4096
    }
}
