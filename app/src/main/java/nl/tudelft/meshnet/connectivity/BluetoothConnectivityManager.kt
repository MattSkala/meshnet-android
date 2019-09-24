package nl.tudelft.meshnet.connectivity

import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.widget.Toast
import nl.tudelft.meshnet.util.SingletonHolder

class BluetoothConnectivityManager(
    private val context: Context
) : BaseBluetoothConnectivityManager(context) {

    // Create a BroadcastReceiver for ACTION_FOUND.
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action: String = intent.action!!
            when (action) {
                BluetoothDevice.ACTION_FOUND -> {
                    // Discovery has found a device. Get the BluetoothDevice
                    // object and its info from the Intent.
                    val device: BluetoothDevice =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val deviceClass: BluetoothClass =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_CLASS)
                    val deviceName = device.name
                    val deviceHardwareAddress = device.address // MAC address
                    Log.d("BTConnectivityMgr","found device: $deviceName $deviceHardwareAddress ${deviceClass.deviceClass}")
                    addEndpoint(Endpoint(deviceHardwareAddress, deviceName, EndpointState.DISCOVERED))
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    stopDiscovery()
                    Toast.makeText(context, "Discovery finished", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun startDiscovery() {
        // Register for broadcasts when a device is discovered.
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED))

        if (bluetoothAdapter.startDiscovery()) {
            discoveryStatus.value = ConnectivityStatus.ACTIVE
        }
    }

    override fun stopDiscovery() {
        context.unregisterReceiver(receiver)
        bluetoothAdapter.cancelDiscovery()
        discoveryStatus.value = ConnectivityStatus.INACTIVE
    }

    companion object : SingletonHolder<BluetoothConnectivityManager, Context>(::BluetoothConnectivityManager)
}
