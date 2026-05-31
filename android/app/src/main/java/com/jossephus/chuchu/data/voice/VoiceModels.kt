package com.jossephus.chuchu.data.voice

object VoiceModels {
    const val SYSTEM_ID = "system"
    const val PARAKEET_V2_ID = "parakeet-tdt-v2"

    val parakeetV2: VoiceModel = VoiceModel(
        id = PARAKEET_V2_ID,
        displayName = "Parakeet TDT v2",
        languageLabel = "English",
        storageSizeLabel = "670 MB",
        downloadSizeBytes = 482_468_385L,
        sha256 = "157c157bc51155e03e37d2466522a3a737dd9c72bb25f36eb18912964161e1ad",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2",
        internalDir = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
    )
}
