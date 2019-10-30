package nl.tudelft.meshnet.connectivity

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.widget.Toast
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.Exception
import java.util.*

abstract class BaseBluetoothConnectivityManager(
    private val context: Context
) : ConnectivityManager(context) {
    val bluetoothAdapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    private var connectedThreads: MutableMap<String, ConnectedThread> = mutableMapOf()

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

    override fun isBluetoothRequired(): Boolean {
        return true
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

    protected open fun createBluetoothServerSocket(): BluetoothServerSocket {
        return bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(NAME, MY_UUID)
    }

    protected open fun createBluetoothSocket(device: BluetoothDevice): BluetoothSocket {
        return device.createInsecureRfcommSocketToServiceRecord(MY_UUID)
    }

    companion object {
        private const val TAG = "BaseBTConnectivityMgr"
    }

    private val NAME = "meshnet"
    private val MY_UUID = UUID.fromString("62c94792-5e72-461b-bbf4-4be7360776b5")

    inner class AcceptThread : Thread() {

        private val mmServerSocket: BluetoothServerSocket? by lazy(LazyThreadSafetyMode.NONE) {
            createBluetoothServerSocket()
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

    inner class ConnectThread(device: BluetoothDevice) : Thread() {

        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            createBluetoothSocket(device)
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

    inner class ConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {

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

                val body = mmBuffer.copyOf(numBytes)
                handleMessageReceived(body)
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
