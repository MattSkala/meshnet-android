package nl.tudelft.meshnet.connectivity

import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.*
import android.os.Build
import android.os.Handler
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import nl.tudelft.meshnet.util.SingletonHolder
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
import java.util.*
import kotlin.random.Random

// TODO: refactor connection for availability on >= O
@RequiresApi(Build.VERSION_CODES.Q)
class WifiAwareConnectivityManager(
    private val context: Context
) : ConnectivityManager(context) {

    private val wifiAwareManager: WifiAwareManager? by lazy {
        context.getSystemService(Context.WIFI_AWARE_SERVICE) as WifiAwareManager?
    }

    private val connectivityManager: android.net.ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    }

    private var wifiAwareSession: WifiAwareSession? = null
    private var publishDiscoverySession: PublishDiscoverySession? = null
    private var subscribeDiscoverySession: SubscribeDiscoverySession? = null
    private var peerAwareInfo: WifiAwareNetworkInfo? = null

    private val peerNetworkCallbacks = mutableMapOf<PeerHandle, android.net.ConnectivityManager.NetworkCallback>()
    private val peerSockets = mutableMapOf<PeerHandle, Socket>()

    private var acceptThread: AcceptThread? = null
    //private var connectThread: ConnectThread? = null
    private var connectedThreads: MutableMap<String, ConnectedThread> = mutableMapOf()

    private val peerNetworkCapabilities = mutableMapOf<PeerHandle, NetworkCapabilities>()

    private fun createSocket(peerHandle: PeerHandle, network: Network, networkCapabilities: NetworkCapabilities) {
        val peerAwareInfo = networkCapabilities.transportInfo as WifiAwareNetworkInfo
        val peerIpv6 = peerAwareInfo.peerIpv6Addr
        val peerPort = peerAwareInfo.port
        Log.d(TAG, "peer: $peerIpv6 $peerPort")

        try {
            val socket = network.socketFactory.createSocket(peerIpv6, peerPort)
            Log.d(TAG, "created socket to $peerIpv6 $peerPort")
            peerSockets[peerHandle] = socket

            manageMyConnectedSocket(socket)
        } catch (e: ConnectException) {
            e.printStackTrace()
        }
    }

    private fun createNetworkCallback(peerHandle: PeerHandle, isServer: Boolean): android.net.ConnectivityManager.NetworkCallback {
        return object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "onAvailable: " + network.networkHandle)
                addEndpoint(Endpoint(peerHandle.toString(), null, EndpointState.CONNECTED))
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                peerAwareInfo = networkCapabilities.transportInfo as WifiAwareNetworkInfo
                val peerIpv6 = peerAwareInfo?.peerIpv6Addr
                val peerPort = peerAwareInfo?.port
                Log.d(TAG, "onCapabilitiesChanged: $peerIpv6 $peerPort")
                peerNetworkCapabilities[peerHandle] = networkCapabilities

                if (peerSockets[peerHandle] == null && !isServer) {
                    // Create a socket for a client to connect to the server
                    createSocket(peerHandle, network, networkCapabilities)
                }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "onLost")
                removeEndpoint(peerHandle.toString())
            }
        }
    }

    private val peerHandles = mutableMapOf<String, PeerHandle>()

    override fun isSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
    }

    override fun startAdvertising() {
        if (wifiAwareManager?.isAvailable != true) return

        advertisingStatus.value = ConnectivityStatus.PENDING

        wifiAwareManager?.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession?) {
                Log.d(TAG, "onAttached")
                wifiAwareSession = session

                startPublishing()
            }

            override fun onAttachFailed() {
                Log.d(TAG, "onAttachFailed")
            }
        }, null)
    }

    private fun startPublishing() {
        val config: PublishConfig = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .build()

        wifiAwareSession?.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                publishDiscoverySession = session
                advertisingStatus.value = ConnectivityStatus.ACTIVE
                Log.d(TAG, "onPublishStarted")
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                Log.d(TAG, "onMessageReceived: " + peerHandle.toString() + " " + message)

                val endpointId = peerHandle.toString()
                peerHandles[endpointId] = peerHandle
                if (findEndpoint(endpointId) == null) {
                    addEndpoint(Endpoint(endpointId, null, EndpointState.CONNECTING))
                }

                // If this is the first message from this peer, request a network
                if (!peerNetworkCallbacks.containsKey(peerHandle)) {
                    requestNetwork(publishDiscoverySession!!, peerHandle, 1234)
                }

                addMessage(Message(message.toString(Charsets.UTF_8), Date(), peerHandle.toString()))
            }

            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray,
                matchFilter: MutableList<ByteArray>
            ) {
                Log.d(TAG, "onServiceDiscovered: " + peerHandle.toString())

                val endpointId = peerHandle.toString()
                peerHandles[endpointId] = peerHandle
            }
        }, null)

        val ss = ServerSocket(1234)
        val port = ss.localPort
        Log.d(TAG, "server socket local port: $port")
        // TODO: store server socket reference, accept incoming connections?

        acceptThread = AcceptThread(ss)
        acceptThread?.start()
    }

    private fun requestNetwork(discoverySession: DiscoverySession, peerHandle: PeerHandle, port: Int? = null) {
        Log.d(TAG, "requestNetwork "  + peerHandle.toString())

        val networkSpecifierBuilder = WifiAwareNetworkSpecifier.Builder(discoverySession, peerHandle)
            .setPskPassphrase("somePassword")
        if (port != null) {
            networkSpecifierBuilder.setPort(port)
        }
        val networkSpecifier = networkSpecifierBuilder.build()
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(networkSpecifier)
            .build()

        val networkCallback = createNetworkCallback(peerHandle, port != null)
        peerNetworkCallbacks[peerHandle] = networkCallback;
        connectivityManager.requestNetwork(networkRequest, networkCallback);
    }

    override fun stopAdvertising() {
        publishDiscoverySession?.close()
        acceptThread?.cancel()
        advertisingStatus.value = ConnectivityStatus.INACTIVE
    }

    override fun startDiscovery() {
        if (wifiAwareManager?.isAvailable != true) return

        discoveryStatus.value = ConnectivityStatus.PENDING

        wifiAwareManager?.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession?) {
                Log.d(TAG, "onAttached")
                wifiAwareSession = session

                startSubscribing()
            }

            override fun onAttachFailed() {
                Log.d(TAG, "onAttachFailed")
            }
        }, null)
    }

    private fun startSubscribing() {
        val config: SubscribeConfig = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .build()

        wifiAwareSession?.subscribe(config, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                subscribeDiscoverySession = session
                discoveryStatus.value = ConnectivityStatus.ACTIVE
                Log.d(TAG, "onSubscribeStarted")
            }

            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray,
                matchFilter: List<ByteArray>
            ) {
                Log.d(TAG, "onServiceDiscovered")
                // TODO: store endpointId in serviceSpecificInfo
                val endpointId = peerHandle.toString()
                peerHandles[endpointId] = peerHandle
                addEndpoint(Endpoint(endpointId, null, EndpointState.DISCOVERED))
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                Log.d(TAG, "onMessageReceived: " + peerHandle.toString() + " " + message)

                addMessage(Message(message.toString(Charsets.UTF_8), Date(), peerHandle.toString()))
            }
        }, null)
    }

    override fun requestConnection(endpointId: String) {
        Log.d(TAG, "requestConnection " + endpointId)

        updateEndpointState(endpointId, EndpointState.CONNECTING)

        val peerHandle = peerHandles[endpointId]!!
        val messageId = Random.nextInt()
        val message = "CONNECTION_REQUEST"
        subscribeDiscoverySession?.sendMessage(peerHandle, messageId, message.toByteArray())

        // TODO: wait until the publisher requests the network in a better way?
        Handler().postDelayed(Runnable {
            requestNetwork(subscribeDiscoverySession!!, peerHandle)
        }, 5000)
    }

    override fun disconnectFromEndpoint(endpointId: String) {
        // TODO
        //val networkCallback = peerNetworkCallbacks[peerHandle]
        //connectivityManager.unregisterNetworkCallback(networkCallback)
    }

    override fun broadcastMessage(message: String) {
        /*
        for (endpoint in _endpoints) {
            sendMessage(endpoint, message)
        }
         */

        Log.d(TAG, "send to " + connectedThreads.size + " connected threads")

        for (thread in connectedThreads.values) {
            Thread(Runnable {
                thread.write(message.toByteArray())
            }).start()
        }
    }

    override fun sendMessage(endpoint: Endpoint, message: String) {
        val thread = connectedThreads[endpoint.endpointId]
        if (thread != null) {
            thread.write(message.toByteArray())
        } else {
            Log.e(TAG, "no connected thread for " + endpoint.endpointId)
        }

        val peerHandle = peerHandles[endpoint.endpointId]
        if (peerHandle != null) {
            // TODO: remove?
            val messageId = Random.nextInt()
            subscribeDiscoverySession?.sendMessage(peerHandle, messageId, message.toByteArray())
            publishDiscoverySession?.sendMessage(peerHandle, messageId, message.toByteArray())

            /*
            val socket = peerSockets[peerHandle]
            if (socket != null) {
                try {
                    socket.getOutputStream().write(message.toByteArray())
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            } else {
                Log.e(TAG, "socket for peer $endpoint is null!")
            }
             */
        }
    }

    override fun stopDiscovery() {
        subscribeDiscoverySession?.close()
        discoveryStatus.value = ConnectivityStatus.INACTIVE
    }

    override fun stop() {
        wifiAwareSession?.close()
    }


    private fun manageMyConnectedSocket(socket: Socket) {
        Log.d(TAG, "got a socket!")

        /*
        addEndpoint(
            Endpoint(
                endpoint.endpointId,
                endpoint.endpointName,
                EndpointState.CONNECTED
            )
        )
         */

        val connectedThread = ConnectedThread(socket)
        connectedThreads[socket.inetAddress.toString()] = connectedThread
        connectedThread.start()
    }

    companion object : SingletonHolder<WifiAwareConnectivityManager, Context>(::WifiAwareConnectivityManager) {
        private const val SERVICE_NAME = "meshnet"
        private const val TAG = "WifiAwareManager"
    }

    inner class AcceptThread(private val mmServerSocket: ServerSocket) : Thread() {
        var shouldLoop = true

        override fun run() {
            // Keep listening until exception occurs or a socket is returned.
            while (shouldLoop) {
                val socket: Socket? = try {
                    mmServerSocket.accept()
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
                mmServerSocket.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the connect socket", e)
            }
        }
    }

    inner class ConnectedThread(private val mmSocket: Socket) : Thread() {

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
                val sender = mmSocket.inetAddress.toString()
                addMessage(Message(text, Date(), sender))
            }

            val endpointId = mmSocket.inetAddress.toString()
            updateEndpointState(endpointId, EndpointState.DISCOVERED)
        }

        // Call this from the main activity to send data to the remote device.
        fun write(bytes: ByteArray) {
            try {
                //Log.d(TAG, "sending bytes to " + mmSocket.remoteDevice.address)
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
