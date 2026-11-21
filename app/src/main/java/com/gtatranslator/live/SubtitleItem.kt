package com.gtatranslator.live

enum class SpeakerGender {
    MALE,
    FEMALE,
    NEUTRAL
}

data class SubtitleItem(
    val id: Long,
    val gender: SpeakerGender,
    val translatedText: String,
    val originalText: String,
    val timestamp: String,
    val sourceTag: String = ""
)
