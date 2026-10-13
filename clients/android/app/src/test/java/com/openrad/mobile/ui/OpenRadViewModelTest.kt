package com.openrad.mobile.ui

import com.openrad.mobile.data.MockRepository
import com.openrad.mobile.data.VpnRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpenRadViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class RecordingRepository(scope: TestScope, private val base: VpnRepository = MockRepository(scope)) : VpnRepository by base {
        val searches = ArrayList<String>()
        override suspend fun searchPublic(query: String) {
            searches.add(query)
        }
    }

    @Test
    fun publicSearchIsDebouncedBy300Milliseconds() = runTest(dispatcher) {
        val repo = RecordingRepository(this)
        val vm = OpenRadViewModel(repo)
        runCurrent()
        advanceTimeBy(301)
        runCurrent()
        assertEquals(listOf(""), repo.searches)
        vm.discoverQuery.value = "m"
        advanceTimeBy(100)
        vm.discoverQuery.value = "mi"
        advanceTimeBy(100)
        vm.discoverQuery.value = "min"
        advanceTimeBy(299)
        runCurrent()
        assertEquals(listOf(""), repo.searches)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("", "min"), repo.searches)
    }
}
