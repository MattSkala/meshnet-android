package nl.tudelft.meshnet.connectivity

import java.util.*

data class Message(
    val id: String,
    val message: String,
    val timestamp: Date,
    val sender: String
) {
    fun serialize(): String {
        val millis = timestamp.time
        return "$id|$millis|$sender|$message"
    }

    companion object {
        fun deserialize(bytes: ByteArray): Message? {
            val body = bytes.toString(Charsets.UTF_8)
            val parts = body.split("|")
            return if (parts.size >= 4) {
                Message(parts[0], parts[3], Date(parts[1].toLong()), parts[2])
            } else {
                null
            }
        }
    }
}