package nl.tudelft.meshnet.ui.messages

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.map
import nl.tudelft.meshnet.connectivity.ConnectivityManager
import nl.tudelft.meshnet.connectivity.NearbyConnectivityManager

class MessagesViewModel(
    app: Application
) : AndroidViewModel(app) {
    private val connectivityManager = ConnectivityManager.getInstance(app)

    val messages = connectivityManager.messages.map { messages ->
        messages.map { MessageItem(it) }
    }

    fun sendMessage(message: String) {
        connectivityManager.sendMessage(message)
    }
}