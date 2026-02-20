package org.benesv.history.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Tests for the Pager component.
 */
class PagerTest {

    @Test
    fun testLoadFirstReturnsFirstPage() = runBlocking {
        val items = (0..99).toList()
        val pager = Pager<Int>(pageSize = 10) { limit, offset ->
            items.drop(offset).take(limit)
        }

        val firstPage = pager.loadFirst()

        assertEquals(10, firstPage.size)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), firstPage)
        assertTrue(pager.hasMore())
    }

    @Test
    fun testLoadNextReturnsSubsequentPages() = runBlocking {
        val items = (0..99).toList()
        val pager = Pager<Int>(pageSize = 10) { limit, offset ->
            items.drop(offset).take(limit)
        }

        pager.loadFirst()
        val secondPage = pager.loadNext()
        val thirdPage = pager.loadNext()

        assertEquals(10, secondPage.size)
        assertEquals(listOf(10, 11, 12, 13, 14, 15, 16, 17, 18, 19), secondPage)
        assertEquals(10, thirdPage.size)
        assertEquals(listOf(20, 21, 22, 23, 24, 25, 26, 27, 28, 29), thirdPage)
        assertTrue(pager.hasMore())
    }
}
