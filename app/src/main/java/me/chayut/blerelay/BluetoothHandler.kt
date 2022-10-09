package me.chayut.blerelay

import android.bluetooth.BluetoothAdapter
import android.content.Context
import com.welie.blessed.*
import kotlinx.coroutines.*
import timber.log.Timber
import java.math.BigInteger
import java.util.*


internal class BluetoothHandler private constructor(context: Context) {

//    val environmentChannel = Channel<BloodPressureMeasurement>(UNLIMITED)

    private fun handlePeripheral(peripheral: BluetoothPeripheral) {
        scope.launch {
            try {
                val mtu = peripheral.requestMtu(185)
                Timber.i("MTU is $mtu")

                peripheral.requestConnectionPriority(ConnectionPriority.HIGH)

                val rssi = peripheral.readRemoteRssi()
                Timber.i("RSSI is $rssi")

                setupTemperatureNotifications(peripheral)

            } catch (e: IllegalArgumentException) {
                Timber.e(e)
            } catch (b: GattException) {
                Timber.e(b)
            }
        }
    }

    private suspend fun  setupTemperatureNotifications(peripheral: BluetoothPeripheral) {
        peripheral.getCharacteristic(BLE_UUID_ENVIRONMENTAL_SENSING_SERVICE, BLE_UUID_TEMPERATURE)?.let {
            peripheral.observe(it) { value ->
//                val measurement = HeartRateMeasurement.fromBytes(value)
//                heartRateChannel.trySend(measurement)
                Timber.d("1 :%X", value[0])
                Timber.d("2: %X", value[1])

                val tempInt = value[1].toInt() * 256 +  value[0].toUByte().toInt()
                Timber.d("Temp: %d", tempInt)
            }
        }
    }


    private fun startScanning() {
        Timber.d("startScanning")
        central.scanForPeripheralsWithServices(supportedServices,
            { peripheral, scanResult ->
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


        private val BLE_UUID_ENVIRONMENTAL_SENSING_SERVICE: UUID = UUID.fromString("0000181A-0000-1000-8000-00805f9b34fb")
        private val BLE_UUID_TEMPERATURE: UUID = UUID.fromString("00002A6E-0000-1000-8000-00805f9b34fb")
        private val BLE_UUID_HUMIDITY : UUID = UUID.fromString("00002A6F-0000-1000-8000-00805f9b34fb")
        private val BLE_UUID_PRESSURE: UUID = UUID.fromString("00002A6D-0000-1000-8000-00805f9b34fb")

        private var instance: BluetoothHandler? = null

        private val supportedServices = arrayOf(BLE_UUID_ENVIRONMENTAL_SENSING_SERVICE)

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
        Timber.i("init")
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