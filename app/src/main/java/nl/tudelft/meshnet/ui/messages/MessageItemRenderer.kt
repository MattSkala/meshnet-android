package nl.tudelft.meshnet.ui.messages

import android.view.View
import com.mattskala.itemadapter.ItemLayoutRenderer
import kotlinx.android.synthetic.main.item_message.view.*
import nl.tudelft.meshnet.R
import java.text.SimpleDateFormat

class MessageItemRenderer : ItemLayoutRenderer<MessageItem, View>(MessageItem::class.java) {
    val dateTimeFormat = SimpleDateFormat.getDateTimeInstance()

    override fun getLayoutResourceId(): Int {
        return R.layout.item_message
    }

    override fun bindView(item: MessageItem, view: View) = with(view) {
        txtTime.text = dateTimeFormat.format(item.message.timestamp)
        txtUsername.text = item.message.sender
        txtMessage.text = item.message.message
    }
}