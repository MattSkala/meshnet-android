package nl.tudelft.meshnet.ui.peers

import android.view.View
import androidx.core.view.isVisible
import com.mattskala.itemadapter.ItemLayoutRenderer
import kotlinx.android.synthetic.main.item_peer.view.*
import nl.tudelft.meshnet.R
import nl.tudelft.meshnet.connectivity.ConnectivityManager

class PeerItemRenderer(
    private val onConnectClick: (ConnectivityManager.Endpoint) -> Unit,
    private val onDisconnectClick: (ConnectivityManager.Endpoint) -> Unit
) : ItemLayoutRenderer<PeerItem, View>(PeerItem::class.java) {
    override fun getLayoutResourceId(): Int {
        return R.layout.item_peer
    }

    override fun bindView(item: PeerItem, view: View) = with(view) {
        txtEndpointId.text = item.endpoint.endpointId
        txtEndpointName.text = item.endpoint.endpointName
        btnConnect.isVisible = item.endpoint.state == ConnectivityManager.EndpointState.DISCOVERED
        btnConnect.setOnClickListener {
            onConnectClick(item.endpoint)
        }
        btnDisconnect.isVisible = item.endpoint.state == ConnectivityManager.EndpointState.CONNECTED
        btnDisconnect.setOnClickListener {
            onDisconnectClick(item.endpoint)
        }
        progress.isVisible = item.endpoint.state == ConnectivityManager.EndpointState.CONNECTING
    }
}