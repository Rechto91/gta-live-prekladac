package com.gtatranslator.live

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class TranslationResult(
    val gender: SpeakerGender,
    val originalEnglish: String,
    val translatedText: String,
    val isEmptySpeech: Boolean = false
)

class GeminiClient {

    suspend fun translateAudioWav(
        apiKey: String,
        model: String,
        targetLang: String,
        wavBytes: ByteArray,
        recentContext: List<String>
    ): Result<TranslationResult> = withContext(Dispatchers.IO) {
        try {
            val langName = if (targetLang == "cs") "češtiny" else "slovenčiny"
            val contextBlock = if (recentContext.isNotEmpty()) {
                "Predchádzajúce repliky pre kontext:\n" + recentContext.joinToString("\n")
            } else {
                ""
            }

            val instruction = buildString {
                append("Si okamžitý prekladač herných dialógov (napr. GTA VI) z angličtiny do hovorovej $langName. ")
                append("Vypočuj si priložený zvuk z televízora. Ignoruj hudbu, hluk motora, streľbu a nezrozumiteľný šum. ")
                append("Ak v zvuku nie je žiadna zrozumiteľná anglická reč, odpovedz presne:\n")
                append("SPEAKER: NONE\nEN: \nTR: \n\n")
                append("Ak niekto rozpráva po anglicky, rozpoznaj podľa hlasu či hovorí muž (M), žena (Z) alebo neisté/viac (U). ")
                append("Prelož reč prirodzene a hovorovo (vrátane slangu) a použij SPRÁVNY gramatický rod podľa pohlavia hovoriaceho (napr. 'išiel som' pre muža, 'išla som' pre ženu). ")
                append("Odpovedaj VÝHRADNE v tomto 3-riadkovom formáte bez markdownu:\n")
                append("SPEAKER: M alebo Z alebo U\n")
                append("EN: <krátky anglický prepis>\n")
                append("TR: <prirodzený preklad do $langName>")
                if (contextBlock.isNotBlank()) {
                    append("\n\n$contextBlock")
                }
            }

            val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

            val parts = JSONArray().apply {
                put(JSONObject().put("text", "Prelož anglický dialóg z tohto audio úryvku."))
                put(
                    JSONObject().put(
                        "inlineData",
                        JSONObject()
                            .put("mimeType", "audio/wav")
                            .put("data", base64Audio)
                    )
                )
            }

            val body = JSONObject().apply {
                put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", instruction))
                    )
                )
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", parts)
                    )
                )
            }

            val rawResponse = postGenerateContent(apiKey, model, body)
            Result.success(parseStructuredResponse(rawResponse, fallbackOriginal = ""))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun translateText(
        apiKey: String,
        model: String,
        targetLang: String,
        englishText: String,
        recentContext: List<String>
    ): Result<TranslationResult> = withContext(Dispatchers.IO) {
        try {
            val langName = if (targetLang == "cs") "češtiny" else "slovenčiny"
            val contextBlock = if (recentContext.isNotEmpty()) {
                "Predchádzajúce repliky pre kontext:\n" + recentContext.joinToString("\n")
            } else {
                ""
            }

            val instruction = buildString {
                append("Si okamžitý prekladač herných dialógov (napr. GTA VI) z angličtiny do hovorovej $langName. ")
                append("Prelož nasledujúcu anglickú vetu prirodzene vrátane slangu. ")
                append("Podľa kontextu odhadni pohlavie hovoriaceho (M, Z alebo U). ")
                append("Odpovedaj VÝHRADNE v tomto 3-riadkovom formáte bez markdownu:\n")
                append("SPEAKER: M alebo Z alebo U\n")
                append("EN: <originálny anglický text>\n")
                append("TR: <prirodzený preklad do $langName>")
                if (contextBlock.isNotBlank()) {
                    append("\n\n$contextBlock")
                }
            }

            val body = JSONObject().apply {
                put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", instruction))
                    )
                )
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "parts",
                                JSONArray().put(JSONObject().put("text", englishText))
                            )
                    )
                )
            }

            val rawResponse = postGenerateContent(apiKey, model, body)
            Result.success(parseStructuredResponse(rawResponse, fallbackOriginal = englishText))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun postGenerateContent(apiKey: String, model: String, payload: JSONObject): String {
        val cleanModel = model.trim().ifBlank { "gemini-3.5-flash-lite" }
        val endpoint =
            "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=${apiKey.trim()}"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(payload.toString())
            writer.flush()
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val responseBody = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        conn.disconnect()

        if (code !in 200..299) {
            val apiMsg = try {
                JSONObject(responseBody).optJSONObject("error")?.optString("message")
            } catch (_: Exception) {
                null
            }
            throw IllegalStateException("Gemini API chyba ($code): ${apiMsg ?: responseBody.take(160)}")
        }

        val root = JSONObject(responseBody)
        val candidates = root.optJSONArray("candidates")
            ?: throw IllegalStateException("Prázdna odpoveď z Gemini API.")
        if (candidates.length() == 0) {
            return "SPEAKER: NONE\nEN: \nTR: "
        }

        val content = candidates.getJSONObject(0).optJSONObject("content")
        val parts = content?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (!part.optBoolean("thought", false)) {
                    val t = part.optString("text", "")
                    if (t.isNotBlank()) sb.append(t)
                }
            }
        }
        return sb.toString().trim()
    }

    private fun parseStructuredResponse(raw: String, fallbackOriginal: String): TranslationResult {
        var speakerCode = "U"
        var enText = fallbackOriginal
        var trText = ""

        val lines = raw.lines()
        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("SPEAKER:", ignoreCase = true) -> {
                    speakerCode = trimmed.substringAfter(":").trim().uppercase()
                }
                trimmed.startsWith("EN:", ignoreCase = true) -> {
                    enText = trimmed.substringAfter(":").trim()
                }
                trimmed.startsWith("TR:", ignoreCase = true) -> {
                    trText = trimmed.substringAfter(":").trim()
                }
            }
        }

        if (speakerCode.startsWith("NONE") || (trText.isBlank() && raw.contains("NONE", ignoreCase = true))) {
            return TranslationResult(SpeakerGender.NEUTRAL, "", "", isEmptySpeech = true)
        }

        if (trText.isBlank()) {
            trText = raw.replace("SPEAKER:", "").replace("TR:", "").trim()
        }

        val gender = when {
            speakerCode.startsWith("M") -> SpeakerGender.MALE
            speakerCode.startsWith("Z") || speakerCode.startsWith("Ž") || speakerCode.startsWith("F") -> SpeakerGender.FEMALE
            else -> SpeakerGender.NEUTRAL
        }

        return TranslationResult(
            gender = gender,
            originalEnglish = enText,
            translatedText = trText,
            isEmptySpeech = trText.isBlank()
        )
    }
}
