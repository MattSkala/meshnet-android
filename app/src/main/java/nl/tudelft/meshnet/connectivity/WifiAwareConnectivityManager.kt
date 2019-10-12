package nl.tudelft.meshnet.connectivity

import android.content.Context
import android.content.pm.PackageManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.*
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import nl.tudelft.meshnet.util.SingletonHolder
import java.net.ServerSocket
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

    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "onAvailable")

            val peerAwareInfo = peerAwareInfo
            // TODO: call this only for the subscriber
            if (peerAwareInfo != null) {
                val peerIpv6 = peerAwareInfo.peerIpv6Addr
                val peerPort = peerAwareInfo.port
                val socket = network.socketFactory.createSocket(peerIpv6, peerPort)
                // TODO: store socket reference
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            Log.d(TAG, "onCapabilitiesChanged")
            peerAwareInfo = networkCapabilities.transportInfo as WifiAwareNetworkInfo
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "onLost")
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
                Log.d(TAG, "onMessageReceived")

                requestNetwork(publishDiscoverySession!!, peerHandle, 0)
            }
        }, null)

        val ss = ServerSocket(0)
        val port = ss.localPort
        // TODO: store server socket reference, accept incoming connections?
    }

    private fun requestNetwork(discoverySession: DiscoverySession, peerHandle: PeerHandle, port: Int? = null) {
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

        connectivityManager.requestNetwork(networkRequest, networkCallback);
    }

    override fun stopAdvertising() {
        publishDiscoverySession?.close()
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
        }, null)
    }

    override fun requestConnection(endpointId: String) {
        val peerHandle = peerHandles[endpointId]!!
        val messageId = Random.nextInt()
        val message = "CONNECTION_REQUEST"
        subscribeDiscoverySession?.sendMessage(peerHandle, messageId, message.toByteArray())

        // TODO: wait until the publisher requests the network?
        requestNetwork(subscribeDiscoverySession!!, peerHandle, 0)
    }

    override fun disconnectFromEndpoint(endpointId: String) {
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }

    override fun broadcastMessage(message: String) {
        for (endpoint in _endpoints) {
            sendMessage(endpoint, message)
        }
    }

    override fun sendMessage(endpoint: Endpoint, message: String) {
        val peerHandle = peerHandles[endpoint.endpointId]
        if (peerHandle != null) {
            val messageId = Random.nextInt()
            subscribeDiscoverySession?.sendMessage(peerHandle, messageId, message.toByteArray())
        }
    }

    override fun stopDiscovery() {
        subscribeDiscoverySession?.close()
        discoveryStatus.value = ConnectivityStatus.INACTIVE
    }

    override fun stop() {
        wifiAwareSession?.close()
    }


    companion object : SingletonHolder<WifiAwareConnectivityManager, Context>(::WifiAwareConnectivityManager) {
        private const val SERVICE_NAME = "meshnet"
        private const val TAG = "WifiAwareManager"
    }
}
