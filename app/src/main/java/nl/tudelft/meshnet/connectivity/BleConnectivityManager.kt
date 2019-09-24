package nl.tudelft.meshnet.connectivity

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.os.Parcelable
import android.util.Log
import nl.tudelft.meshnet.util.SingletonHolder
import java.util.*


class BleConnectivityManager(
    private val context: Context
) : BaseBluetoothConnectivityManager(context) {
    private val leScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner
    }

    private val leAdvertiser by lazy {
        bluetoothAdapter.bluetoothLeAdvertiser
    }

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
            .setConnectable(false)
            .build()
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        leAdvertiser.startAdvertising(settings, advertiseData, advertiseCallback)
    }

    override fun stopAdvertising() {
        super.stopAdvertising()
        leAdvertiser.stopAdvertising(advertiseCallback)
    }

    companion object : SingletonHolder<BleConnectivityManager, Context>(::BleConnectivityManager) {
        private const val TAG = "BleConnectivityManager"
        private val SERVICE_UUID = UUID.fromString("62c94792-5e72-461b-bbf4-4be7360776b5")
    }
}
