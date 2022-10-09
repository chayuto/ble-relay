package me.chayut.blerelay

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.util.Log
import com.welie.blessed.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import timber.log.Timber
import timber.log.Timber.DebugTree
import java.nio.ByteOrder
import java.util.*

internal class BluetoothHandler private constructor(context: Context) {


    private fun handlePeripheral(peripheral: BluetoothPeripheral) {
        scope.launch {
            try {
                val mtu = peripheral.requestMtu(185)
                Timber.i("MTU is $mtu")

                peripheral.requestConnectionPriority(ConnectionPriority.HIGH)

                val rssi = peripheral.readRemoteRssi()
                Timber.i("RSSI is $rssi")

                peripheral.getCharacteristic(DIS_SERVICE_UUID, MANUFACTURER_NAME_CHARACTERISTIC_UUID)?.let {
                    val manufacturerName = peripheral.readCharacteristic(it).asString()
                    Timber.i("Received: $manufacturerName")
                }

            } catch (e: IllegalArgumentException) {
                Timber.e(e)
            } catch (b: GattException) {
                Timber.e(b)
            }
        }
    }


    private fun startScanning() {
        Log.d("XXX", "startScanning")
        Timber.d("startScanning")
        central.scanForPeripheralsWithServices(supportedServices,
            { peripheral, scanResult ->
                Log.d("XXX", "Found peripheral '${peripheral.name}' with RSSI ${scanResult.rssi}")
                Timber.i("Found peripheral '${peripheral.name}' with RSSI ${scanResult.rssi}")
                central.stopScan()
                connectPeripheral(peripheral)
            },
            { scanFailure -> Timber.e("scan failed with reason $scanFailure") })
    }

    private fun connectPeripheral(peripheral: BluetoothPeripheral) {
        peripheral.observeBondState {
            Timber.i("Bond state is $it")
        }

        scope.launch {
            try {
                central.connectPeripheral(peripheral)
            } catch (connectionFailed: ConnectionFailedException) {
                Timber.e("connection failed")
            }
        }
    }

    companion object {
        // UUIDs for the Blood Pressure service (BLP)
        private val BLP_SERVICE_UUID: UUID = UUID.fromString("00001810-0000-1000-8000-00805f9b34fb")
        private val BLP_MEASUREMENT_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A35-0000-1000-8000-00805f9b34fb")

        // UUIDs for the Health Thermometer service (HTS)
        private val HTS_SERVICE_UUID = UUID.fromString("00001809-0000-1000-8000-00805f9b34fb")
        private val HTS_MEASUREMENT_CHARACTERISTIC_UUID = UUID.fromString("00002A1C-0000-1000-8000-00805f9b34fb")

        // UUIDs for the Heart Rate service (HRS)
        private val HRS_SERVICE_UUID: UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
        private val HRS_MEASUREMENT_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")

        // UUIDs for the Device Information service (DIS)
        private val DIS_SERVICE_UUID: UUID = UUID.fromString("0000180A-0000-1000-8000-00805f9b34fb")
        private val MANUFACTURER_NAME_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A29-0000-1000-8000-00805f9b34fb")
        private val MODEL_NUMBER_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A24-0000-1000-8000-00805f9b34fb")

        // UUIDs for the Current Time service (CTS)
        private val CTS_SERVICE_UUID: UUID = UUID.fromString("00001805-0000-1000-8000-00805f9b34fb")
        private val CURRENT_TIME_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A2B-0000-1000-8000-00805f9b34fb")

        // UUIDs for the Battery Service (BAS)
        private val BTS_SERVICE_UUID: UUID = UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb")

        // UUIDs for the Pulse Oximeter Service (PLX)
        val PLX_SERVICE_UUID: UUID = UUID.fromString("00001822-0000-1000-8000-00805f9b34fb")
        private val PLX_SPOT_MEASUREMENT_CHAR_UUID: UUID = UUID.fromString("00002a5e-0000-1000-8000-00805f9b34fb")
        private val PLX_CONTINUOUS_MEASUREMENT_CHAR_UUID: UUID = UUID.fromString("00002a5f-0000-1000-8000-00805f9b34fb")

        // UUIDs for the Weight Scale Service (WSS)
        val WSS_SERVICE_UUID: UUID = UUID.fromString("0000181D-0000-1000-8000-00805f9b34fb")
        private val WSS_MEASUREMENT_CHAR_UUID: UUID = UUID.fromString("00002A9D-0000-1000-8000-00805f9b34fb")
        val GLUCOSE_SERVICE_UUID: UUID = UUID.fromString("00001808-0000-1000-8000-00805f9b34fb")
        val GLUCOSE_MEASUREMENT_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A18-0000-1000-8000-00805f9b34fb")
        val GLUCOSE_RECORD_ACCESS_POINT_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A52-0000-1000-8000-00805f9b34fb")
        val GLUCOSE_MEASUREMENT_CONTEXT_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A34-0000-1000-8000-00805f9b34fb")

        // Contour Glucose Service
        val CONTOUR_SERVICE_UUID: UUID = UUID.fromString("00000000-0002-11E2-9E96-0800200C9A66")
        private val CONTOUR_CLOCK = UUID.fromString("00001026-0002-11E2-9E96-0800200C9A66")
        private var instance: BluetoothHandler? = null

        private val supportedServices = arrayOf(BLP_SERVICE_UUID, HTS_SERVICE_UUID, HRS_SERVICE_UUID, PLX_SERVICE_UUID, WSS_SERVICE_UUID, GLUCOSE_SERVICE_UUID)

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): BluetoothHandler {
            if (instance == null) {
                instance = BluetoothHandler(context.applicationContext)
            }
            return requireNotNull(instance)
        }
    }

    @JvmField
    var central: BluetoothCentralManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        central = BluetoothCentralManager(context)

        central.observeConnectionState { peripheral, state ->
            Timber.i("Peripheral '${peripheral.name}' is $state")
            when (state) {
                ConnectionState.CONNECTED -> handlePeripheral(peripheral)
                ConnectionState.DISCONNECTED -> scope.launch {
                    delay(15000)

                    // Check if this peripheral should still be auto connected
                    if (central.getPeripheral(peripheral.address).getState() == ConnectionState.DISCONNECTED) {
                        central.autoConnectPeripheral(peripheral)
                    }
                }
                else -> {
                }
            }
        }

        central.observeAdapterState { state ->
            when (state) {
                BluetoothAdapter.STATE_ON -> startScanning()
            }
        }

        startScanning()
    }
}