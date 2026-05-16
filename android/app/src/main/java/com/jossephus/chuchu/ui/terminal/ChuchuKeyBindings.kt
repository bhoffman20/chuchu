package com.jossephus.chuchu.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class ChuchuHint(
    val key: String,
    val description: String,
)

class ChuchuKeyBindings(
    private val hints: List<ChuchuHint>,
    private val handlers: Map<Char, () -> Unit>,
) {
    var isPrefixActive: Boolean by mutableStateOf(false)
        private set

    fun togglePrefix() {
        isPrefixActive = !isPrefixActive
    }

    fun reset() {
        isPrefixActive = false
    }

    fun handleText(text: String): Boolean {
        if (!isPrefixActive || text.isBlank()) return false
        val key = text.first().lowercaseChar()
        handlers[key]?.invoke()
        isPrefixActive = false
        return true
    }

    fun hints(): List<ChuchuHint> = hints
}
