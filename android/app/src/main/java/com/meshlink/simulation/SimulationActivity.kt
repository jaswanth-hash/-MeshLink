package com.meshlink.simulation

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.meshlink.R

/**
 * Dev/test screen for the offline mesh simulator.
 * Clearly separate from real Nearby Connections messaging.
 */
class SimulationActivity : AppCompatActivity() {

    private val simulator = MeshSimulator()

    private lateinit var graphView: SimulationGraphView
    private lateinit var routeText: TextView
    private lateinit var hopText: TextView
    private lateinit var deliveryStatusText: TextView
    private lateinit var liveStatusText: TextView
    private lateinit var logText: TextView
    private lateinit var sendButton: Button
    private lateinit var failButton: Button

    private val logLines = ArrayDeque<String>()
    private var lastPath: List<String> = emptyList()
    private var deliveryStatus: DeliveryStatus = DeliveryStatus.IDLE
    private var animating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simulation)

        graphView = findViewById(R.id.simGraph)
        routeText = findViewById(R.id.simRouteText)
        hopText = findViewById(R.id.simHopText)
        deliveryStatusText = findViewById(R.id.simDeliveryStatus)
        liveStatusText = findViewById(R.id.simLiveStatus)
        logText = findViewById(R.id.simLog)
        sendButton = findViewById(R.id.simSend)
        failButton = findViewById(R.id.simFail)

        findViewById<Button>(R.id.simLoadDefault).setOnClickListener {
            cancelAnimation()
            simulator.loadDefaultScenario()
            lastPath = emptyList()
            setDeliveryStatus(DeliveryStatus.IDLE)
            appendLog("SIM: loaded default topology A—B—C—D (+ B—D).")
            refreshUi()
        }
        findViewById<Button>(R.id.simReset).setOnClickListener {
            cancelAnimation()
            simulator.reset()
            lastPath = emptyList()
            setDeliveryStatus(DeliveryStatus.IDLE)
            appendLog("SIM: simulation reset.")
            refreshUi()
        }
        failButton.setOnClickListener { simulateFailureAndReroute() }

        findViewById<Button>(R.id.simAddNode).setOnClickListener {
            val id = textOf(R.id.simNodeInput)
            if (id.isEmpty()) {
                toast("Enter a node id (e.g. E)")
                return@setOnClickListener
            }
            runCatching { simulator.addNode(id) }
                .onSuccess {
                    appendLog("SIM: added node ${it.id}")
                    refreshUi()
                }
                .onFailure { toast(it.message ?: "Add failed") }
        }
        findViewById<Button>(R.id.simRemoveNode).setOnClickListener {
            val id = textOf(R.id.simNodeInput).ifEmpty { textOf(R.id.simDisableNode) }
            if (id.isEmpty()) {
                toast("Enter a node id to remove")
                return@setOnClickListener
            }
            if (!simulator.removeNode(id)) {
                toast("Unknown node")
                return@setOnClickListener
            }
            lastPath = emptyList()
            appendLog("SIM: removed node ${id.uppercase()}")
            refreshUi()
        }
        findViewById<Button>(R.id.simConnect).setOnClickListener {
            val a = textOf(R.id.simLinkA)
            val b = textOf(R.id.simLinkB)
            runCatching { simulator.connect(a, b) }
                .onSuccess {
                    appendLog("SIM: connected ${it.a}—${it.b}")
                    refreshUi()
                }
                .onFailure { toast(it.message ?: "Connect failed") }
        }
        findViewById<Button>(R.id.simDisconnect).setOnClickListener {
            val a = textOf(R.id.simLinkA)
            val b = textOf(R.id.simLinkB)
            val removed = simulator.disconnect(a, b)
            appendLog(
                if (removed) "SIM: disconnected $a—$b"
                else "SIM: no link $a—$b"
            )
            lastPath = emptyList()
            refreshUi()
        }
        findViewById<Button>(R.id.simDisable).setOnClickListener {
            val id = textOf(R.id.simDisableNode)
            if (!simulator.setNodeEnabled(id, false)) {
                toast("Unknown node")
                return@setOnClickListener
            }
            appendLog("SIM: disabled node ${id.uppercase()}")
            lastPath = emptyList()
            attemptAutoReroute(reason = "after manual disable")
        }
        findViewById<Button>(R.id.simEnable).setOnClickListener {
            val id = textOf(R.id.simDisableNode)
            if (!simulator.setNodeEnabled(id, true)) {
                toast("Unknown node")
                return@setOnClickListener
            }
            appendLog("SIM: enabled node ${id.uppercase()}")
            refreshUi()
        }
        sendButton.setOnClickListener { sendSimulatedMessage(autoReroute = false) }

        simulator.loadDefaultScenario()
        appendLog("SIM: ready (offline). Not Bluetooth / Nearby.")
        refreshUi()
    }

    override fun onDestroy() {
        cancelAnimation()
        super.onDestroy()
    }

    private fun sendSimulatedMessage(autoReroute: Boolean) {
        if (animating) return
        val from = textOf(R.id.simFrom)
        val to = textOf(R.id.simTo)
        val body = findViewById<EditText>(R.id.simMessageBody).text?.toString()
            .orEmpty()
            .ifBlank { "ping" }

        setDeliveryStatus(DeliveryStatus.SENT)
        appendLog("SIM: SENT $from → $to \"$body\"")

        val result = simulator.send(from, to, body)
        when (result) {
            is RoutingResult.Delivered -> {
                lastPath = result.path
                val route = result.path.joinToString(" → ")
                val hops = result.hopCount
                routeText.text = getString(R.string.sim_route_format, route, result.messageId)
                hopText.text = getString(R.string.sim_hops_format, hops)
                appendLog("SIM: ROUTING [${result.messageId}] $route ($hops hops)")
                setDeliveryStatus(DeliveryStatus.ROUTING)
                refreshUi(keepRouteInfo = true)
                animating = true
                setControlsEnabled(false)
                graphView.animatePacketAlongRoute {
                    animating = false
                    setControlsEnabled(true)
                    setDeliveryStatus(DeliveryStatus.DELIVERED)
                    appendLog("SIM: DELIVERED [${result.messageId}] via $route")
                    refreshUi(keepRouteInfo = true)
                }
            }
            is RoutingResult.Duplicate -> {
                setDeliveryStatus(DeliveryStatus.FAILED)
                hopText.text = getString(R.string.sim_hops_idle)
                appendLog("SIM: FAILED duplicate id [${result.messageId}]")
                toast("Duplicate message id blocked")
                refreshUi()
            }
            is RoutingResult.Failed -> {
                lastPath = emptyList()
                setDeliveryStatus(DeliveryStatus.FAILED)
                routeText.text = getString(R.string.sim_route_failed, result.reason)
                hopText.text = getString(R.string.sim_hops_idle)
                appendLog("SIM: FAILED [${result.messageId}] ${result.reason}")
                if (autoReroute) {
                    appendLog("SIM: no alternate route available")
                }
                refreshUi(keepRouteInfo = true)
            }
        }
    }

    private fun simulateFailureAndReroute() {
        if (animating) return
        val event = simulator.simulateRandomFailure()
        when (event) {
            is FailureEvent.NodeDisabled ->
                appendLog("SIM: FAILURE disabled node ${event.nodeId}")
            is FailureEvent.LinkRemoved ->
                appendLog("SIM: FAILURE removed link ${event.link.a}—${event.link.b}")
            FailureEvent.None -> {
                appendLog("SIM: FAILURE skipped (empty topology)")
                toast("Nothing to fail")
                return
            }
        }
        lastPath = emptyList()
        attemptAutoReroute(reason = "after simulated failure")
    }

    private fun attemptAutoReroute(reason: String) {
        val from = textOf(R.id.simFrom)
        val to = textOf(R.id.simTo)
        val preview = simulator.findShortestPath(from, to)
        refreshUi()
        if (preview == null) {
            setDeliveryStatus(DeliveryStatus.FAILED)
            routeText.text = getString(
                R.string.sim_route_failed,
                "No SIM route $from → $to $reason"
            )
            hopText.text = getString(R.string.sim_hops_idle)
            appendLog("SIM: REROUTE failed — no path $from → $to")
            return
        }
        appendLog("SIM: REROUTE found ${preview.joinToString(" → ")} $reason")
        sendSimulatedMessage(autoReroute = true)
    }

    private fun setDeliveryStatus(status: DeliveryStatus) {
        deliveryStatus = status
        deliveryStatusText.text = when (status) {
            DeliveryStatus.IDLE -> getString(R.string.sim_status_idle)
            DeliveryStatus.SENT -> getString(R.string.sim_status_sent)
            DeliveryStatus.ROUTING -> getString(R.string.sim_status_routing)
            DeliveryStatus.DELIVERED -> getString(R.string.sim_status_delivered)
            DeliveryStatus.FAILED -> getString(R.string.sim_status_failed)
        }
    }

    private fun cancelAnimation() {
        animating = false
        graphView.stopPacketAnimation()
        setControlsEnabled(true)
    }

    private fun setControlsEnabled(enabled: Boolean) {
        sendButton.isEnabled = enabled
        failButton.isEnabled = enabled
    }

    private fun refreshUi(keepRouteInfo: Boolean = false) {
        val nodes = simulator.snapshotNodes()
        val links = simulator.snapshotLinks()
        val connected = nodes
            .filter { simulator.isNodeConnected(it.id) }
            .map { it.id }
            .toSet()
        graphView.updateGraph(nodes, links, connected, lastPath)

        liveStatusText.text = buildString {
            append((simulator.nodeStatusLines() + simulator.linkStatusLines()).joinToString("\n"))
            if (isEmpty()) append(getString(R.string.sim_live_empty))
        }

        if (!keepRouteInfo && lastPath.isEmpty() && deliveryStatus == DeliveryStatus.IDLE) {
            routeText.text = getString(R.string.sim_route_idle)
            hopText.text = getString(R.string.sim_hops_idle)
        }
        logText.text = logLines.joinToString("\n")
    }

    private fun appendLog(line: String) {
        logLines.addFirst(line)
        while (logLines.size > 50) logLines.removeLast()
        logText.text = logLines.joinToString("\n")
    }

    private fun textOf(id: Int): String =
        findViewById<EditText>(id).text?.toString().orEmpty().trim()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
