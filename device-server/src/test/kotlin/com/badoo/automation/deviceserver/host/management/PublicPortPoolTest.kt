package com.badoo.automation.deviceserver.host.management

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicPortPoolTest {
    private val alwaysFree: (Int) -> Boolean = { true }

    @Test
    fun allocatesConsecutivePortsFromRange() {
        val pool = PublicPortPool(min = 100, max = 105, isBindable = alwaysFree)

        assertEquals(listOf(100, 101, 102), pool.allocateDAP().toList())
        assertEquals(listOf(103, 104, 105), pool.allocateDAP().toList())
    }

    @Test
    fun failsWhenExhausted() {
        val pool = PublicPortPool(min = 100, max = 104, isBindable = alwaysFree)
        pool.allocateDAP()

        val error = runCatching { pool.allocateDAP() }.exceptionOrNull()

        assertTrue(error is RuntimeException)
        assertEquals("No more public ports to allocate", error?.message)
    }

    @Test
    fun returnsPartiallyTakenPortsAfterFailedAllocation() {
        val pool = PublicPortPool(min = 100, max = 106, isBindable = alwaysFree)
        val first = pool.allocateDAP()
        pool.allocateDAP()
        runCatching { pool.allocateDAP() } // only 106 is left

        pool.deallocateDAP(first)

        assertEquals(listOf(106, 100, 101), pool.allocateDAP().toList())
    }

    @Test
    fun reusesReleasedPortsLast() {
        val pool = PublicPortPool(min = 100, max = 108, isBindable = alwaysFree)
        val first = pool.allocateDAP()
        pool.deallocateDAP(first)

        assertNotEquals(first, pool.allocateDAP())
    }

    @Test
    fun releasingTwiceDoesNotHandOutPortTwice() {
        val pool = PublicPortPool(min = 100, max = 105, isBindable = alwaysFree)
        val first = pool.allocateDAP()
        pool.deallocateDAP(first)
        pool.deallocateDAP(first)

        val all = listOf(pool.allocateDAP(), pool.allocateDAP()).flatMap { it.toList() }

        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun skipsPortsThatCannotBeBound() {
        val pool = PublicPortPool(min = 100, max = 105, isBindable = { it != 101 })

        assertEquals(listOf(100, 102, 103), pool.allocateDAP().toList())
    }
}
