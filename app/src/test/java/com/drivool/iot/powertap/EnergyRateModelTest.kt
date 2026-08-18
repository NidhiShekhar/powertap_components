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

    @Test
    fun parseFromFirebase_parsesActiveTariffMap() {
        val details = mapOf(
            "active" to "tariffA",
            "tariff" to mapOf(
                "tariffA" to mapOf(
                    "t001" to mapOf(
                        "start_time" to "06:00",
                        "end_time" to "18:00",
                        "label" to "Upto 200",
                        "upTo" to 200,
                        "rate" to 5.0
                    ),
                    "t002" to mapOf(
                        "start_time" to "06:00",
                        "end_time" to "18:00",
                        "label" to "201-300",
                        "upTo" to 300,
                        "rate" to 7.2
                    )
                )
            )
        )

        val parsed = EnergyRateModel.parseFromFirebase(details)
        requireNotNull(parsed)
        assertEquals("Firebase (tariffA)", parsed.source)
        assertEquals(1, parsed.rates.size)
        assertEquals("Day", parsed.rates.first().label)
        assertEquals(2, parsed.rates.first().slabs.size)
        assertEquals(7.2, parsed.rates.first().slabs[1].price, 0.0)
    }
}
