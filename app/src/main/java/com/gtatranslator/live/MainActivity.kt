package com.gtatranslator.live

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        private const val PREFS_NAME = "gta_live_prefs"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_LANG = "target_lang"
        private const val KEY_SHOW_ORIG = "show_original"
        private const val KEY_THRESHOLD = "vad_threshold"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_USE_AI_AUDIO = "use_ai_audio"
        private const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvLivePreview: TextView
    private lateinit var tvThresholdValue: TextView
    private lateinit var pbAudioLevel: ProgressBar
    private lateinit var seekThreshold: SeekBar
    private lateinit var seekFontSize: SeekBar
    private lateinit var btnMode: Button
    private lateinit var btnSettings: Button
    private lateinit var btnToggleListen: Button
    private lateinit var switchTts: SwitchMaterial
    private lateinit var rvSubtitles: RecyclerView

    private val adapter = SubtitleAdapter()
    private val geminiClient = GeminiClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val recentContext = ArrayDeque<String>()

    private var vadRecorder: AudioVadRecorder? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var isListening = false
    private var useAiAudioMode = true
    private var apiKey = ""
    private var modelName = DEFAULT_MODEL
    private var targetLang = "sk"
    private var showOriginal = true
    private var vadThreshold = 18
    private var fontSizeSp = 26f
    private var subtitleIdCounter = 1L

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startListening()
        } else {
            Toast.makeText(this, "Aplikácia potrebuje prístup k mikrofónu.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        loadPrefs()
        setupRecyclerView()
        setupListeners()
        initTts()
        updateModeUi()
        updateStatusPrompt()
    }

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvLivePreview = findViewById(R.id.tvLivePreview)
        tvThresholdValue = findViewById(R.id.tvThresholdValue)
        pbAudioLevel = findViewById(R.id.pbAudioLevel)
        seekThreshold = findViewById(R.id.seekThreshold)
        seekFontSize = findViewById(R.id.seekFontSize)
        btnMode = findViewById(R.id.btnMode)
        btnSettings = findViewById(R.id.btnSettings)
        btnToggleListen = findViewById(R.id.btnToggleListen)
        switchTts = findViewById(R.id.switchTts)
        rvSubtitles = findViewById(R.id.rvSubtitles)
    }

    private fun loadPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        modelName = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        targetLang = prefs.getString(KEY_LANG, "sk") ?: "sk"
        showOriginal = prefs.getBoolean(KEY_SHOW_ORIG, true)
        vadThreshold = prefs.getInt(KEY_THRESHOLD, 18)
        fontSizeSp = prefs.getFloat(KEY_FONT_SIZE, 26f)
        useAiAudioMode = prefs.getBoolean(KEY_USE_AI_AUDIO, true)

        adapter.fontSizeSp = fontSizeSp
        adapter.showOriginal = showOriginal
        seekThreshold.progress = vadThreshold
        tvThresholdValue.text = "Prah: $vadThreshold%"
        seekFontSize.progress = (fontSizeSp - 18f).toInt().coerceIn(0, 20)
    }

    private fun savePrefs() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_MODEL, modelName)
            .putString(KEY_LANG, targetLang)
            .putBoolean(KEY_SHOW_ORIG, showOriginal)
            .putInt(KEY_THRESHOLD, vadThreshold)
            .putFloat(KEY_FONT_SIZE, fontSizeSp)
            .putBoolean(KEY_USE_AI_AUDIO, useAiAudioMode)
            .apply()
    }

    private fun setupRecyclerView() {
        val lm = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        rvSubtitles.layoutManager = lm
        rvSubtitles.adapter = adapter
    }

    private fun setupListeners() {
        btnSettings.setOnClickListener { showSettingsDialog() }

        btnMode.setOnClickListener {
            val wasListening = isListening
            if (wasListening) stopListening()
            useAiAudioMode = !useAiAudioMode
            savePrefs()
            updateModeUi()
            if (wasListening) startListening()
        }

        btnToggleListen.setOnClickListener {
            if (isListening) {
                stopListening()
            } else {
                checkAndStartListening()
            }
        }

        seekThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                vadThreshold = progress.coerceAtLeast(3)
                tvThresholdValue.text = "Prah: $vadThreshold%"
                vadRecorder?.thresholdPercent = vadThreshold
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                savePrefs()
            }
        })

        seekFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                fontSizeSp = 18f + progress
                adapter.fontSizeSp = fontSizeSp
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                savePrefs()
            }
        })
    }

    private fun updateModeUi() {
        if (useAiAudioMode) {
            btnMode.text = getString(R.string.mode_ai_audio)
            pbAudioLevel.visibility = View.VISIBLE
            seekThreshold.isEnabled = true
        } else {
            btnMode.text = getString(R.string.mode_android_stt)
            pbAudioLevel.progress = 0
            seekThreshold.isEnabled = false
        }
    }

    private fun updateStatusPrompt() {
        tvStatus.text = if (apiKey.isBlank()) {
            getString(R.string.status_need_key)
        } else {
            getString(R.string.status_ready)
        }
    }

    private fun checkAndStartListening() {
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Najprv zadaj Gemini API kľúč v Nastaveniach.", Toast.LENGTH_LONG).show()
            showSettingsDialog()
            return
        }

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            startListening()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        if (isListening) return
        isListening = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        btnToggleListen.text = getString(R.string.btn_stop)
        btnToggleListen.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_red)
        tvLivePreview.visibility = View.VISIBLE

        if (useAiAudioMode) {
            tvStatus.text = "Počúvam TV cez AI Sluch ($modelName)..."
            tvLivePreview.text = "Čakám na dialóg (prah $vadThreshold%)..."
            startAiAudioMode()
        } else {
            tvStatus.text = "Počúvam TV cez Android STT..."
            tvLivePreview.text = "Rozpoznávam angličtinu..."
            startAndroidSttMode()
        }
    }

    private fun stopListening() {
        isListening = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        vadRecorder?.stop()
        vadRecorder = null

        mainHandler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null

        pbAudioLevel.progress = 0
        tvLivePreview.visibility = View.GONE
        btnToggleListen.text = getString(R.string.btn_start)
        btnToggleListen.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_cyan)
        updateStatusPrompt()
    }

    private fun startAiAudioMode() {
        val recorder = AudioVadRecorder(
            onLevelChanged = { pct, capturing ->
                runOnUiThread {
                    if (!isListening) return@runOnUiThread
                    pbAudioLevel.progress = pct
                    val tintColor = if (capturing) R.color.accent_pink else R.color.accent_cyan
                    pbAudioLevel.progressTintList = ContextCompat.getColorStateList(this, tintColor)
                    if (capturing) {
                        tvLivePreview.text = "Zachytávam reč z TV..."
                    }
                }
            },
            onSpeechSegmentReady = { wavBytes ->
                scope.launch {
                    if (!isListening) return@launch
                    tvLivePreview.text = "AI analyzuje hlas (Muž/Žena) a prekladá..."
                    val result = geminiClient.translateAudioWav(
                        apiKey = apiKey,
                        model = modelName,
                        targetLang = targetLang,
                        wavBytes = wavBytes,
                        recentContext = recentContext.toList()
                    )
                    handleTranslationResult(result)
                }
            },
            onError = { err ->
                runOnUiThread {
                    tvStatus.text = err
                    stopListening()
                }
            }
        )
        recorder.thresholdPercent = vadThreshold
        vadRecorder = recorder
        recorder.start()
    }

    private fun startAndroidSttMode() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            tvStatus.text = "Android rozpoznávanie reči nie je na tomto mobile dostupné. Prepni na AI Sluch."
            stopListening()
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    tvLivePreview.text = "Počúvam angličtinu..."
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    val pct = ((rmsdB + 2f) * 8f).toInt().coerceIn(0, 100)
                    pbAudioLevel.progress = pct
                }

                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    if (isListening && !useAiAudioMode) {
                        scheduleSttRestart(350L)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spoken = matches?.firstOrNull()?.trim().orEmpty()
                    if (spoken.isNotBlank()) {
                        tvLivePreview.text = "Prekladám: $spoken"
                        scope.launch {
                            val res = geminiClient.translateText(
                                apiKey = apiKey,
                                model = modelName,
                                targetLang = targetLang,
                                englishText = spoken,
                                recentContext = recentContext.toList()
                            )
                            handleTranslationResult(res)
                        }
                    }
                    if (isListening && !useAiAudioMode) {
                        scheduleSttRestart(150L)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!partial.isNullOrBlank()) {
                        tvLivePreview.text = "EN: $partial"
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        launchSttIntent()
    }

    private fun launchSttIntent() {
        if (!isListening || useAiAudioMode) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            scheduleSttRestart(600L)
        }
    }

    private fun scheduleSttRestart(delayMs: Long) {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            launchSttIntent()
        }, delayMs)
    }

    private fun handleTranslationResult(result: Result<TranslationResult>) {
        result.onSuccess { tr ->
            if (tr.isEmptySpeech || tr.translatedText.isBlank()) {
                if (isListening) {
                    tvLivePreview.text = "Počúvam dialóg z TV..."
                }
                return@onSuccess
            }

            val item = SubtitleItem(
                id = subtitleIdCounter++,
                gender = tr.gender,
                translatedText = tr.translatedText,
                originalText = tr.originalEnglish,
                timestamp = timeFormat.format(Date())
            )
            adapter.addItem(item)
            rvSubtitles.smoothScrollToPosition(adapter.itemCount - 1)

            val speakerTag = when (tr.gender) {
                SpeakerGender.MALE -> "M"
                SpeakerGender.FEMALE -> "Z"
                SpeakerGender.NEUTRAL -> "U"
            }
            if (recentContext.size >= 4) recentContext.removeFirst()
            recentContext.addLast("[$speakerTag] ${tr.originalEnglish} -> ${tr.translatedText}")

            if (isListening) {
                tvLivePreview.text = "Počúvam ďalšiu repliku..."
            }

            if (switchTts.isChecked) {
                speakTranslation(tr.translatedText, tr.gender)
            }
        }.onFailure { err ->
            tvStatus.text = err.message ?: "Chyba komunikácie s Gemini API"
        }
    }

    private fun initTts() {
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            updateTtsLanguage()
        }
    }

    private fun updateTtsLanguage() {
        if (!ttsReady) return
        val locale = if (targetLang == "cs") Locale("cs", "CZ") else Locale("sk", "SK")
        tts?.language = locale
    }

    private fun speakTranslation(text: String, gender: SpeakerGender) {
        if (!ttsReady) return
        val pitch = when (gender) {
            SpeakerGender.MALE -> 0.84f
            SpeakerGender.FEMALE -> 1.18f
            SpeakerGender.NEUTRAL -> 1.0f
        }
        tts?.setPitch(pitch)
        tts?.setSpeechRate(1.08f)
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "gta_tts_${System.currentTimeMillis()}")
    }

    private fun showSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etApiKey = view.findViewById<EditText>(R.id.etApiKey)
        val etModel = view.findViewById<EditText>(R.id.etModel)
        val rbSlovak = view.findViewById<RadioButton>(R.id.rbSlovak)
        val rbCzech = view.findViewById<RadioButton>(R.id.rbCzech)
        val cbShowOriginal = view.findViewById<CheckBox>(R.id.cbShowOriginal)
        val btnClearHistory = view.findViewById<Button>(R.id.btnClearHistory)

        etApiKey.setText(apiKey)
        etModel.setText(modelName)
        if (targetLang == "cs") rbCzech.isChecked = true else rbSlovak.isChecked = true
        cbShowOriginal.isChecked = showOriginal

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(view)
            .setPositiveButton("Uložiť") { _, _ ->
                apiKey = etApiKey.text.toString().trim()
                modelName = etModel.text.toString().trim().ifBlank { DEFAULT_MODEL }
                targetLang = if (rbCzech.isChecked) "cs" else "sk"
                showOriginal = cbShowOriginal.isChecked
                adapter.showOriginal = showOriginal
                savePrefs()
                updateTtsLanguage()
                if (!isListening) updateStatusPrompt()
                Toast.makeText(this, "Nastavenia uložené.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Zrušiť", null)
            .create()

        btnClearHistory.setOnClickListener {
            adapter.clearAll()
            recentContext.clear()
            Toast.makeText(this, "História vymazaná.", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        scope.cancel()
        tts?.stop()
        tts?.shutdown()
    }
}
