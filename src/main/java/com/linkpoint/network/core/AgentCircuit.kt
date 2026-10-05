package com.linkpoint.network.core

import com.linkpoint.network.NetworkLogger
import com.linkpoint.network.events.EventBus
import com.linkpoint.network.events.ConnectionState
import com.linkpoint.network.events.ConnectionStateChangedEvent
import com.linkpoint.protocol.auth.AuthReply
import com.linkpoint.protocol.messages.CircuitDispatcher
import com.linkpoint.protocol.messages.ids.MessageIdRegistry
import com.linkpoint.protocol.messages.UDPConnectionFixed
import com.linkpoint.protocol.messages.MessageRouter
import com.linkpoint.protocol.scenery.SceneDataHandler
import com.linkpoint.render.RenderQueue
import com.linkpoint.render.SceneGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Agent Circuit with Circuit Establishment State Machine
 *
 * Following Lumiya's architecture: this circuit does NOT create its own UDP socket.
 * Instead, it shares the main app's UDPConnectionFixed, registering message handlers
 * for scene data (rendering) while the main connection handles communications.
 *
 * This follows Lumiya's SLAgentCircuit pattern where a single SLConnection (IO thread)
 * manages all circuits through a shared NIO Selector.
 *
 * Circuit establishment sequence:
 * 1. Register scene handlers on shared connection
 * 2. Circuit ready - scene data flows through shared socket
 * 3. Agent updates sent through shared socket
 */
