package com.jossephus.chuchu.ui.terminal.dictation

import com.jossephus.chuchu.ui.terminal.DictationState
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

interface TranscriberBackend {
    val id: String
    val state: StateFlow<DictationState>

    fun start(locale: Locale)
    fun stop()
    fun cancel()
    fun release()
}
