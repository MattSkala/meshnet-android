package nl.tudelft.meshnet.connectivity

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.widget.Toast
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.Exception
import java.util.*

class BluetoothConnectivityManager(
    private val context: Context
) : ConnectivityManager(context) {
    val bluetoothAdapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    private var connectedThreads: MutableMap<String, ConnectedThread> = mutableMapOf()

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

    override fun startAdvertising() {
        acceptThread = AcceptThread()
        acceptThread?.start()
        advertisingStatus.value = ConnectivityStatus.ACTIVE
    }

    override fun stopAdvertising() {
        acceptThread?.cancel()
        advertisingStatus.value = ConnectivityStatus.INACTIVE
    }

    override fun requestConnection(endpointId: String) {
        updateEndpointState(endpointId, EndpointState.CONNECTING)

        val remoteDevice = bluetoothAdapter.getRemoteDevice(endpointId)
        Log.d(TAG, "requestConnection: $remoteDevice")
        connectThread?.cancel()
        connectThread = ConnectThread(remoteDevice)
        connectThread?.start()
    }

    override fun disconnectFromEndpoint(endpointId: String) {
        val thread = connectedThreads[endpointId]
        connectedThreads.remove(endpointId)
        thread?.cancel()

        updateEndpointState(endpointId, EndpointState.DISCOVERED)
    }

    override fun sendMessage(endpoint: Endpoint, message: String) {
        val thread = connectedThreads[endpoint.endpointId]
        if (thread != null) {
            thread.write(message.toByteArray())
        } else {
            Log.e(TAG, "thread for endpoint " + endpoint.endpointId + " not found")
        }
    }

    private fun manageMyConnectedSocket(socket: BluetoothSocket) {
        Log.d(TAG, "got a socket!")

        val address = socket.remoteDevice.address
        addEndpoint(
            Endpoint(
                address,
                socket.remoteDevice.name,
                EndpointState.CONNECTED
            )
        )

        val connectedThread = ConnectedThread(socket)
        connectedThreads[address] = connectedThread
        connectedThread.start()
    }

    companion object : ConnectivityManagerFactory {
        private const val TAG = "BTConnectivityMgr"

        @SuppressLint("StaticFieldLeak")
        private var instance: BluetoothConnectivityManager? = null

        override fun getInstance(context: Context): ConnectivityManager {
            val i = instance
            if (i!= null) {
                return i
            }

            val i2 = BluetoothConnectivityManager(context.applicationContext)
            instance = i2
            return i2
        }
    }

    private val NAME = "meshnet"
    private val MY_UUID = UUID.fromString("62c94792-5e72-461b-bbf4-4be7360776b5")
    private inner class AcceptThread : Thread() {

        private val mmServerSocket: BluetoothServerSocket? by lazy(LazyThreadSafetyMode.NONE) {
            bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(NAME, MY_UUID)
        }

        var shouldLoop = true

        override fun run() {
            // Keep listening until exception occurs or a socket is returned.
            while (shouldLoop) {
                val socket: BluetoothSocket? = try {
                    mmServerSocket?.accept()
                } catch (e: IOException) {
                    Log.e(TAG, "Socket's accept() method failed", e)
                    //shouldLoop = false
                    null
                }
                socket?.also {
                    manageMyConnectedSocket(it)
                    //mmServerSocket?.close()
                    //shouldLoop = false
                }
            }
        }

        // Closes the connect socket and causes the thread to finish.
        fun cancel() {
            shouldLoop = false
            try {
                Log.d(TAG, "AcceptThread cancel")
                mmServerSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the connect socket", e)
            }
        }
    }

    private inner class ConnectThread(device: BluetoothDevice) : Thread() {

        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            //device.createRfcommSocketToServiceRecord(MY_UUID)
            device.createInsecureRfcommSocketToServiceRecord(MY_UUID)
        }

        public override fun run() {
            // Cancel discovery because it otherwise slows down the connection.
            bluetoothAdapter.cancelDiscovery()
            discoveryStatus.postValue(ConnectivityStatus.INACTIVE)

            try {
                mmSocket?.let { socket ->
                    // Connect to the remote device through the socket. This call blocks
                    // until it succeeds or throws an exception.
                    socket.connect()

                    // The connection attempt succeeded. Perform work associated with
                    // the connection in a separate thread.
                    manageMyConnectedSocket(socket)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val address = mmSocket?.remoteDevice?.address
                if (address != null) {
                    removeEndpoint(address)
                }
            }
        }

        // Closes the client socket and causes the thread to finish.
        fun cancel() {
            try {
                Log.d(TAG, "ConnectThread cancel")
                //mmSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the client socket", e)
            }
        }
    }

    private inner class ConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {

        private val mmInStream: InputStream = mmSocket.inputStream
        private val mmOutStream: OutputStream = mmSocket.outputStream
        private val mmBuffer: ByteArray = ByteArray(1024) // mmBuffer store for the stream

        override fun run() {
            var numBytes: Int // bytes returned from read()

            // Keep listening to the InputStream until an exception occurs.
            while (true) {
                // Read from the InputStream.
                numBytes = try {
                    mmInStream.read(mmBuffer)
                } catch (e: IOException) {
                    Log.d(TAG, "Input stream was disconnected", e)
                    break
                }

                Log.d(TAG, "Read $numBytes bytes")

                val text = mmBuffer.copyOf(numBytes).toString(Charsets.UTF_8)
                val sender = mmSocket.remoteDevice.name ?: mmSocket.remoteDevice.address
                addMessage(Message(text, Date(), sender))
            }

            updateEndpointState(mmSocket.remoteDevice.address, EndpointState.DISCOVERED)
        }

        // Call this from the main activity to send data to the remote device.
        fun write(bytes: ByteArray) {
            try {
                Log.d(TAG, "sending bytes to " + mmSocket.remoteDevice.address)
                mmOutStream.write(bytes)
            } catch (e: IOException) {
                Log.e(TAG, "Error occurred when sending data", e)

                // Send a failure message back to the activity.
                return
            }
        }

        // Call this method from the main activity to shut down the connection.
        fun cancel() {
            try {
                Log.d(TAG, "ConnectedThread cancel")
                mmSocket.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the connect socket", e)
            }
        }
    }
}
