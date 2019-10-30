package nl.tudelft.meshnet.connectivity

import android.content.Context
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

abstract class ConnectivityManager(
    private val context: Context
) {
    protected val username by lazy {
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString("username", "guest" + Random.nextInt(1000))!!
    }

    val advertisingStatus = MutableLiveData<ConnectivityStatus>(ConnectivityStatus.INACTIVE)
    val discoveryStatus = MutableLiveData<ConnectivityStatus>(ConnectivityStatus.INACTIVE)

    protected val _endpoints = CopyOnWriteArrayList<Endpoint>()
    val endpoints = MutableLiveData<List<Endpoint>>(_endpoints)

    private val _messages = mutableListOf<Message>()
    val messages = MutableLiveData<List<Message>>(_messages)

    abstract fun isSupported(): Boolean
    abstract fun startDiscovery()
    abstract fun stopDiscovery()
    abstract fun startAdvertising()
    abstract fun stopAdvertising()
    abstract fun requestConnection(endpointId: String)
    abstract fun disconnectFromEndpoint(endpointId: String)

    /**
     * Stops everything. Should be called when the connectivity manager is not needed anymore.
     */
    open fun stop() {
    }

    open fun isBluetoothRequired(): Boolean = false

    fun toggleAdvertising() {
        if (advertisingStatus.value == ConnectivityStatus.INACTIVE) {
            startAdvertising()
        } else {
            stopAdvertising()
        }
    }

    fun toggleDiscovery() {
        if (discoveryStatus.value == ConnectivityStatus.INACTIVE) {
            startDiscovery()
        } else {
            stopDiscovery()
        }
    }


    /*
     * Endpoints
     */

    protected fun findEndpoint(endpointId: String): Endpoint? {
        return _endpoints.find { it.endpointId == endpointId }
    }

    protected fun addEndpoint(endpoint: Endpoint) {
        Log.d(
            TAG,
            "addEndpoint " + endpoint.endpointId + " " + endpoint.endpointName + " " + endpoint.state
        )
        val existing = findEndpoint(endpoint.endpointId)
        if (existing != null) {
            _endpoints.remove(existing)
        }
        _endpoints.add(endpoint)
        notifyEndpointsChanged()
    }

    protected fun removeEndpoint(endpointId: String) {
        Log.d(TAG, "removeEndpoint $endpointId")
        _endpoints.removeAll {
            it.endpointId == endpointId
        }
        notifyEndpointsChanged()
    }

    protected fun updateEndpointState(endpointId: String, state: EndpointState) {
        Log.d(TAG, "updateEndpoint $endpointId -> $state")

        val endpoint = findEndpoint(endpointId)
        if (endpoint != null) {
            endpoint.state = state
            addEndpoint(endpoint)
        } else {
            Log.e(TAG, "Endpoint $endpointId not found")
        }
    }

    protected fun notifyEndpointsChanged() {
        endpoints.postValue(_endpoints)
    }

    /*
     * Messages
     */

    /**
     * Broadcasts a message to all connected endpoints.
     */
    fun sendMessage(text: String) {
        Log.d(TAG, "sendMessage $text to ${_endpoints.size} endpoints")

        val id = UUID.randomUUID().toString()
        val message = Message(id, text, Date(), username)

        addMessage(message)

        broadcastMessage(message.serialize())
    }

    open fun broadcastMessage(message: String) {
        for (endpoint in _endpoints) {
            if (endpoint.state == EndpointState.CONNECTED) {
                sendMessage(endpoint, message)
            }
        }
    }

    abstract fun sendMessage(endpoint: Endpoint, message: String)

    protected fun handleMessageReceived(bytes: ByteArray) {
        val message = Message.deserialize(bytes)
        if (message != null) {
            addMessage(message)
        } else {
            Log.e(TAG, "Message deserialization failed")
        }
    }

    protected fun addMessage(message: Message) {
        val existingMessage = _messages.find { it.id == message.id }
        // Only accept message if not received yet
        if (existingMessage == null) {
            _messages.add(message)
            messages.postValue(_messages)
        }
    }

    companion object {
        private const val TAG = "ConnectivityManager"

        fun getInstance(context: Context): ConnectivityManager {
            val className = PreferenceManager.getDefaultSharedPreferences(context)
                .getString("connectivity", "NearbyConnectivityManager")!!

            return when (className) {
                "BluetoothConnectivityManager" -> BluetoothConnectivityManager.getInstance(context)
                "BleConnectivityManager" -> BleConnectivityManager.getInstance(context)
                "BleGattConnectivityManager" -> BleGattConnectivityManager.getInstance(context)
                "WifiAwareConnectivityManager" -> WifiAwareConnectivityManager.getInstance(context)
                "WifiDirectConnectivityManager" -> WifiDirectConnectivityManager.getInstance(context)
                else -> NearbyConnectivityManager.getInstance(context)
            }
        }
    }
}

interface ConnectivityManagerFactory {
    fun getInstance(context: Context): ConnectivityManager
}

enum class ConnectivityStatus {
    INACTIVE,
    PENDING,
    ACTIVE
}
