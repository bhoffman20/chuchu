package com.jossephus.chuchu.data.voice

data class VoiceModel(
    val id: String,
    val displayName: String,
    val languageLabel: String,
    val storageSizeLabel: String,
    val downloadSizeBytes: Long,
    val sha256: String,
    val url: String,
    val internalDir: String,
)
