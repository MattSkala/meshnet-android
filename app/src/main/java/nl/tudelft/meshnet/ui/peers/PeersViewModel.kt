package nl.tudelft.meshnet.ui.peers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.map
import androidx.preference.PreferenceManager
import nl.tudelft.meshnet.connectivity.*
import kotlin.random.Random

class PeersViewModel(
    private val app: Application
) : AndroidViewModel(app) {
    private val connectivityManager by lazy {
        ConnectivityManager.getInstance(app)
    }

    val advertisingStatus = connectivityManager.advertisingStatus
    val discoveryStatus = connectivityManager.discoveryStatus

    val endpoints = connectivityManager.endpoints.map { endpoints ->
        endpoints
            .sortedWith(compareBy (
                { it.state != EndpointState.CONNECTED },
                { it.state != EndpointState.CONNECTING }
            )).map { PeerItem(it) }
    }

    fun toggleAdvertising() {
        connectivityManager.toggleAdvertising()
    }

    fun toggleDiscovery() {
        connectivityManager.toggleDiscovery()
    }

    fun connect(endpoint: Endpoint) {
        connectivityManager.requestConnection(endpoint.endpointId)
    }

    fun disconnect(endpoint: Endpoint) {
        connectivityManager.disconnectFromEndpoint(endpoint.endpointId)
    }

    fun isBluetooth(): Boolean {
        return connectivityManager is BluetoothConnectivityManager
    }
}