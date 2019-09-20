package nl.tudelft.meshnet.ui.messages

import com.mattskala.itemadapter.Item
import nl.tudelft.meshnet.connectivity.ConnectivityManager

class MessageItem(val message: ConnectivityManager.Message) : Item()
