package ai.myraa.assistant

import kotlinx.coroutines.flow.MutableStateFlow

object AppState {
    val status = MutableStateFlow("Ready")
    val emotion = MutableStateFlow("calm")
    val running = MutableStateFlow(false)
    val muted = MutableStateFlow(false)
    val level = MutableStateFlow(0f)
    val log = MutableStateFlow<List<String>>(emptyList())
    fun add(line: String) { log.value = (log.value + line).takeLast(40) }
}
