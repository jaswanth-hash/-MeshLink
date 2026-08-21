package com.meshlink.simulation

/** Result of [MeshSimulator.simulateRandomFailure] — uses existing disable/disconnect only. */
sealed class FailureEvent {
    data class NodeDisabled(val nodeId: String) : FailureEvent()
    data class LinkRemoved(val link: SimLink) : FailureEvent()
    data object None : FailureEvent()
}
