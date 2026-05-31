package com.jossephus.chuchu.ui.terminal.dictation

import android.content.Context
import com.jossephus.chuchu.data.voice.VoiceModels
import com.jossephus.chuchu.ui.terminal.DictationState
import com.jossephus.chuchu.ui.terminal.VoiceDictationController
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

class SystemTranscriberBackend(
    context: Context,
    onFinalText: (String) -> Unit,
    onError: (String) -> Unit,
) : TranscriberBackend {
    private val controller = VoiceDictationController(context, onFinalText, onError)

    override val id: String = VoiceModels.SYSTEM_ID
    override val state: StateFlow<DictationState> = controller.state

    override fun start(locale: Locale) {
        controller.start(locale)
    }

    override fun stop() {
        controller.stop()
    }

    override fun cancel() {
        controller.cancel()
    }

    override fun release() {
        controller.release()
    }
}
