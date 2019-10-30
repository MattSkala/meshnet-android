package nl.tudelft.meshnet.connectivity

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.*
import android.net.wifi.WpsInfo
import android.net.wifi.aware.*
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.*
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import nl.tudelft.meshnet.util.SingletonHolder
import java.io.*
import java.lang.Exception
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.*
import kotlin.random.Random

class WifiDirectConnectivityManager(
    private val context: Context
) : ConnectivityManager(context) {

    private val intentFilter = IntentFilter()
    private val broadcastReceiver = WifiDirectBroadcastReceiver()

    private val p2pManager: WifiP2pManager by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    }

    private val discoveryListener = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            if (discoveryStatus.value == ConnectivityStatus.PENDING) {
                discoveryStatus.value = ConnectivityStatus.ACTIVE
            }
        }

        override fun onFailure(reasonCode: Int) {
            discoveryStatus.value = ConnectivityStatus.INACTIVE
        }
    }

    private val peerListListener = WifiP2pManager.PeerListListener { peerList ->
        val deviceList = peerList.deviceList

        for (device in deviceList) {
            if (findEndpoint(device.deviceAddress) == null) {
                val state = when (device.status) {
                    WifiP2pDevice.CONNECTED -> EndpointState.CONNECTED
                    WifiP2pDevice.INVITED -> EndpointState.CONNECTING
                    else -> EndpointState.DISCOVERED
                }
                addEndpoint(
                    Endpoint(
                        device.deviceAddress,
                        device.deviceName,
                        state
                    )
                )
            }
        }
    }

    private val connectionListener = WifiP2pManager.ConnectionInfoListener { info ->

        // InetAddress from WifiP2pInfo struct.
        val groupOwnerAddress: String = info.groupOwnerAddress.hostAddress

        // After the group negotiation, we can determine the group owner
        // (server).
        if (info.groupFormed && info.isGroupOwner) {
            // Do whatever tasks are specific to the group owner.
            // One common case is creating a group owner thread and accepting
            // incoming connections.
            acceptThread = AcceptThread()
            acceptThread?.start()
        } else if (info.groupFormed) {
            // The other device acts as the peer (client). In this case,
            // you'll want to create a peer thread that connects
            // to the group owner.
            connectThread = ConnectThread(info.groupOwnerAddress)
            connectThread?.start()
        }
    }

    private var channel: WifiP2pManager.Channel

    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    private var connectedThreads: MutableMap<String, ConnectedThread> = mutableMapOf()

    init {
        // Indicates a change in the Wi-Fi P2P status.
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)

        // Indicates a change in the list of available peers.
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)

        // Indicates the state of Wi-Fi P2P connectivity has changed.
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)

        // Indicates this device's details have changed.
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)

        channel = p2pManager.initialize(context, Looper.getMainLooper(), null)
    }

    override fun isSupported(): Boolean {
        return true
    }

    override fun startAdvertising() {
        // not implemented
    }

    override fun stopAdvertising() {
        // not implemented
    }

    override fun startDiscovery() {
        discoveryStatus.value = ConnectivityStatus.PENDING
        context.registerReceiver(broadcastReceiver, intentFilter)
        p2pManager.discoverPeers(channel, discoveryListener)
    }

    override fun stopDiscovery() {
        context.unregisterReceiver(broadcastReceiver)
        p2pManager.stopPeerDiscovery(channel, discoveryListener)
        discoveryStatus.value = ConnectivityStatus.INACTIVE
    }

    override fun requestConnection(endpointId: String) {
        val config = WifiP2pConfig().apply {
            deviceAddress = endpointId
            wps.setup = WpsInfo.PBC
        }

        p2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                updateEndpointState(endpointId, EndpointState.CONNECTING)
                //updateEndpointState(endpointId, EndpointState.CONNECTED)
            }

            override fun onFailure(reasonCode: Int) {
                //updateEndpointState(endpointId, EndpointState.DISCOVERED)
            }
        })
    }

    override fun disconnectFromEndpoint(endpointId: String) {
        // not implemented
    }

    override fun sendMessage(endpoint: Endpoint, message: String) {
        val thread = connectedThreads[endpoint.endpointId]
        if (thread != null) {
            Thread(Runnable {
                thread.write(message.toByteArray())
            }).start()
        } else {
            Log.e(TAG, "thread for endpoint " + endpoint.endpointId + " not found")
        }
    }

    private fun manageMyConnectedSocket(socket: Socket) {
        Log.d(TAG, "got a socket: " + socket + " " + socket.remoteSocketAddress + " " + socket.inetAddress)

        val address = socket.inetAddress.hostAddress

        addEndpoint(
            Endpoint(
                address,
                address,
                EndpointState.CONNECTED
            )
        )

        val connectedThread = ConnectedThread(socket)
        connectedThreads[address] = connectedThread
        connectedThread.start()
    }

    companion object : SingletonHolder<WifiDirectConnectivityManager, Context>(::WifiDirectConnectivityManager) {
        private const val SERVICE_NAME = "meshnet"
        private const val TAG = "WifiDirectManager"
    }

    inner class WifiDirectBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when(intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    // Determine if Wifi P2P mode is enabled or not, alert
                    // the Activity.
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    /*
                    activity.isWifiP2pEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED

                     */
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {

                    // The peer list has changed! We should probably do something about
                    // that.

                    p2pManager.requestPeers(channel, peerListListener)

                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {

                    // Connection state changed! We should probably do something about
                    // that.

                    val networkInfo: NetworkInfo? = intent
                        .getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO) as NetworkInfo

                    if (networkInfo?.isConnected == true) {
                        // We are connected with the other device, request connection
                        // info to find group owner IP

                        p2pManager.requestConnectionInfo(channel, connectionListener)
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {

                    /*
                    (activity.supportFragmentManager.findFragmentById(R.id.frag_list) as DeviceListFragment)
                        .apply {
                            updateThisDevice(
                                intent.getParcelableExtra(
                                    WifiP2pManager.EXTRA_WIFI_P2P_DEVICE) as WifiP2pDevice
                            )
                        }

                     */
                }
            }
        }
    }

    inner class AcceptThread : Thread() {

        private val serverSocket = ServerSocket(8888)

        var shouldLoop = true

        override fun run() {
            while (shouldLoop) {
                val socket: Socket? = try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    Log.e(TAG, "Socket's accept() method failed", e)
                    null
                }
                socket?.also {
                    manageMyConnectedSocket(it)
                }
            }
        }

        // Closes the connect socket and causes the thread to finish.
        fun cancel() {
            shouldLoop = false
            try {
                Log.d(TAG, "AcceptThread cancel")
                serverSocket.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the connect socket", e)
            }
        }
    }

    inner class ConnectThread(val groupOwner: InetAddress) : Thread() {

        private val socket = Socket()

        public override fun run() {
            try {
                socket.bind(null)
                socket.connect(InetSocketAddress(groupOwner.hostAddress, 8888), 500)
                manageMyConnectedSocket(socket)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Closes the client socket and causes the thread to finish.
        fun cancel() {
            try {
                Log.d(TAG, "ConnectThread cancel")
                socket.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the client socket", e)
            }
        }
    }

    inner class ConnectedThread(private val socket: Socket) : Thread() {

        private val mmInStream: InputStream = socket.inputStream
        private val mmOutStream: OutputStream = socket.outputStream
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

            //updateEndpointState(socket.inetAddress.toString(), EndpointState.DISCOVERED)
        }

        // Call this from the main activity to send data to the remote device.
        fun write(bytes: ByteArray) {
            try {
                Log.d(TAG, "sending bytes to " + socket.inetAddress)
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
                socket.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the connect socket", e)
            }
        }

    }
}
