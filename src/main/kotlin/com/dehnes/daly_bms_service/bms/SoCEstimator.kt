package com.dehnes.daly_bms_service.bms

data class TableEntry(
    val voltage: Double,
    val soc: Int,
    val charging: Boolean? = null
)

object SoCEstimator {

    // https://footprinthero.com/lifepo4-battery-voltage-charts
    val table = listOf(
        TableEntry(3.65, 100, true),
        TableEntry(3.4, 100, false),
        TableEntry(3.35, 99),
        TableEntry(3.33, 90),
        TableEntry(3.3, 70),
        TableEntry(3.28, 40),
        TableEntry(3.25, 30),
        TableEntry(3.23, 20),
        TableEntry(3.2, 17),
        TableEntry(3.13, 14),
        TableEntry(3.0, 9),
        TableEntry(2.5, 0),
    )

    fun estimate(cellVoltage: Double, charging: Boolean): Double {
        val finalTable = table
            .reversed()
            .filter { it.charging == null || it.charging == charging }
        val (left, right) = finalTable
            .windowed(2, 1)
            .firstOrNull { (left, right) ->
                cellVoltage in left.voltage..right.voltage
            } ?: run {
            // outside of range
            return if (cellVoltage <= finalTable.first().voltage) 0.0 else if (cellVoltage >= finalTable.last().voltage) 100.0 else {
                error("")
            }
        }


        val base = cellVoltage - left.voltage
        val upper = right.voltage - left.voltage

        val percentage = base / upper

        val socUpper = right.soc - left.soc
        return left.soc + (socUpper * percentage)
    }
}