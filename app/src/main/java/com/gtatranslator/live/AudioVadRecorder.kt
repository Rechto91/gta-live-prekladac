package com.gtatranslator.live

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Native AudioRecord + Voice Activity Detection (VAD).
 * Captures spoken English segments from TV audio, wraps them into 16kHz mono WAV,
 * and delivers them directly to Gemini so AI hears the actual speaker voice (male/female).
 */
class AudioVadRecorder(
    private val onLevelChanged: (percent: Int, isCapturing: Boolean) -> Unit,
    private val onSpeechSegmentReady: (wavBytes: ByteArray) -> Unit,
    private val onError: (message: String) -> Unit
) {
    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val FRAME_SAMPLES = 1600
        private const val PRE_ROLL_FRAMES = 3
        private const val SILENCE_FRAMES_END = 7
        private const val MIN_SPEECH_FRAMES = 4
        private const val MAX_SPEECH_FRAMES = 65
    }

    @Volatile
    var thresholdPercent: Int = 18

    @Volatile
    private var isRunning = false
    private var workerThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            onError("Mikrofón nepodporuje 16kHz PCM záznam.")
            return
        }

        val bufferSize = maxOf(minBuf * 2, FRAME_SAMPLES * 4)
        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            onError("Nepodarilo sa spustiť mikrofón: ${e.message}")
            return
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            onError("Mikrofón sa nepodarilo inicializovať.")
            return
        }

        isRunning = true
        workerThread = Thread {
            val shortBuf = ShortArray(FRAME_SAMPLES)
            val preRoll = ArrayDeque<ByteArray>()
            val speechPcm = ByteArrayOutputStream()
            var speaking = false
            var speechFrames = 0
            var silenceFrames = 0

            try {
                audioRecord.startRecording()
                while (isRunning) {
                    val read = audioRecord.read(shortBuf, 0, FRAME_SAMPLES)
                    if (read <= 0) continue

                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val s = shortBuf[i].toDouble()
                        sumSq += s * s
                    }
                    val rms = sqrt(sumSq / read)
                    val levelPct = min(100, ((rms / 3500.0) * 100.0).toInt())

                    val frameBytes = shortsToBytes(shortBuf, read)
                    val activeThreshold = thresholdPercent

                    if (levelPct >= activeThreshold) {
                        if (!speaking) {
                            speaking = true
                            speechPcm.reset()
                            speechFrames = 0
                            silenceFrames = 0
                            for (pre in preRoll) {
                                speechPcm.write(pre)
                            }
                            preRoll.clear()
                        }
                        speechPcm.write(frameBytes)
                        speechFrames++
                        silenceFrames = 0
                    } else {
                        if (speaking) {
                            speechPcm.write(frameBytes)
                            silenceFrames++
                            if (silenceFrames >= SILENCE_FRAMES_END) {
                                if (speechFrames >= MIN_SPEECH_FRAMES) {
                                    emitWav(speechPcm.toByteArray())
                                }
                                speaking = false
                                speechPcm.reset()
                                speechFrames = 0
                                silenceFrames = 0
                            }
                        } else {
                            if (preRoll.size >= PRE_ROLL_FRAMES) {
                                preRoll.removeFirst()
                            }
                            preRoll.addLast(frameBytes)
                        }
                    }

                    if (speaking && speechFrames >= MAX_SPEECH_FRAMES) {
                        emitWav(speechPcm.toByteArray())
                        speaking = false
                        speechPcm.reset()
                        speechFrames = 0
                        silenceFrames = 0
                    }

                    onLevelChanged(levelPct, speaking)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    onError("Chyba nahrávania zvuku: ${e.message}")
                }
            } finally {
                try {
                    if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop()
                    }
                } catch (_: Exception) {
                }
                audioRecord.release()
                onLevelChanged(0, false)
            }
        }.apply {
            name = "ViceAudioVadThread"
            start()
        }
    }

    fun stop() {
        isRunning = false
        workerThread?.interrupt()
        workerThread = null
    }

    private fun emitWav(pcmData: ByteArray) {
        onSpeechSegmentReady(buildWav(pcmData))
    }

    private fun shortsToBytes(samples: ShortArray, count: Int): ByteArray {
        val bb = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            bb.putShort(samples[i])
        }
        return bb.array()
    }

    private fun buildWav(pcmData: ByteArray): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8)
        val totalDataLen = pcmData.size + 36
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(CHANNELS.toShort())
        header.putInt(SAMPLE_RATE)
        header.putInt(byteRate)
        header.putShort((CHANNELS * (BITS_PER_SAMPLE / 8)).toShort())
        header.putShort(BITS_PER_SAMPLE.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmData.size)

        val out = ByteArray(44 + pcmData.size)
        System.arraycopy(header.array(), 0, out, 0, 44)
        System.arraycopy(pcmData, 0, out, 44, pcmData.size)
        return out
    }
}
