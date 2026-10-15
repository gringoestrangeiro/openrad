package com.openrad.mobile.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class TunPollTest {
    // Linux poll(2) values; OsConstants is unavailable in JVM unit tests.
    private val pollIn = 0x001
    private val pollErr = 0x008
    private val pollHup = 0x010
    private val pollNval = 0x020
    private val terminal = pollErr or pollHup or pollNval

    @Test
    fun readableDataIsReadEvenWhenTheInterfaceHangsUp() {
        assertEquals(TunPollAction.READ, tunPollAction(pollIn, pollIn, terminal))
        assertEquals(TunPollAction.READ, tunPollAction(pollIn or pollHup, pollIn, terminal))
    }

    @Test
    fun errorsWithoutReadableDataStopTheReader() {
        for (revents in listOf(pollErr, pollHup, pollNval, pollErr or pollHup)) {
            assertEquals(TunPollAction.STOP, tunPollAction(revents, pollIn, terminal))
        }
    }

    @Test
    fun unrelatedOrEmptyEventsKeepWaiting() {
        assertEquals(TunPollAction.WAIT, tunPollAction(0, pollIn, terminal))
        assertEquals(TunPollAction.WAIT, tunPollAction(0x004, pollIn, terminal)) // POLLOUT
    }
}
