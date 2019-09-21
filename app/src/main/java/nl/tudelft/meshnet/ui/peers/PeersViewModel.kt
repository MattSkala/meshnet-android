package nl.tudelft.meshnet.ui.peers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.map
import androidx.preference.PreferenceManager
import nl.tudelft.meshnet.connectivity.BluetoothConnectivityManager
import nl.tudelft.meshnet.connectivity.ConnectivityManager
import nl.tudelft.meshnet.connectivity.ConnectivityManagerFactory
import nl.tudelft.meshnet.connectivity.NearbyConnectivityManager
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
        endpoints.map { PeerItem(it) }
    }

    fun toggleAdvertising() {
        connectivityManager.toggleAdvertising()
    }

    fun toggleDiscovery() {
        connectivityManager.toggleDiscovery()
    }

    fun connect(endpoint: NearbyConnectivityManager.Endpoint) {
        connectivityManager.requestConnection(endpoint.endpointId)
    }

    fun disconnect(endpoint: NearbyConnectivityManager.Endpoint) {
        connectivityManager.disconnectFromEndpoint(endpoint.endpointId)
    }

    fun isBluetooth(): Boolean {
        return connectivityManager is BluetoothConnectivityManager
    }
}