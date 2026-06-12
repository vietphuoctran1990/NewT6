package com.phucnt.mytranslator

/** Lightweight in-process channel for the service to push UI state to the Activity. */
object EngineBus {
    data class State(
        val running: Boolean = false,
        val status: String = "idle",
        val source: String = "",
        val translation: String = "",
        val provisionalSource: String = "",
        val provisionalTranslation: String = "",
        val error: String? = null,
    )

    @Volatile
    var state = State()
        private set

    @Volatile
    var listener: ((State) -> Unit)? = null

    // Synchronized: updates arrive from WebSocket, capture, and main threads;
    // an unguarded read-modify-write would drop concurrent changes.
    @Synchronized
    fun publish(newState: State) {
        state = newState
        listener?.invoke(newState)
    }

    @Synchronized
    fun update(transform: (State) -> State) {
        state = transform(state)
        listener?.invoke(state)
    }
}
