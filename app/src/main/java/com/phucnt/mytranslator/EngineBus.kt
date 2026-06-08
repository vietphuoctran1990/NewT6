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

    fun publish(newState: State) {
        state = newState
        listener?.invoke(newState)
    }

    fun update(transform: (State) -> State) = publish(transform(state))
}
