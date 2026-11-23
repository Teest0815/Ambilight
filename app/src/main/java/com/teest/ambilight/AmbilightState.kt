package com.teest.ambilight

import kotlinx.coroutines.flow.MutableStateFlow

/** Small bridge between the capture service and the UI (same process). */
object AmbilightState {
    val running = MutableStateFlow(false)
    val status = MutableStateFlow("Gestoppt")

    /** Current LED colours (ARGB) for the live preview, null when nothing is running. */
    val colors = MutableStateFlow<IntArray?>(null)
}
