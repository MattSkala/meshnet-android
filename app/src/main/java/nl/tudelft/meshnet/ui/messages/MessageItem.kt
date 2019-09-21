package nl.tudelft.meshnet.ui.messages

import com.mattskala.itemadapter.Item
import nl.tudelft.meshnet.connectivity.NearbyConnectivityManager

class MessageItem(val message: NearbyConnectivityManager.Message) : Item()
