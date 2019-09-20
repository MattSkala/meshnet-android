package nl.tudelft.meshnet.connectivity

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlin.random.Random

class ConnectivityManager(
    private val context: Context
) {
    val advertisingStatus = MutableLiveData<ConnectivityStatus>()
    val discoveryStatus = MutableLiveData<ConnectivityStatus>()

    private val _endpoints = mutableListOf<Endpoint>()
    val endpoints = MutableLiveData<List<Endpoint>>()

    private val username by lazy {
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString("username", "guest" + Random.nextInt(1000))!!
    }

    init {
        advertisingStatus.value = ConnectivityStatus.INACTIVE
        discoveryStatus.value = ConnectivityStatus.INACTIVE
        endpoints.value = _endpoints
    }

    private val connectionInfos = mutableMapOf<String, ConnectionInfo>()

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "onEndpointFound $endpointId $info")
            // An endpoint was found. We request a connection to it.

            if (findEndpoint(endpointId) == null) {
                addEndpoint(Endpoint(endpointId, info.endpointName, EndpointState.DISCOVERED))
            }
        }

        override fun onEndpointLost(endpointId: String) {
            // A previously discovered endpoint has gone away.
            Log.d(TAG, "onEndpointLost $endpointId")

            removeEndpoint(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            Log.d(TAG, "onPayloadReceived $endpointId $payload")

            if (payload.type == Payload.Type.BYTES) {
                val message = payload.asBytes()?.toString(Charsets.UTF_8)
                Log.d(TAG, "onPayloadReceived: $message")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            Log.d(TAG, "onPayloadTransferUpdate $endpointId ${update.payloadId} ${update.bytesTransferred}/${update.totalBytes} ${update.status}")
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.d(TAG, "onConnectionInitiated $endpointId")

            addEndpoint(Endpoint(endpointId, connectionInfo.endpointName, EndpointState.CONNECTING))

            // Automatically accept the connection on both sides.
            Nearby.getConnectionsClient(context)
                .acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            Log.d(TAG, "onConnectionResult $endpointId ${result.status.statusCode}")

            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    // We're connected! Can now start sending and receiving data.
                    updateEndpointState(endpointId, EndpointState.CONNECTED)

                    /*
                    Nearby.getConnectionsClient(context).sendPayload(endpointId,
                        Payload.fromBytes("Hello, $endpointId! This is ${getUserNickname()}.".toByteArray()))
                     */
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    // The connection was rejected by one or both sides.
                    updateEndpointState(endpointId, EndpointState.DISCOVERED)
                }
                ConnectionsStatusCodes.STATUS_ERROR -> {
                    // The connection broke before it was able to be accepted.
                    updateEndpointState(endpointId, EndpointState.DISCOVERED)
                }
                else -> {
                    // Unknown status code
                    updateEndpointState(endpointId, EndpointState.DISCOVERED)
                }
            }

            connectionInfos.remove(endpointId)
        }

        override fun onDisconnected(endpointId: String) {
            // We've been disconnected from this endpoint. No more data can be
            // sent or received.
            Log.d(TAG, "onDisconnect $endpointId")

            updateEndpointState(endpointId, EndpointState.DISCOVERED)
        }
    }

    fun startAdvertising() {
        advertisingStatus.value = ConnectivityStatus.PENDING
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        Nearby.getConnectionsClient(context)
            .startAdvertising(
                username,
                SERVICE_ID, connectionLifecycleCallback, advertisingOptions
            )
            .addOnSuccessListener {
                // We're advertising!
                Log.d(TAG, "startAdvertising success")
                advertisingStatus.value = ConnectivityStatus.ACTIVE
            }
            .addOnFailureListener { e ->
                // We were unable to start advertising.
                e.printStackTrace()
                advertisingStatus.value = ConnectivityStatus.INACTIVE
            }
    }

    fun stopAdvertising() {
        Nearby.getConnectionsClient(context)
            .stopAdvertising()
        advertisingStatus.value = ConnectivityStatus.INACTIVE
    }

    fun toggleAdvertising() {
        if (advertisingStatus.value == ConnectivityStatus.INACTIVE) {
            startAdvertising()
        } else {
            stopAdvertising()
        }
    }

    fun startDiscovery() {
        discoveryStatus.value = ConnectivityStatus.PENDING
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        Nearby.getConnectionsClient(context)
            .startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
            .addOnSuccessListener {
                // We're discovering!
                Log.d(TAG, "startDiscovery success")
                discoveryStatus.value = ConnectivityStatus.ACTIVE
            }
            .addOnFailureListener { e ->
                // We're unable to start discovering.
                e.printStackTrace()
                discoveryStatus.value = ConnectivityStatus.INACTIVE
            }
    }

    fun stopDiscovery() {
        Nearby.getConnectionsClient(context)
            .stopDiscovery()
        discoveryStatus.value = ConnectivityStatus.INACTIVE
    }

    fun toggleDiscovery() {
        if (discoveryStatus.value == ConnectivityStatus.INACTIVE) {
            startDiscovery()
        } else {
            stopDiscovery()
        }
    }

    fun requestConnection(endpointId: String) {
        updateEndpointState(endpointId, EndpointState.CONNECTING)
        Nearby.getConnectionsClient(context)
            .requestConnection(username, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener {
                // We successfully requested a connection. Now both sides
                // must accept before the connection is established.
                Log.d(TAG, "requestConnection success $endpointId")
            }
            .addOnFailureListener { e: Exception ->
                // Nearby Connections failed to request the connection.
                Log.d(TAG, "requestConnection failure $endpointId")
                e.printStackTrace()
                updateEndpointState(endpointId, EndpointState.DISCOVERED)
            }
    }

    fun disconnectFromEndpoint(endpointId: String) {
        Nearby.getConnectionsClient(context)
            .disconnectFromEndpoint(endpointId)
        updateEndpointState(endpointId, EndpointState.DISCOVERED)
    }

    private fun findEndpoint(endpointId: String): Endpoint? {
        return _endpoints.find { it.endpointId == endpointId }
    }
    private fun addEndpoint(endpoint: Endpoint) {
        val existing = findEndpoint(endpoint.endpointId)
        if (existing != null) {
            _endpoints.remove(existing)
        }
        _endpoints.add(endpoint)
        endpoints.value = _endpoints
        notifyEndpointsChanged()
    }

    private fun removeEndpoint(endpointId: String) {
        _endpoints.removeAll {
            it.endpointId == endpointId
        }
        notifyEndpointsChanged()
    }

    private fun updateEndpointState(endpointId: String, state: EndpointState) {
        val endpoint = findEndpoint(endpointId)
        if (endpoint != null) {
            endpoint.state = state
            addEndpoint(endpoint)
        } else {
            Log.e(TAG, "Endpoint $endpointId not found")
        }
    }

    private fun notifyEndpointsChanged() {
        endpoints.value = _endpoints
    }

    companion object {
        private val STRATEGY = Strategy.P2P_CLUSTER
        private const val SERVICE_ID = "meshnet"
        private const val TAG = "ConnectivityManager"

        @SuppressLint("StaticFieldLeak")
        private var instance: ConnectivityManager? = null

        fun getInstance(context: Context): ConnectivityManager {
            val i = instance
            if (i!= null) {
                return i
            }

            val i2 = ConnectivityManager(context.applicationContext)
            instance = i2
            return i2
        }
    }

    enum class ConnectivityStatus {
        INACTIVE,
        PENDING,
        ACTIVE
    }

    enum class EndpointState {
        DISCOVERED,
        CONNECTING,
        CONNECTED
    }

    data class Endpoint(
        val endpointId: String,
        val endpointName: String,
        var state: EndpointState
    )
}