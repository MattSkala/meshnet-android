package nl.tudelft.meshnet.ui.peers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.map
import nl.tudelft.meshnet.connectivity.ConnectivityManager
import nl.tudelft.meshnet.extensions.combineLatest

class PeersViewModel(
    private val app: Application
) : AndroidViewModel(app) {
    private val connectivityManager = ConnectivityManager.getInstance(app)

    val advertisingStatus = connectivityManager.advertisingStatus
    val discoveryStatus = connectivityManager.discoveryStatus

    val endpoints = connectivityManager.endpoints.map { endpoints ->
        endpoints.map { PeerItem(it) }
    }

    fun toggleAdvertising() {
        connectivityManager.toggleAdvertising()
    }

    fun toggleDiscovery() {
        connectivityManager.toggleDiscovery()
    }

    fun connect(endpoint: ConnectivityManager.Endpoint) {
        connectivityManager.requestConnection(endpoint.endpointId)
    }

    fun disconnect(endpoint: ConnectivityManager.Endpoint) {
        connectivityManager.disconnectFromEndpoint(endpoint.endpointId)
    }
}