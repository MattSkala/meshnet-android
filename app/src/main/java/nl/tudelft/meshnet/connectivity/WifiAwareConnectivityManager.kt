package nl.tudelft.meshnet.connectivity

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.*
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import nl.tudelft.meshnet.util.SingletonHolder
import kotlin.random.Random

@RequiresApi(Build.VERSION_CODES.O)
class WifiAwareConnectivityManager(
    private val context: Context
) : ConnectivityManager(context) {

    private val wifiAwareManager: WifiAwareManager? by lazy {
        context.getSystemService(Context.WIFI_AWARE_SERVICE) as WifiAwareManager?
    }

    private var wifiAwareSession: WifiAwareSession? = null
    private var publishDiscoverySession: PublishDiscoverySession? = null
    private var subscribeDiscoverySession: SubscribeDiscoverySession? = null

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
            }
        }, null)
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
        // TODO: implement connection
        // https://developer.android.com/guide/topics/connectivity/wifi-aware#create_a_connection
    }

    override fun disconnectFromEndpoint(endpointId: String) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
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
