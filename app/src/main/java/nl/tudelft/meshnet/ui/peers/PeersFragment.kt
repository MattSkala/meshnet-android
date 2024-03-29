package nl.tudelft.meshnet.ui.peers

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.LinearLayoutManager
import com.mattskala.itemadapter.ItemAdapter
import kotlinx.android.synthetic.main.fragment_peers.*
import nl.tudelft.meshnet.R
import nl.tudelft.meshnet.connectivity.ConnectivityStatus
import nl.tudelft.meshnet.connectivity.NearbyConnectivityManager

class PeersFragment : Fragment() {
    private val viewModel by lazy {
        ViewModelProviders.of(this)[PeersViewModel::class.java]
    }

    private val adapter = ItemAdapter()

    private val bluetoothAdapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        adapter.registerRenderer(PeerItemRenderer ({
            viewModel.connect(it)
        }, {
            viewModel.disconnect(it)
        }))
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_peers, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        btnAdvertising.setOnClickListener {
            if (!ensureBluetoothEnabled()) return@setOnClickListener

            if (viewModel.isBluetoothDiscoveryRequired() && viewModel.advertisingStatus.value == ConnectivityStatus.INACTIVE) {
                val discoverableIntent: Intent =
                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                        putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                    }
                activity?.startActivity(discoverableIntent)
            }

            viewModel.toggleAdvertising()
        }

        btnDiscovery.setOnClickListener {
            if (!ensureBluetoothEnabled()) return@setOnClickListener

            if (checkPermissions()) {
                viewModel.toggleDiscovery()
            }
        }

        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        viewModel.advertisingStatus.observe(this, Observer {
            if (it != null) {
                btnAdvertising.setText(when (it) {
                    ConnectivityStatus.INACTIVE -> R.string.start_advertising
                    ConnectivityStatus.PENDING -> R.string.starting
                    ConnectivityStatus.ACTIVE -> R.string.stop_advertising
                })
            }
        })

        viewModel.discoveryStatus.observe(this, Observer {
            if (it != null) {
                btnDiscovery.setText(when (it) {
                    ConnectivityStatus.INACTIVE -> R.string.start_discovery
                    ConnectivityStatus.PENDING -> R.string.starting
                    ConnectivityStatus.ACTIVE -> R.string.stop_discovery
                })
            }
        })

        viewModel.endpoints.observe(this, Observer {
            adapter.updateItems(it)
        })
    }

    private fun checkPermissions(): Boolean {
        return if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            true
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_CODE_PERMISSIONS
            )
            false
        }
    }

    private fun ensureBluetoothEnabled(): Boolean {
        if (viewModel.isBluetoothRequired()) {
            // Ensures Bluetooth is available on the device and it is enabled. If not,
            // displays a dialog requesting user permission to enable Bluetooth.
            if (!bluetoothAdapter.isEnabled) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableBtIntent, REQUEST_CODE_ENABLE_BT)
            }
        }
        return bluetoothAdapter.isEnabled
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val REQUEST_CODE_ENABLE_BT = 20
    }
}