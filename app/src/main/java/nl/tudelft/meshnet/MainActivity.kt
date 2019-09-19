package nl.tudelft.meshnet

import android.Manifest
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.android.synthetic.main.activity_main.*
import kotlin.random.Random


class MainActivity : AppCompatActivity() {

    private var isAdvertising = false
    private var isDiscovering = false

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "onEndpointFound $endpointId $info")
            // An endpoint was found. We request a connection to it.
            Nearby.getConnectionsClient(applicationContext)
                .requestConnection(getUserNickname(), endpointId, connectionLifecycleCallback)
                .addOnSuccessListener {
                    // We successfully requested a connection. Now both sides
                    // must accept before the connection is established.
                    Log.d(TAG, "requestConnection success $endpointId")
                }
                .addOnFailureListener { e: Exception ->
                    // Nearby Connections failed to request the connection.
                    e.printStackTrace()
                }
        }

        override fun onEndpointLost(endpointId: String) {
            // A previously discovered endpoint has gone away.
            Log.d(TAG, "onEndpointLost $endpointId")
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

            // Automatically accept the connection on both sides.
            Nearby.getConnectionsClient(applicationContext).acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            Log.d(TAG, "onConnectionResult $endpointId ${result.status.statusCode}")

            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    // We're connected! Can now start sending and receiving data.
                    Nearby.getConnectionsClient(applicationContext).sendPayload(endpointId,
                        Payload.fromBytes("Hello, $endpointId! This is ${getUserNickname()}.".toByteArray()))
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    // The connection was rejected by one or both sides.
                }
                ConnectionsStatusCodes.STATUS_ERROR -> {
                    // The connection broke before it was able to be accepted.
                }
                else -> {
                    // Unknown status code
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            // We've been disconnected from this endpoint. No more data can be
            // sent or received.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    fun toggleAdvertising(view: View) {
        if (isAdvertising) {
            stopAdvertising()
        } else {
            startAdvertising()
        }
        isAdvertising = !isAdvertising
        btnAdvertising.setText(if (isAdvertising)
            R.string.stop_advertising else
            R.string.start_advertising)
    }

    fun toggleDiscovery(view: View) {
        if (isDiscovering) {
            stopDiscovery()
            isDiscovering = false
        } else {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                startDiscovery()
                isDiscovering = true
            } else {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    REQUEST_CODE_PERMISSIONS)
            }
        }

        btnDiscovery.setText(if (isDiscovering)
            R.string.stop_discovery else
            R.string.start_discovery)
    }

    private fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        Nearby.getConnectionsClient(this)
            .startAdvertising(
                getUserNickname(), SERVICE_ID, connectionLifecycleCallback, advertisingOptions
            )
            .addOnSuccessListener {
                // We're advertising!
                Log.d(TAG, "startAdvertising success")
            }
            .addOnFailureListener { e ->
                // We were unable to start advertising.
                e.printStackTrace()
            }
    }

    private fun stopAdvertising() {
        Nearby.getConnectionsClient(this)
            .stopAdvertising()
    }

    private fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        Nearby.getConnectionsClient(this)
            .startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
            .addOnSuccessListener {
                // We're discovering!
                Log.d(TAG, "startDiscovery success")
            }
            .addOnFailureListener { e ->
                // We're unable to start discovering.
                e.printStackTrace()
            }
    }

    private fun stopDiscovery() {
        Nearby.getConnectionsClient(this)
            .stopDiscovery()
    }

    private fun getUserNickname(): String {
        return "mesh" + Random.nextInt()
    }

    companion object {
        private val STRATEGY = Strategy.P2P_CLUSTER
        private const val SERVICE_ID = "meshnet"
        private const val TAG = "MeshNet"
        private const val REQUEST_CODE_PERMISSIONS = 10
    }
}
