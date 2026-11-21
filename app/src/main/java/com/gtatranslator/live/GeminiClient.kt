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

    suspend fun translateText(
        apiKey: String,
        model: String,
        targetLang: String,
        englishText: String,
        genderHint: SpeakerGender,
        recentContext: List<String>
    ): Result<TranslationResult> = withContext(Dispatchers.IO) {
        try {
            val langName = if (targetLang == "cs") "češtiny" else "slovenčiny"
            val genderDirective = when (genderHint) {
                SpeakerGender.MALE -> "Hovoriaci je MUŽ (Jason) – použi mužský rod (napr. 'išiel som', 'videl som') a vráť SPEAKER: M."
                SpeakerGender.FEMALE -> "Hovoriaci je ŽENA (Lucia) – použi ženský rod (napr. 'išla som', 'videla som') a vráť SPEAKER: Z."
                SpeakerGender.NEUTRAL -> "Odhadni z kontextu či hovorí muž (M), žena (Z) alebo neutrálne (U) a použi prirodzený rod."
            }
            val contextBlock = if (recentContext.isNotEmpty()) {
                "Nedávne repliky:\n" + recentContext.joinToString("\n")
            } else {
                ""
            }

            val instruction = buildString {
                append("Si profesionálny dabingový prekladač pre hru GTA VI (Vice City / Leonida). ")
                append("Prelož vstupný text okamžite do živej, prirodzenej, hovorenej $langName (vrátane pouličného slangu), aby znel perfektne pri nahlas prečítaní. ")
                append("$genderDirective ")
                append("Odpovedaj VÝHRADNE v tomto 3-riadkovom formáte bez markdownu:\n")
                append("SPEAKER: M alebo Z alebo U\n")
                append("EN: <originálny text>\n")
                append("TR: <prirodzený preklad do $langName>")
                if (contextBlock.isNotBlank()) {
                    append("\n\n$contextBlock")
                }
            }

            val body = buildPayload(
                instruction = instruction,
                parts = JSONArray().put(JSONObject().put("text", englishText))
            )

            val rawResponse = postGenerateContent(apiKey, model, body)
            val parsed = parseStructuredResponse(rawResponse, fallbackOriginal = englishText)
            val finalGender = if (genderHint != SpeakerGender.NEUTRAL) genderHint else parsed.gender
            Result.success(parsed.copy(gender = finalGender))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun translateSubtitleImage(
        apiKey: String,
        model: String,
        targetLang: String,
        jpegBytes: ByteArray,
        recentContext: List<String>
    ): Result<TranslationResult> = withContext(Dispatchers.IO) {
        try {
            val langName = if (targetLang == "cs") "češtiny" else "slovenčiny"
            val contextBlock = if (recentContext.isNotEmpty()) {
                "Nedávne titulky:\n" + recentContext.joinToString("\n")
            } else {
                ""
            }

            val instruction = buildString {
                append("Si okamžitý prekladač herných titulkov z obrazovky televízora (GTA VI) do hovorenej $langName. ")
                append("Prečítaj anglické titulky na obrázku. Ignoruj mapu, ikony, ovládacie tlačidlá a pozadie hry. ")
                append("Ak na obrázku nie je žiadny čitateľný herný titulok alebo dialóg, odpovedz presne:\n")
                append("SPEAKER: NONE\nEN: \nTR: \n\n")
                append("Ak titulok nájdeš, prelož ho prirodzene a hovorovo do $langName (vrátane slangu), pripravený na prečítanie hlasom. ")
                append("Ak je z kontextu jasné pohlavie postavy (napr. Jason = M, Lucia = Z), uveď M alebo Z, inak U. ")
                append("Odpovedaj VÝHRADNE v tomto 3-riadkovom formáte bez markdownu:\n")
                append("SPEAKER: M alebo Z alebo U\n")
                append("EN: <prečítaný anglický titulok>\n")
                append("TR: <prirodzený preklad do $langName>")
                if (contextBlock.isNotBlank()) {
                    append("\n\n$contextBlock")
                }
            }

            val base64Img = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val parts = JSONArray().apply {
                put(JSONObject().put("text", "Prečítaj a prelož anglické titulky z tohto záberu obrazovky."))
                put(
                    JSONObject().put(
                        "inlineData",
                        JSONObject()
                            .put("mimeType", "image/jpeg")
                            .put("data", base64Img)
                    )
                )
            }

            val body = buildPayload(instruction = instruction, parts = parts)
            val rawResponse = postGenerateContent(apiKey, model, body)
            Result.success(parseStructuredResponse(rawResponse, fallbackOriginal = ""))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

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
                append("Si okamžitý dabingový prekladač herných dialógov (GTA VI) z angličtiny do hovorovej $langName. ")
                append("Vypočuj si priložený zvuk z televízora. Ignoruj hudbu, hluk motora, streľbu a nezrozumiteľný šum. ")
                append("Ak v zvuku nie je žiadna zrozumiteľná anglická reč, odpovedz presne:\n")
                append("SPEAKER: NONE\nEN: \nTR: \n\n")
                append("Ak niekto rozpráva po anglicky, rozpoznaj podľa hlasu či hovorí muž (M), žena (Z) alebo neisté (U). ")
                append("Prelož reč prirodzene a hovorovo (vrátane slangu) so SPRÁVNYM gramatickým rodom podľa pohlavia hovoriaceho. ")
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

            val body = buildPayload(instruction = instruction, parts = parts)
            val rawResponse = postGenerateContent(apiKey, model, body)
            Result.success(parseStructuredResponse(rawResponse, fallbackOriginal = ""))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildPayload(instruction: String, parts: JSONArray): JSONObject {
        return JSONObject().apply {
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
            put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.25)
                    .put("maxOutputTokens", 256)
            )
        }
    }

    private fun postGenerateContent(apiKey: String, model: String, payload: JSONObject): String {
        val cleanModel = model.trim().ifBlank { "gemini-2.5-flash-lite" }
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

        for (line in raw.lines()) {
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
