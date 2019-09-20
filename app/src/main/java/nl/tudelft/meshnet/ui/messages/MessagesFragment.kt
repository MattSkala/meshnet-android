package nl.tudelft.meshnet.ui.messages

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.LinearLayoutManager
import com.mattskala.itemadapter.ItemAdapter
import kotlinx.android.synthetic.main.fragment_messages.*
import nl.tudelft.meshnet.R
import nl.tudelft.meshnet.ui.peers.PeersViewModel

class MessagesFragment : Fragment() {
    private val viewModel by lazy {
        ViewModelProviders.of(this)[MessagesViewModel::class.java]
    }

    private val adapter = ItemAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        adapter.registerRenderer(MessageItemRenderer())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_messages, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        btnSend.setOnClickListener {
            val message = edtMessage.text.toString()
            if (message.isNotBlank()) {
                viewModel.sendMessage(message)
                edtMessage.text = null
            }
        }

        viewModel.messages.observe(this, Observer {
            adapter.updateItems(it)
        })
    }
}