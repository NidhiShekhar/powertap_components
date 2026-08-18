package com.drivool.iot.powertap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyRateModelTest {

    @Test
    fun defaultTariffRates_matchesExpectedDayAndNightSlabs() {
        val rates = EnergyRateModel.defaultTariffRates()

        assertEquals(2, rates.size)
        assertEquals("Day", rates[0].label)
        assertEquals("Night", rates[1].label)
        assertEquals(3, rates[0].slabs.size)
        assertEquals(3, rates[1].slabs.size)
        assertEquals(5.0, rates[0].slabs[0].price, 0.0)
        assertEquals(9.5, rates[1].slabs[2].price, 0.0)
        assertTrue(rates.all { it.slabs.all { slab -> slab.price > 0 } })
    }
}
