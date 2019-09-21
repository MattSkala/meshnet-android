package nl.tudelft.meshnet.ui.peers

import android.view.View
import androidx.core.view.isVisible
import com.mattskala.itemadapter.ItemLayoutRenderer
import kotlinx.android.synthetic.main.item_peer.view.*
import nl.tudelft.meshnet.R
import nl.tudelft.meshnet.connectivity.NearbyConnectivityManager

class PeerItemRenderer(
    private val onConnectClick: (NearbyConnectivityManager.Endpoint) -> Unit,
    private val onDisconnectClick: (NearbyConnectivityManager.Endpoint) -> Unit
) : ItemLayoutRenderer<PeerItem, View>(PeerItem::class.java) {
    override fun getLayoutResourceId(): Int {
        return R.layout.item_peer
    }

    override fun bindView(item: PeerItem, view: View) = with(view) {
        txtEndpointId.text = item.endpoint.endpointId
        txtEndpointName.text = item.endpoint.endpointName
        btnConnect.isVisible = item.endpoint.state == NearbyConnectivityManager.EndpointState.DISCOVERED
        btnConnect.setOnClickListener {
            onConnectClick(item.endpoint)
        }
        btnDisconnect.isVisible = item.endpoint.state == NearbyConnectivityManager.EndpointState.CONNECTED
        btnDisconnect.setOnClickListener {
            onDisconnectClick(item.endpoint)
        }
        progress.isVisible = item.endpoint.state == NearbyConnectivityManager.EndpointState.CONNECTING
    }
}