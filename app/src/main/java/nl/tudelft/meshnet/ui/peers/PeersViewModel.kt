package nl.tudelft.meshnet.ui.peers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.map
import nl.tudelft.meshnet.connectivity.*

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

    fun isBluetoothDiscoveryRequired(): Boolean {
        return connectivityManager is BluetoothConnectivityManager
    }

    fun isBluetoothRequired(): Boolean {
        return connectivityManager.isBluetoothRequired()
    }
}