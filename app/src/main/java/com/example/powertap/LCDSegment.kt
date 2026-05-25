package com.example.powertap

data class LCDSegment(
    val text: String,
    val fontSize: Float = 20f,
    val align: Align = Align.LEFT,
    val weight: Float = 1f,
    val bold: Boolean = false
)

enum class Align {
    LEFT,
    CENTER,
    RIGHT
}
