package nl.tudelft.meshnet.connectivity

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.Parcelable
import android.util.Log
import nl.tudelft.meshnet.util.SingletonHolder
import java.util.*


class BleGattConnectivityManager(
    private val context: Context
) : BaseBluetoothConnectivityManager(context) {
    override fun isSupported(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val leScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner
    }

    private val leAdvertiser by lazy {
        bluetoothAdapter.bluetoothLeAdvertiser
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange")
            val state = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> EndpointState.CONNECTED
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTING -> EndpointState.CONNECTING
                else -> EndpointState.DISCOVERED
            }
            addEndpoint(Endpoint(device.address, device.name, state))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (value != null) {
                handleMessageReceived(value)
                gattServer?.sendResponse(device, requestId, 0, offset, value)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            Log.d(TAG, "onCharacteristicReadRequest")

            val value = messages.value?.lastOrNull()?.message?.toByteArray()
            gattServer?.sendResponse(device, requestId, 0, offset, value)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: $newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                updateEndpointState(gatt.device.address, EndpointState.CONNECTED)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                updateEndpointState(gatt.device.address, EndpointState.DISCOVERED)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered $status")

            val service = gatt.getService(SERVICE_UUID)
            if (service != null) {
                Log.d(TAG, "enabling notifications")
                val characteristic = service.getCharacteristic(MESSAGES_UUID)
                gatt.setCharacteristicNotification(characteristic, true)
            } else {
                Log.e(TAG, "service is null")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicRead")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            Log.d(TAG, "onCharacteristicChanged")
            handleMessageReceived(characteristic.value)
        }
    }

    private var gatts: MutableMap<String, BluetoothGatt> = mutableMapOf()

    private var gattServer: BluetoothGattServer? = null
    private var messagesCharacteristic: BluetoothGattCharacteristic? = null

    private val scanCallback = object : ScanCallback() {
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            Log.d(TAG, "onBatchScanResults " + results.size)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.d(TAG, "onScanFailed $errorCode")
            discoveryStatus.value = ConnectivityStatus.INACTIVE
        }

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            //val success = device.fetchUuidsWithSdp()
            //Log.d(TAG, "onScanResult $callbackType " + device.address + " " + device.name + " " + success + " " + device.uuids)
            val uuids = result.scanRecord?.serviceUuids?.map {
                it.uuid
            }
            Log.d(TAG, "onScanResult $callbackType " + device.address + " " + device.name + " " + uuids)

            if (uuids?.contains(SERVICE_UUID) == true) {
                addEndpoint(Endpoint(device.address, device.name, EndpointState.DISCOVERED))
            }

        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "onStartSuccess")
            advertisingStatus.value = ConnectivityStatus.ACTIVE
        }

        override fun onStartFailure(errorCode: Int) {
            Log.d(TAG, "onStartFailure $errorCode")
            advertisingStatus.value = ConnectivityStatus.INACTIVE
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action: String = intent.action!!
            when (action) {
                BluetoothDevice.ACTION_UUID -> {
                    // Discovery has found a device. Get the BluetoothDevice
                    // object and its info from the Intent.
                    val device: BluetoothDevice =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val parceUuids: Array<Parcelable> =
                        intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID) ?: arrayOf()
                    val uuids = parceUuids.map { (it as ParcelUuid).uuid }
                    val deviceName = device.name
                    val deviceHardwareAddress = device.address // MAC address
                    Log.d(TAG,"found device: $deviceName $deviceHardwareAddress $uuids")
                    for (uuid in uuids) {
                        if (uuid == SERVICE_UUID) {
                            addEndpoint(Endpoint(deviceHardwareAddress, deviceName, EndpointState.DISCOVERED))
                        }
                    }
                }
            }
        }
    }

    override fun startDiscovery() {
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_UUID))

        val settingsBuilder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            settingsBuilder.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            settingsBuilder.setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            settingsBuilder.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        }

        val serviceScanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        discoveryStatus.value = ConnectivityStatus.ACTIVE
        leScanner.startScan(null, settingsBuilder.build(), scanCallback)
    }

    override fun stopDiscovery() {
        context.unregisterReceiver(receiver)

        leScanner.stopScan(scanCallback)
        discoveryStatus.value = ConnectivityStatus.INACTIVE
    }

    override fun startAdvertising() {
        super.startAdvertising()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTimeout(0)
            // make sure the server is connectable!
            .setConnectable(true)
            .build()
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        leAdvertiser.startAdvertising(settings, advertiseData, advertiseCallback)

        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        val addedService = gattServer?.addService(createGattService())
        Log.d(TAG, "addService? $addedService")
    }

    private fun createGattService(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val messages = BluetoothGattCharacteristic(MESSAGES_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE)
        messagesCharacteristic = messages

        service.addCharacteristic(messages)

        return service
    }

    override fun stopAdvertising() {
        super.stopAdvertising()
        leAdvertiser.stopAdvertising(advertiseCallback)
        gattServer?.close()
    }

    override fun requestConnection(endpointId: String) {
        updateEndpointState(endpointId, EndpointState.CONNECTING)

        val remoteDevice = bluetoothAdapter.getRemoteDevice(endpointId)
        val gatt = remoteDevice.connectGatt(context, false, gattCallback)

        gatts[endpointId] = gatt
    }

    override fun disconnectFromEndpoint(endpointId: String) {
        val gatt = gatts.remove(endpointId)
        gatt?.close()
        updateEndpointState(endpointId, EndpointState.DISCOVERED)
    }

    override fun sendMessage(endpoint: Endpoint, message: String) {
        val gatt = gatts[endpoint.endpointId]
        if (gatt != null) {
            Log.d(TAG, "Send message to gatt " + endpoint.endpointId)
            val service = gatt.getService(SERVICE_UUID)
            val characteristic = service.getCharacteristic(MESSAGES_UUID)
            characteristic.value = message.toByteArray()
            val success = gatt.writeCharacteristic(characteristic)
            Log.d(TAG, "writeCharacteristic " + success)
        } else if (gattServer != null) {
            Log.d(TAG, "update message characteristic value")
            messagesCharacteristic?.value = message.toByteArray()
            for (e in endpoints.value!!) {
                val device = bluetoothAdapter.getRemoteDevice(e.endpointId)
                val notified = gattServer?.notifyCharacteristicChanged(device, messagesCharacteristic,false)
                Log.d(TAG, "notify endpoint " + e.endpointId + " " + notified)
            }
        } else {
            Log.e(TAG, "Gatt not found for $endpoint")
        }
    }

    companion object : SingletonHolder<BleGattConnectivityManager, Context>(::BleGattConnectivityManager) {
        private const val TAG = "BleGattConnectivityMgr"
        private val SERVICE_UUID = UUID.fromString("62c94792-5e72-461b-bbf4-4be7360776b5")

        val MESSAGES_UUID: UUID = UUID.fromString("00002a2b-0000-1000-8000-00805f9b34fb")
        val SEND_MESSAGE_UUID: UUID = UUID.fromString("00002a2c-0000-1000-8000-00805f9b34fb")
    }
}
