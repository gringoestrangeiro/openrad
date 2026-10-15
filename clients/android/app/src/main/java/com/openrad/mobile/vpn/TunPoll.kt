package com.openrad.mobile.vpn

/** What the TUN reader does after one poll(2) wakeup. */
internal enum class TunPollAction { READ, WAIT, STOP }

/**
 * Classifies poll(2) `revents` for the TUN reader. Readable data is read
 * first, even alongside POLLHUP, so queued packets are not lost; an error
 * without readable data is terminal, because poll keeps reporting it and the
 * reader would otherwise spin. The masks are parameters so this stays a pure
 * function that JVM unit tests can exercise without android.system.OsConstants.
 */
internal fun tunPollAction(revents: Int, readable: Int, terminal: Int): TunPollAction = when {
    (revents and readable) != 0 -> TunPollAction.READ
    (revents and terminal) != 0 -> TunPollAction.STOP
    else -> TunPollAction.WAIT
}
