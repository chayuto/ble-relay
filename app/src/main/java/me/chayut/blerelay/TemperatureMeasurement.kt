package me.chayut.blerelay

import com.welie.blessed.BluetoothBytesParser
import com.welie.blessed.BluetoothBytesParser.Companion.FORMAT_FLOAT
import com.welie.blessed.BluetoothBytesParser.Companion.FORMAT_UINT8
import java.nio.ByteOrder
import java.util.*

data class TemperatureMeasurement(
    val temperatureValue: Float,
)