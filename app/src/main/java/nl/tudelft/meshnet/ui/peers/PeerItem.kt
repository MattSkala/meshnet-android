package nl.tudelft.meshnet.ui.peers

import com.mattskala.itemadapter.Item
import nl.tudelft.meshnet.connectivity.ConnectivityManager

class PeerItem(val endpoint: ConnectivityManager.Endpoint) : Item()
