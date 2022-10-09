package me.chayut.blerelay

import android.bluetooth.BluetoothAdapter
import android.content.Context
import com.influxdb.client.domain.WritePrecision
import com.influxdb.client.kotlin.InfluxDBClientKotlinFactory
import com.influxdb.client.write.Point
import com.welie.blessed.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import timber.log.Timber
import java.time.Instant
import java.util.*


internal class BluetoothHandler private constructor(context: Context) {

    val temperatureChannel = Channel<TemperatureMeasurement>(Channel.UNLIMITED)
    val humidityChannel = Channel<HumidityMeasurement>(Channel.UNLIMITED)

    private fun handlePeripheral(peripheral: BluetoothPeripheral) {
        scope.launch {
            try {
                val mtu = peripheral.requestMtu(185)
                Timber.i("MTU is $mtu")

                peripheral.requestConnectionPriority(ConnectionPriority.HIGH)

                val rssi = peripheral.readRemoteRssi()
                Timber.i("RSSI is $rssi")

                setupTemperatureNotifications(peripheral)
                setupHumidityNotifications(peripheral)

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


                Timber.d("1 :%X", value[0])
                Timber.d("2: %X", value[1])

                val tempInt = value[1].toInt() * 256 +  value[0].toUByte().toInt()
                Timber.d("Temp: %d", tempInt)
                val tempVal = tempInt.toFloat()/ 100.0f


                val measurement = TemperatureMeasurement(temperatureValue = tempVal)
                temperatureChannel.trySend(measurement)

                scope.launch {
                    try {
                        // You can generate an API token from the "API Tokens Tab" in the UI
                        val token = BuildConfig.apikey //System.getenv()["INFLUX_TOKEN"]
                        val org = "nonesecure@gmail.com"
                        val bucket = "test2"

                        val client = InfluxDBClientKotlinFactory.create("https://ap-southeast-2-1.aws.cloud2.influxdata.com", token.toCharArray(), org, bucket)

                        val writeApi = client.getWriteKotlinApi()

                        val point = Point
                            .measurement("environment")
                            .addTag("host", "host1")
                            .addField("temperature", tempVal)
                            .time(Instant.now(), WritePrecision.NS);

                        writeApi.writePoint(point)

                    } catch (e: Exception) {
                        Timber.e(e)
                    }
                }

            }
        }
    }

    private suspend fun  setupHumidityNotifications(peripheral: BluetoothPeripheral) {
        peripheral.getCharacteristic(BLE_UUID_ENVIRONMENTAL_SENSING_SERVICE, BLE_UUID_HUMIDITY)?.let {
            peripheral.observe(it) { value ->

                Timber.d("1 :%X", value[0])
                Timber.d("2: %X", value[1])

                val humidityInt = value[1].toInt() * 256 +  value[0].toUByte().toInt()
                Timber.d("Humidity: %d", humidityInt)

                val humidityVal = humidityInt.toFloat()/ 100.0f

                val measurement = HumidityMeasurement(humidityValue = humidityVal)
                humidityChannel.trySend(measurement)

                scope.launch {
                    try {
                        // You can generate an API token from the "API Tokens Tab" in the UI
                        val token = BuildConfig.apikey //System.getenv()["INFLUX_TOKEN"]
                        val org = "nonesecure@gmail.com"
                        val bucket = "test2"

                        val client = InfluxDBClientKotlinFactory.create("https://ap-southeast-2-1.aws.cloud2.influxdata.com", token.toCharArray(), org, bucket)

                        val writeApi = client.getWriteKotlinApi()

                        val point = Point
                            .measurement("environment")
                            .addTag("host", "host1")
                            .addField("humidity", humidityVal)
                            .time(Instant.now(), WritePrecision.NS);

                        writeApi.writePoint(point)

                    } catch (e: Exception) {
                        Timber.e(e)
                    }
                }

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