class AgentCircuit(
    private val authReply: AuthReply,
    private val sharedConnection: UDPConnectionFixed,
    private val sceneGraph: SceneGraph? = null,
    private val renderQueue: RenderQueue? = null,
    private val scope: CoroutineScope = CoroutineScope(CircuitDispatcher.dispatcher + SupervisorJob())
) {

    companion object {
        private const val TAG = "AgentCircuit"
        private const val AGENT_UPDATE_INTERVAL_MS = 100L
    }

    private val _circuitState = MutableStateFlow(CircuitState.DISCONNECTED)
    val circuitState: StateFlow<CircuitState> = _circuitState.asStateFlow()

    private var isConnected: Boolean = false
    private var agentUpdateJob: Job? = null
    private var stateListener: CircuitStateListener? = null

    private val udpConnection: UDPConnectionFixed = sharedConnection
    private val lifecycleOwnerId = "AgentCircuit:${authReply.agentId}:${authReply.circuitCode}"

    private val messageRouter = udpConnection.getMessageRouter()
    private val sceneDataHandler = SceneDataHandler(sceneGraph, renderQueue)

    interface CircuitStateListener {
        fun onStateChanged(from: CircuitState, to: CircuitState)
        fun onCircuitReady()
        fun onCircuitError(reason: String)
    }

    init {
        scope.launch { initializeCircuit() }
    }

    fun setStateListener(listener: CircuitStateListener) {
        this.stateListener = listener
    }

    private fun transitionState(newState: CircuitState, reason: String = "") {
        val oldState = _circuitState.value
        if (oldState != newState) {
            NetworkLogger.log(NetworkLogger.Level.INFO, NetworkLogger.Category.UDP,
                "Circuit state: $oldState → $newState ($reason)")
            _circuitState.value = newState
            stateListener?.onStateChanged(oldState, newState)
            if (newState == CircuitState.CIRCUIT_READY) {
                stateListener?.onCircuitReady()
            }
        }
    }

    private suspend fun initializeCircuit() {
        NetworkLogger.log(NetworkLogger.Level.INFO, NetworkLogger.Category.UDP, "=== Initializing Agent Circuit ===")
        try {
            registerSceneDataHandlers()
            establishCircuit()
        } catch (e: Exception) {
            NetworkLogger.log(NetworkLogger.Level.ERROR, NetworkLogger.Category.UDP, "Failed: ${e.message}")
            transitionState(CircuitState.ERROR, "Initialization failed")
            stateListener?.onCircuitError("Initialization failed: ${e.message}")
            throw e
        }
    }

    private suspend fun establishCircuit() {
        NetworkLogger.log(NetworkLogger.Level.INFO, NetworkLogger.Category.UDP, "=== Starting Circuit Establishment ===")
        transitionState(CircuitState.CONNECTING, "Waiting for shared UDP handshake")

        // UDPConnectionFixed owns the real circuit handshake. AgentCircuit must
        // not invent ACKed/READY states merely because the shared socket exists:
        // UseCircuitCode -> PacketAck -> CompleteAgentMovement is the protocol
        // gate before any AgentUpdate traffic is allowed.
        val handshakeCompleted = withTimeoutOrNull(15_000L) {
            while (isActive && udpConnection.isConnected.value && !udpConnection.isCircuitHandshakeComplete()) {
                delay(50L)
            }
            udpConnection.isConnected.value && udpConnection.isCircuitHandshakeComplete()
        } ?: false

        if (!handshakeCompleted) {
            val reason = if (!udpConnection.isConnected.value) {
                "Shared UDP connection is no longer connected"
            } else {
                "Timed out waiting for UseCircuitCode ACK / CompleteAgentMovement"
            }
            transitionState(CircuitState.ERROR, reason)
            stateListener?.onCircuitError(reason)
            return
        }

        isConnected = true
        transitionState(CircuitState.USE_CIRCUIT_CODE_ACKED, "UseCircuitCode acknowledged by simulator")
        transitionState(CircuitState.COMPLETE_AGENT_MOVEMENT_ACKED, "CompleteAgentMovement sent by shared circuit")
        transitionState(CircuitState.CIRCUIT_READY, "Shared circuit handshake complete")

        // UDPConnectionFixed starts the AgentUpdate loop from the same
        // handshake boundary. Do not start a second loop here.
    }

    private suspend fun registerSceneDataHandlers() {
        messageRouter.registerHandler(MessageIdRegistry.LAYER_DATA, object : MessageRouter.Handler {
            override fun handleMessage(msgId: Int, data: ByteArray) = sceneDataHandler.handleLayerData(data)
            override fun getPriority() = 0
        })

        messageRouter.registerHandler(MessageIdRegistry.OBJECT_UPDATE, object : MessageRouter.Handler {
            override fun handleMessage(msgId: Int, data: ByteArray) = sceneDataHandler.handleObjectUpdate(data)
            override fun getPriority() = 0
        })

        messageRouter.registerHandler(MessageIdRegistry.OBJECT_PROPERTIES, object : MessageRouter.Handler {
            override fun handleMessage(msgId: Int, data: ByteArray) = sceneDataHandler.handleObjectProperties(data)
            override fun getPriority() = 0
        })

        NetworkLogger.log(NetworkLogger.Level.DEBUG, NetworkLogger.Category.UDP, "Scene handlers registered on shared connection")
    }

    // AgentUpdate ownership lives in UDPConnectionFixed. Keeping a second
    // scheduler here used to send AgentUpdate before UseCircuitCode was ACKed
    // and could also create duplicate movement loops.


    fun getStatistics() = mapOf(
        "state" to _circuitState.value.name,
        "connected" to isConnected,
        "usesSharedConnection" to true,
        "sceneStats" to sceneDataHandler.getStatistics()
    )

    fun isCircuitReady() = _circuitState.value == CircuitState.CIRCUIT_READY

    fun close() {
        isConnected = false
        agentUpdateJob?.cancel()
        udpConnection.releaseMovementLifecycle(lifecycleOwnerId)
        try {
            scope.launch {
                EventBus.publish(ConnectionStateChangedEvent(ConnectionState.CONNECTED, ConnectionState.DISCONNECTED))
            }
        } catch (e: Exception) {
            NetworkLogger.log(NetworkLogger.Level.ERROR, NetworkLogger.Category.UDP, "Close error: ${e.message}")
        }
        transitionState(CircuitState.DISCONNECTED, "Closed")
        scope.cancel()
    }

}
