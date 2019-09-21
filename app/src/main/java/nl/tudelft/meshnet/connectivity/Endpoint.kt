package nl.tudelft.meshnet.connectivity

data class Endpoint(
    val endpointId: String,
    val endpointName: String?,
    var state: EndpointState
)

enum class EndpointState {
    DISCOVERED,
    CONNECTING,
    CONNECTED
}
