package com.drivool.iot.powertap

data class TariffSlab(
    val units: String,
    val category: String,
    val price: Double
)

data class TariffGroup(
    val label: String,
    val time: String,
    val slabs: List<TariffSlab>
)

object EnergyRateModel {
    fun defaultTariffRates(): List<TariffGroup> = listOf(
        TariffGroup(
            label = "Day",
            time = "6 AM - 6 PM",
            slabs = listOf(
                TariffSlab(units = "Upto 200", category = "Cat-I(A)", price = 5.0),
                TariffSlab(units = "201-300", category = "Cat-I(B)(I)", price = 7.2),
                TariffSlab(units = "301-400", category = "Cat-I(B)(II)", price = 8.5)
            )
        ),
        TariffGroup(
            label = "Night",
            time = "6 PM - 6 AM",
            slabs = listOf(
                TariffSlab(units = "Upto 200", category = "Cat-I(A)", price = 6.0),
                TariffSlab(units = "201-300", category = "Cat-I(B)(I)", price = 8.0),
                TariffSlab(units = "301-400", category = "Cat-I(B)(II)", price = 9.5)
            )
        )
    )
}
