package nl.tudelft.meshnet.ui.peers

import com.mattskala.itemadapter.Item
import nl.tudelft.meshnet.connectivity.NearbyConnectivityManager

class PeerItem(val endpoint: NearbyConnectivityManager.Endpoint) : Item()
