package com.openrad.mobile.data

import com.openrad.mobile.engine.CommandResult
import org.junit.Assert.*
import org.junit.Test

class PublicCatalogTest {
    @Test fun newSearchClearsPaginationAndRejectsAnOlderResponse() {
        val catalog = PublicCatalog<Int>()
        val first = catalog.begin(1, "old")!!
        assertTrue(catalog.complete(first, 1, CommandResult.Listing("old", listOf("Alpha" to 3L), 10)))
        val page = catalog.more(1)!!
        val second = catalog.begin(1, "new")!!
        assertFalse(catalog.state.value.hasMore)
        assertTrue(catalog.state.value.results.isEmpty())
        assertFalse(catalog.complete(page, 1, CommandResult.Listing("old", listOf("Beta" to 1L), 20)))
        assertTrue(catalog.state.value.busy)
        assertTrue(catalog.complete(second, 1, CommandResult.Listing("new", listOf("Gamma" to 1L), 0)))
        assertEquals(listOf("Gamma" to 1L), catalog.state.value.results)
    }

    @Test fun identicalQueryCannotConsumePreviousSessionResponse() {
        val catalog = PublicCatalog<Int>()
        val first = catalog.begin(1, "same")!!
        catalog.invalidate(2)
        val second = catalog.begin(2, "same")!!
        assertFalse(catalog.complete(first, 2, CommandResult.Listing("same", listOf("Old" to 1L), 0)))
        assertTrue(catalog.complete(second, 2, CommandResult.Listing("same", listOf("New" to 1L), 10)))
        catalog.invalidate(null)
        assertTrue(catalog.state.value.results.isEmpty())
        assertFalse(catalog.state.value.hasMore)
    }

    @Test fun duplicatePageAndRepeatedCursorEndPagination() {
        val catalog = PublicCatalog<Int>()
        val first = catalog.begin(1, "all")!!
        catalog.complete(first, 1, CommandResult.Listing("all", listOf("A" to 1L, "A" to 2L), 9))
        val page = catalog.more(1)!!
        assertNull(catalog.more(1))
        catalog.complete(page, 1, CommandResult.Listing("all", listOf("A" to 1L), 10))
        assertEquals(listOf("A" to 1L), catalog.state.value.results)
        assertFalse(catalog.state.value.hasMore)
    }

    @Test fun cancellationDoesNotClearAnotherRequestAndResultBudgetIsBounded() {
        val catalog = PublicCatalog<Int>()
        val first = catalog.begin(1, "a")!!
        val second = catalog.begin(1, "b")!!
        assertFalse(catalog.complete(first, 1, null))
        assertTrue(catalog.state.value.busy)
        catalog.complete(second, 1, CommandResult.Listing("b",
            (0..PublicCatalog.MAX_RESULTS).map { "net-$it" to 1L }, 1))
        assertEquals(PublicCatalog.MAX_RESULTS, catalog.state.value.results.size)
        assertFalse(catalog.state.value.hasMore)
        val third = catalog.begin(1, "c")!!
        assertFalse(catalog.complete(third, 2, null))
        assertFalse(catalog.state.value.busy)
    }
}
