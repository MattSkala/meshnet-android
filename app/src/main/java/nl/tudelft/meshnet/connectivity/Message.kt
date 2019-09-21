package nl.tudelft.meshnet.connectivity

import java.util.*

data class Message(
    val message: String,
    val timestamp: Date,
    val sender: String
)