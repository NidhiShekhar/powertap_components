package com.drivool.iot.powertap

import java.util.Locale

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
    data class ParsedTariffRates(
        val rates: List<TariffGroup>,
        val source: String
    )

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

    fun parseFromFirebase(detailsMap: Map<String, Any?>): ParsedTariffRates? {
        val tariffRoot = detailsMap["tariff"] as? Map<*, *> ?: return null
        val activeTariff = detailsMap["active"]?.toString()?.trim().orEmpty()
        val normalizedTariffRoot = tariffRoot.entries.associate { (k, v) -> k.toString() to v }

        val scopedMap = when {
            activeTariff.isNotEmpty() && normalizedTariffRoot[activeTariff] is Map<*, *> ->
                (normalizedTariffRoot[activeTariff] as Map<*, *>).entries.associate { (k, v) -> k.toString() to v }
            else -> normalizedTariffRoot
        }

        val slabs = collectSlabs(scopedMap)
        if (slabs.isEmpty()) return null

        val groupedRates = slabs
            .groupBy { "${it.startTime}-${it.endTime}" }
            .toList()
            .sortedBy { (_, groupSlabs) -> groupSlabs.minOfOrNull { it.upTo ?: Int.MAX_VALUE } ?: Int.MAX_VALUE }
            .mapIndexed { index, (_, groupSlabs) ->
                val sortedSlabs = groupSlabs.sortedBy { it.upTo ?: Int.MAX_VALUE }
                TariffGroup(
                    label = labelForWindow(sortedSlabs.firstOrNull()?.startTime, sortedSlabs.firstOrNull()?.endTime, index),
                    time = timeWindow(sortedSlabs.firstOrNull()?.startTime, sortedSlabs.firstOrNull()?.endTime),
                    slabs = sortedSlabs.mapIndexed { slabIndex, slab ->
                        TariffSlab(
                            units = slab.label.ifBlank {
                                slab.upTo?.let { "Upto $it" } ?: "Slab ${slabIndex + 1}"
                            },
                            category = slab.id.uppercase(Locale.getDefault()),
                            price = slab.rate
                        )
                    }
                )
            }
        val source = if (activeTariff.isNotEmpty()) "Firebase ($activeTariff)" else "Firebase"
        return ParsedTariffRates(groupedRates, source)
    }

    private data class FirebaseSlab(
        val id: String,
        val label: String,
        val upTo: Int?,
        val rate: Double,
        val startTime: String,
        val endTime: String
    )

    private fun collectSlabs(node: Map<String, Any?>): List<FirebaseSlab> {
        val slabs = mutableListOf<FirebaseSlab>()
        node.forEach { (key, value) ->
            val valueMap = value as? Map<*, *> ?: return@forEach
            val map = valueMap.entries.associate { (k, v) -> k.toString() to v }
            val rate = map["rate"]?.toString()?.toDoubleOrNull()
            if (rate != null) {
                slabs += FirebaseSlab(
                    id = key,
                    label = map["label"]?.toString().orEmpty(),
                    upTo = map["upTo"]?.toString()?.toDoubleOrNull()?.toInt(),
                    rate = rate,
                    startTime = map["start_time"]?.toString().orEmpty(),
                    endTime = map["end_time"]?.toString().orEmpty()
                )
            } else {
                slabs += collectSlabs(map)
            }
        }
        return slabs
    }

    private fun labelForWindow(startTime: String?, endTime: String?, index: Int): String {
        val normalizedStart = startTime.orEmpty()
        val normalizedEnd = endTime.orEmpty()
        return when {
            normalizedStart == "06:00" && normalizedEnd == "18:00" -> "Day"
            normalizedStart == "18:00" && normalizedEnd == "06:00" -> "Night"
            normalizedStart.isBlank() && normalizedEnd.isBlank() -> "Tariff ${index + 1}"
            else -> "Tariff ${index + 1}"
        }
    }

    private fun timeWindow(startTime: String?, endTime: String?): String {
        val start = startTime.orEmpty()
        val end = endTime.orEmpty()
        return if (start.isBlank() || end.isBlank()) "All day" else "$start - $end"
    }
}
