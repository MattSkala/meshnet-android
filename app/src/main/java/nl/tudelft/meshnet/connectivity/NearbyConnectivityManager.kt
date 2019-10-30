package nl.tudelft.meshnet.connectivity

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.util.*

class NearbyConnectivityManager(
    private val context: Context
) : ConnectivityManager(context) {
    private val connectionInfos = mutableMapOf<String, ConnectionInfo>()

    override fun isSupported(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }

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
                val body = payload.asBytes()
                if (body != null) {
                    handleMessageReceived(body)
                }
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

    override fun startAdvertising() {
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

    override fun stopAdvertising() {
        Nearby.getConnectionsClient(context)
            .stopAdvertising()
        advertisingStatus.value = ConnectivityStatus.INACTIVE
    }

    override fun startDiscovery() {
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

    override fun stopDiscovery() {
        Nearby.getConnectionsClient(context)
            .stopDiscovery()
        discoveryStatus.value = ConnectivityStatus.INACTIVE
    }

    override fun requestConnection(endpointId: String) {
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

    override fun disconnectFromEndpoint(endpointId: String) {
        Nearby.getConnectionsClient(context)
            .disconnectFromEndpoint(endpointId)
        updateEndpointState(endpointId, EndpointState.DISCOVERED)
    }

    override fun sendMessage(endpoint: Endpoint, message: String) {
        Nearby.getConnectionsClient(context).sendPayload(
            endpoint.endpointId,
            Payload.fromBytes(message.toByteArray())
        )
    }

    companion object : ConnectivityManagerFactory {
        private val STRATEGY = Strategy.P2P_CLUSTER
        private const val SERVICE_ID = "meshnet"
        private const val TAG = "ConnectivityManager"

        @SuppressLint("StaticFieldLeak")
        private var instance: NearbyConnectivityManager? = null

        override fun getInstance(context: Context): ConnectivityManager {
            val i = instance
            if (i!= null) {
                return i
            }

            val i2 = NearbyConnectivityManager(context.applicationContext)
            instance = i2
            return i2
        }
    }
}
