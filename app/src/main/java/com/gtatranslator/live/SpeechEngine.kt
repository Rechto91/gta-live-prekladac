package com.gtatranslator.live

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Dedicated Slovak / Czech Text-To-Speech engine.
 * Picks the highest-quality local neural voice on Android and adjusts voice/pitch
 * automatically for Male (Jason) vs Female (Lucia) lines.
 */
class SpeechEngine(
    context: Context,
    private val onReady: (voiceCount: Int) -> Unit,
    private val onSpeakingChanged: (isSpeaking: Boolean) -> Unit
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isReady = false
    private var targetLang = "sk"
    private var speechRate = 1.05f
    private var bestMaleVoice: Voice? = null
    private var bestFemaleVoice: Voice? = null
    private var defaultVoice: Voice? = null

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            onReady(0)
            return
        }
        isReady = true
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                onSpeakingChanged(true)
            }

            override fun onDone(utteranceId: String?) {
                onSpeakingChanged(false)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onSpeakingChanged(false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                onSpeakingChanged(false)
            }
        })
        configureVoices()
    }

    fun setTargetLanguage(langCode: String) {
        targetLang = langCode
        if (isReady) {
            configureVoices()
        }
    }

    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.7f, 1.5f)
    }

    private fun configureVoices() {
        val locale = if (targetLang == "cs") Locale("cs", "CZ") else Locale("sk", "SK")
        tts?.language = locale

        val matchingVoices = try {
            tts?.voices
                ?.filter { it.locale.language.equals(locale.language, ignoreCase = true) }
                ?.sortedByDescending { it.quality }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        defaultVoice = matchingVoices.firstOrNull()
        bestFemaleVoice = matchingVoices.firstOrNull { v ->
            val n = v.name.lowercase(Locale.ROOT)
            n.contains("female") || n.contains("skf") || n.contains("sfk") || n.contains("csf")
        } ?: defaultVoice
        bestMaleVoice = matchingVoices.firstOrNull { v ->
            val n = v.name.lowercase(Locale.ROOT)
            n.contains("male") && !n.contains("female")
        } ?: matchingVoices.lastOrNull() ?: defaultVoice

        onReady(matchingVoices.size)
    }

    fun speak(text: String, gender: SpeakerGender, flushQueue: Boolean = false) {
        if (!isReady || text.isBlank()) return
        val engine = tts ?: return

        val chosenVoice = when (gender) {
            SpeakerGender.MALE -> bestMaleVoice
            SpeakerGender.FEMALE -> bestFemaleVoice
            SpeakerGender.NEUTRAL -> defaultVoice
        }
        if (chosenVoice != null) {
            try {
                engine.voice = chosenVoice
            } catch (_: Exception) {
            }
        }

        val sameVoiceForBoth = bestMaleVoice != null && bestMaleVoice == bestFemaleVoice
        val pitch = when (gender) {
            SpeakerGender.MALE -> if (sameVoiceForBoth) 0.82f else 0.94f
            SpeakerGender.FEMALE -> if (sameVoiceForBoth) 1.15f else 1.04f
            SpeakerGender.NEUTRAL -> 1.0f
        }

        engine.setPitch(pitch)
        engine.setSpeechRate(speechRate)
        val queueMode = if (flushQueue) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine.speak(text, queueMode, null, "vice_tts_${System.currentTimeMillis()}")
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        onSpeakingChanged(false)
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        isReady = false
    }
}
