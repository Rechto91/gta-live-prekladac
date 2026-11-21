package com.gtatranslator.live

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "gta_live_prefs"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_LANG = "target_lang"
        private const val KEY_SHOW_ORIG = "show_original"
        private const val KEY_THRESHOLD = "vad_threshold"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_SPEECH_RATE = "speech_rate"
        private const val KEY_USE_AI_AUDIO = "use_ai_audio"
        private const val DEFAULT_MODEL = "gemini-2.5-flash-lite"

        private const val TAB_TEXT = 0
        private const val TAB_CAMERA = 1
        private const val TAB_AUDIO = 2
    }

    // Header & Tabs
    private lateinit var tvStatus: TextView
    private lateinit var btnStopSpeech: Button
    private lateinit var btnSettings: Button
    private lateinit var tabTextMode: TextView
    private lateinit var tabCameraMode: TextView
    private lateinit var tabAudioMode: TextView

    // Panels
    private lateinit var panelTextMode: LinearLayout
    private lateinit var panelCameraMode: LinearLayout
    private lateinit var panelAudioMode: LinearLayout

    // Text mode views
    private lateinit var etInputText: EditText
    private lateinit var chipVoiceAuto: TextView
    private lateinit var chipVoiceMale: TextView
    private lateinit var chipVoiceFemale: TextView
    private lateinit var btnPasteTranslate: TextView
    private lateinit var btnClearInput: TextView
    private lateinit var switchAutoType: SwitchMaterial
    private lateinit var btnTranslateText: TextView

    // Camera mode views
    private lateinit var cameraPreview: TextureView
    private lateinit var viewScanReticle: View
    private lateinit var cbCropSubtitles: CheckBox
    private lateinit var switchAutoScan: SwitchMaterial
    private lateinit var btnScanSubtitle: TextView

    // Audio mode views
    private lateinit var tvThresholdValue: TextView
    private lateinit var pbAudioLevel: ProgressBar
    private lateinit var seekThreshold: SeekBar
    private lateinit var btnMode: Button
    private lateinit var btnToggleListen: Button

    // Feed & Bottom strip
    private lateinit var tvLivePreview: TextView
    private lateinit var rvSubtitles: RecyclerView
    private lateinit var switchTts: SwitchMaterial
    private lateinit var tvVoiceInfo: TextView
    private lateinit var btnReplayLast: TextView

    private lateinit var adapter: SubtitleAdapter
    private lateinit var speechEngine: SpeechEngine
    private var cameraScanner: CameraSubtitleScanner? = null

    private val geminiClient = GeminiClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val recentContext = ArrayDeque<String>()

    private var vadRecorder: AudioVadRecorder? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var debounceJob: Job? = null
    private var autoScanJob: Job? = null
    private var isCameraBusy = false

    private var activeTab = TAB_TEXT
    private var selectedGenderHint = SpeakerGender.NEUTRAL
    private var isListening = false
    private var useAiAudioMode = true
    private var apiKey = ""
    private var modelName = DEFAULT_MODEL
    private var targetLang = "sk"
    private var showOriginal = true
    private var vadThreshold = 18
    private var fontSizeSp = 24f
    private var speechRate = 1.05f
    private var subtitleIdCounter = 1L
    private var lastAutoTranslatedInput = ""
    private var lastCameraSubtitle = ""

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startListening()
        } else {
            Toast.makeText(this, "Aplikácia potrebuje povolenie pre mikrofón.", Toast.LENGTH_LONG).show()
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCameraPreview()
        } else {
            Toast.makeText(this, "Aplikácia potrebuje povolenie pre kameru.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupSpeechEngine()
        setupRecyclerView()
        loadPrefs()
        setupListeners()
        updateVoiceChipsUi()
        updateAudioModeUi()
        selectTab(TAB_TEXT)
        updateStatusPrompt()
    }

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        btnStopSpeech = findViewById(R.id.btnStopSpeech)
        btnSettings = findViewById(R.id.btnSettings)
        tabTextMode = findViewById(R.id.tabTextMode)
        tabCameraMode = findViewById(R.id.tabCameraMode)
        tabAudioMode = findViewById(R.id.tabAudioMode)

        panelTextMode = findViewById(R.id.panelTextMode)
        panelCameraMode = findViewById(R.id.panelCameraMode)
        panelAudioMode = findViewById(R.id.panelAudioMode)

        etInputText = findViewById(R.id.etInputText)
        chipVoiceAuto = findViewById(R.id.chipVoiceAuto)
        chipVoiceMale = findViewById(R.id.chipVoiceMale)
        chipVoiceFemale = findViewById(R.id.chipVoiceFemale)
        btnPasteTranslate = findViewById(R.id.btnPasteTranslate)
        btnClearInput = findViewById(R.id.btnClearInput)
        switchAutoType = findViewById(R.id.switchAutoType)
        btnTranslateText = findViewById(R.id.btnTranslateText)

        cameraPreview = findViewById(R.id.cameraPreview)
        viewScanReticle = findViewById(R.id.viewScanReticle)
        cbCropSubtitles = findViewById(R.id.cbCropSubtitles)
        switchAutoScan = findViewById(R.id.switchAutoScan)
        btnScanSubtitle = findViewById(R.id.btnScanSubtitle)

        tvThresholdValue = findViewById(R.id.tvThresholdValue)
        pbAudioLevel = findViewById(R.id.pbAudioLevel)
        seekThreshold = findViewById(R.id.seekThreshold)
        btnMode = findViewById(R.id.btnMode)
        btnToggleListen = findViewById(R.id.btnToggleListen)

        tvLivePreview = findViewById(R.id.tvLivePreview)
        rvSubtitles = findViewById(R.id.rvSubtitles)
        switchTts = findViewById(R.id.switchTts)
        tvVoiceInfo = findViewById(R.id.tvVoiceInfo)
        btnReplayLast = findViewById(R.id.btnReplayLast)
    }

    private fun setupSpeechEngine() {
        speechEngine = SpeechEngine(
            context = this,
            onReady = { count ->
                runOnUiThread {
                    val langTag = if (targetLang == "cs") "CZ" else "SK"
                    val rateTxt = String.format(Locale.US, "%.2fx", speechRate)
                    tvVoiceInfo.text = "$langTag hlasy: $count • $rateTxt"
                }
            },
            onSpeakingChanged = { speaking ->
                runOnUiThread {
                    btnStopSpeech.alpha = if (speaking) 1.0f else 0.55f
                }
            }
        )
    }

    private fun setupRecyclerView() {
        adapter = SubtitleAdapter { item ->
            speechEngine.speak(item.translatedText, item.gender, flushQueue = true)
        }
        val lm = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        rvSubtitles.layoutManager = lm
        rvSubtitles.adapter = adapter
    }

    private fun loadPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        modelName = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        targetLang = prefs.getString(KEY_LANG, "sk") ?: "sk"
        showOriginal = prefs.getBoolean(KEY_SHOW_ORIG, true)
        vadThreshold = prefs.getInt(KEY_THRESHOLD, 18)
        fontSizeSp = prefs.getFloat(KEY_FONT_SIZE, 24f)
        speechRate = prefs.getFloat(KEY_SPEECH_RATE, 1.05f)
        useAiAudioMode = prefs.getBoolean(KEY_USE_AI_AUDIO, true)

        adapter.fontSizeSp = fontSizeSp
        adapter.showOriginal = showOriginal
        seekThreshold.progress = vadThreshold
        tvThresholdValue.text = "Prah: $vadThreshold%"
        speechEngine.setTargetLanguage(targetLang)
        speechEngine.setSpeechRate(speechRate)
        updateVoiceInfoBar()
    }

    private fun savePrefs() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_MODEL, modelName)
            .putString(KEY_LANG, targetLang)
            .putBoolean(KEY_SHOW_ORIG, showOriginal)
            .putInt(KEY_THRESHOLD, vadThreshold)
            .putFloat(KEY_FONT_SIZE, fontSizeSp)
            .putFloat(KEY_SPEECH_RATE, speechRate)
            .putBoolean(KEY_USE_AI_AUDIO, useAiAudioMode)
            .apply()
    }

    private fun updateVoiceInfoBar() {
        val langTag = if (targetLang == "cs") "CZ" else "SK"
        val rateTxt = String.format(Locale.US, "%.2fx", speechRate)
        tvVoiceInfo.text = "$langTag reč • $rateTxt • $modelName"
    }

    private fun setupListeners() {
        btnSettings.setOnClickListener { showSettingsDialog() }
        btnStopSpeech.setOnClickListener { speechEngine.stop() }

        btnReplayLast.setOnClickListener {
            val last = adapter.getLastItem()
            if (last != null) {
                speechEngine.speak(last.translatedText, last.gender, flushQueue = true)
            } else {
                Toast.makeText(this, "Zatiaľ neexistuje žiadny preklad.", Toast.LENGTH_SHORT).show()
            }
        }

        tabTextMode.setOnClickListener { selectTab(TAB_TEXT) }
        tabCameraMode.setOnClickListener { selectTab(TAB_CAMERA) }
        tabAudioMode.setOnClickListener { selectTab(TAB_AUDIO) }

        // Text Mode Listeners
        chipVoiceAuto.setOnClickListener {
            selectedGenderHint = SpeakerGender.NEUTRAL
            updateVoiceChipsUi()
        }
        chipVoiceMale.setOnClickListener {
            selectedGenderHint = SpeakerGender.MALE
            updateVoiceChipsUi()
        }
        chipVoiceFemale.setOnClickListener {
            selectedGenderHint = SpeakerGender.FEMALE
            updateVoiceChipsUi()
        }

        btnClearInput.setOnClickListener {
            debounceJob?.cancel()
            etInputText.setText("")
            lastAutoTranslatedInput = ""
        }

        btnPasteTranslate.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
            if (clip.isBlank()) {
                Toast.makeText(this, "Schránka je prázdna.", Toast.LENGTH_SHORT).show()
            } else {
                etInputText.setText(clip)
                etInputText.setSelection(clip.length)
                triggerTextTranslation(clip, flushSpeech = true)
            }
        }

        btnTranslateText.setOnClickListener {
            val txt = etInputText.text.toString().trim()
            if (txt.isBlank()) {
                Toast.makeText(this, "Najprv napíš alebo vlož text.", Toast.LENGTH_SHORT).show()
            } else {
                triggerTextTranslation(txt, flushSpeech = true)
            }
        }

        etInputText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!switchAutoType.isChecked) return
                val text = s?.toString()?.trim().orEmpty()
                debounceJob?.cancel()
                if (text.length < 3 || text == lastAutoTranslatedInput) return
                debounceJob = scope.launch {
                    delay(750L)
                    if (text != lastAutoTranslatedInput && apiKey.isNotBlank()) {
                        triggerTextTranslation(text, flushSpeech = true)
                    }
                }
            }
        })

        // Camera Mode Listeners
        cbCropSubtitles.setOnCheckedChangeListener { _, isChecked ->
            viewScanReticle.visibility = if (isChecked) View.VISIBLE else View.GONE
            cameraScanner?.resetChangeDetector()
        }

        btnScanSubtitle.setOnClickListener {
            performCameraScan(requireChange = false)
        }

        switchAutoScan.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startAutoCameraLoop()
            } else {
                stopAutoCameraLoop()
            }
        }

        // Audio Mode Listeners
        btnMode.setOnClickListener {
            val wasListening = isListening
            if (wasListening) stopListening()
            useAiAudioMode = !useAiAudioMode
            savePrefs()
            updateAudioModeUi()
            if (wasListening) startListening()
        }

        btnToggleListen.setOnClickListener {
            if (isListening) stopListening() else checkAndStartListening()
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
    }

    private fun selectTab(tab: Int) {
        if (activeTab == TAB_CAMERA && tab != TAB_CAMERA) {
            stopAutoCameraLoop()
            cameraScanner?.stop()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (activeTab == TAB_AUDIO && tab != TAB_AUDIO && isListening) {
            stopListening()
        }

        activeTab = tab
        panelTextMode.visibility = if (tab == TAB_TEXT) View.VISIBLE else View.GONE
        panelCameraMode.visibility = if (tab == TAB_CAMERA) View.VISIBLE else View.GONE
        panelAudioMode.visibility = if (tab == TAB_AUDIO) View.VISIBLE else View.GONE

        styleTab(tabTextMode, tab == TAB_TEXT)
        styleTab(tabCameraMode, tab == TAB_CAMERA)
        styleTab(tabAudioMode, tab == TAB_AUDIO)

        if (tab == TAB_CAMERA) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            checkAndStartCamera()
        }
    }

    private fun styleTab(view: TextView, active: Boolean) {
        view.setBackgroundResource(if (active) R.drawable.bg_tab_active else R.drawable.bg_tab_inactive)
        view.setTextColor(
            ContextCompat.getColor(this, if (active) R.color.text_primary else R.color.text_secondary)
        )
    }

    private fun updateVoiceChipsUi() {
        chipVoiceAuto.setBackgroundResource(
            if (selectedGenderHint == SpeakerGender.NEUTRAL) R.drawable.bg_badge_neutral else R.drawable.bg_tab_inactive
        )
        chipVoiceAuto.setTextColor(
            ContextCompat.getColor(
                this,
                if (selectedGenderHint == SpeakerGender.NEUTRAL) R.color.accent_amber else R.color.text_secondary
            )
        )

        chipVoiceMale.setBackgroundResource(
            if (selectedGenderHint == SpeakerGender.MALE) R.drawable.bg_badge_male else R.drawable.bg_tab_inactive
        )
        chipVoiceMale.setTextColor(
            ContextCompat.getColor(
                this,
                if (selectedGenderHint == SpeakerGender.MALE) R.color.accent_cyan else R.color.text_secondary
            )
        )

        chipVoiceFemale.setBackgroundResource(
            if (selectedGenderHint == SpeakerGender.FEMALE) R.drawable.bg_badge_female else R.drawable.bg_tab_inactive
        )
        chipVoiceFemale.setTextColor(
            ContextCompat.getColor(
                this,
                if (selectedGenderHint == SpeakerGender.FEMALE) R.color.accent_pink else R.color.text_secondary
            )
        )
    }

    private fun updateAudioModeUi() {
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

    private fun ensureApiKeyReady(): Boolean {
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Najprv zadaj Gemini API kľúč v Nastaveniach (⚙).", Toast.LENGTH_LONG).show()
            showSettingsDialog()
            return false
        }
        return true
    }

    // --- MODE 1: Instant Text -> Slovak Speech ---
    private fun triggerTextTranslation(text: String, flushSpeech: Boolean) {
        if (!ensureApiKeyReady()) return
        lastAutoTranslatedInput = text
        tvLivePreview.visibility = View.VISIBLE
        tvLivePreview.text = "Prekladám do slovenskej reči..."

        scope.launch {
            val res = geminiClient.translateText(
                apiKey = apiKey,
                model = modelName,
                targetLang = targetLang,
                englishText = text,
                genderHint = selectedGenderHint,
                recentContext = recentContext.toList()
            )
            handleTranslationResult(res, sourceTag = "TEXT", flushSpeech = flushSpeech)
            if (activeTab == TAB_TEXT) {
                tvLivePreview.visibility = View.GONE
            }
        }
    }

    // --- MODE 2: TV Camera Subtitle Scanner ---
    private fun checkAndStartCamera() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            startCameraPreview()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCameraPreview() {
        if (cameraScanner == null) {
            cameraScanner = CameraSubtitleScanner(
                context = this,
                textureView = cameraPreview,
                onStatus = { msg -> runOnUiThread { tvStatus.text = msg } }
            )
        }
        cameraScanner?.start()
    }

    private fun startAutoCameraLoop() {
        if (!ensureApiKeyReady()) {
            switchAutoScan.isChecked = false
            return
        }
        stopAutoCameraLoop()
        tvLivePreview.visibility = View.VISIBLE
        tvLivePreview.text = "Auto-sken aktívny: sledujem titulky z TV..."
        autoScanJob = scope.launch {
            while (switchAutoScan.isChecked && activeTab == TAB_CAMERA) {
                performCameraScan(requireChange = true)
                delay(2800L)
            }
        }
    }

    private fun stopAutoCameraLoop() {
        autoScanJob?.cancel()
        autoScanJob = null
        if (activeTab == TAB_CAMERA) {
            tvLivePreview.visibility = View.GONE
        }
    }

    private fun performCameraScan(requireChange: Boolean) {
        if (isCameraBusy) return
        if (!ensureApiKeyReady()) return
        val scanner = cameraScanner ?: return

        val jpeg = scanner.captureSubtitleJpeg(
            cropBottomOnly = cbCropSubtitles.isChecked,
            requireChange = requireChange
        ) ?: return

        isCameraBusy = true
        tvLivePreview.visibility = View.VISIBLE
        tvLivePreview.text = "Čítam titulky z televízora..."

        scope.launch {
            try {
                val res = geminiClient.translateSubtitleImage(
                    apiKey = apiKey,
                    model = modelName,
                    targetLang = targetLang,
                    jpegBytes = jpeg,
                    recentContext = recentContext.toList()
                )
                res.onSuccess { tr ->
                    val normalized = tr.translatedText.trim().lowercase(Locale.ROOT)
                    if (!tr.isEmptySpeech && normalized.isNotBlank() && normalized != lastCameraSubtitle) {
                        lastCameraSubtitle = normalized
                        handleTranslationResult(Result.success(tr), sourceTag = "TV", flushSpeech = true)
                    } else if (!requireChange) {
                        tvLivePreview.text = "Na zábere sa nenašiel žiadny čitateľný titulok."
                    }
                }.onFailure { err ->
                    tvStatus.text = err.message ?: "Chyba pri čítaní titulkov"
                }
            } finally {
                isCameraBusy = false
                if (!switchAutoScan.isChecked && activeTab == TAB_CAMERA) {
                    delay(1200L)
                    if (!isCameraBusy && !switchAutoScan.isChecked) {
                        tvLivePreview.visibility = View.GONE
                    }
                }
            }
        }
    }

    // --- MODE 3: Live TV Audio ---
    private fun checkAndStartListening() {
        if (!ensureApiKeyReady()) return
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
                    handleTranslationResult(result, sourceTag = "ZVUK", flushSpeech = false)
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
            tvStatus.text = "Android rozpoznávanie reči nie je dostupné. Prepni na AI Sluch."
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
                                genderHint = SpeakerGender.NEUTRAL,
                                recentContext = recentContext.toList()
                            )
                            handleTranslationResult(res, sourceTag = "STT", flushSpeech = false)
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
        mainHandler.postDelayed({ launchSttIntent() }, delayMs)
    }

    private fun handleTranslationResult(
        result: Result<TranslationResult>,
        sourceTag: String,
        flushSpeech: Boolean
    ) {
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
                timestamp = timeFormat.format(Date()),
                sourceTag = sourceTag
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
                speechEngine.speak(tr.translatedText, tr.gender, flushQueue = flushSpeech)
            }
        }.onFailure { err ->
            tvStatus.text = err.message ?: "Chyba komunikácie s Gemini API"
        }
    }

    private fun showSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etApiKey = view.findViewById<EditText>(R.id.etApiKey)
        val etModel = view.findViewById<EditText>(R.id.etModel)
        val btnModel25Lite = view.findViewById<TextView>(R.id.btnModel25Lite)
        val btnModel25Flash = view.findViewById<TextView>(R.id.btnModel25Flash)
        val btnModel35Lite = view.findViewById<TextView>(R.id.btnModel35Lite)
        val rbSlovak = view.findViewById<RadioButton>(R.id.rbSlovak)
        val rbCzech = view.findViewById<RadioButton>(R.id.rbCzech)
        val tvRateLabel = view.findViewById<TextView>(R.id.tvRateLabel)
        val seekSpeechRate = view.findViewById<SeekBar>(R.id.seekSpeechRate)
        val tvFontLabel = view.findViewById<TextView>(R.id.tvFontLabel)
        val seekFontSize = view.findViewById<SeekBar>(R.id.seekFontSize)
        val cbShowOriginal = view.findViewById<CheckBox>(R.id.cbShowOriginal)
        val btnClearHistory = view.findViewById<Button>(R.id.btnClearHistory)

        etApiKey.setText(apiKey)
        etModel.setText(modelName)
        if (targetLang == "cs") rbCzech.isChecked = true else rbSlovak.isChecked = true
        cbShowOriginal.isChecked = showOriginal

        var tempRate = speechRate
        var tempFont = fontSizeSp
        seekSpeechRate.progress = ((tempRate - 0.75f) / 0.05f).toInt().coerceIn(0, 14)
        tvRateLabel.text = String.format(Locale.US, "Rýchlosť slovenskej reči: %.2fx", tempRate)
        seekFontSize.progress = (tempFont - 18f).toInt().coerceIn(0, 20)
        tvFontLabel.text = "Veľkosť písma titulkov: ${tempFont.toInt()} sp"

        btnModel25Lite.setOnClickListener { etModel.setText("gemini-2.5-flash-lite") }
        btnModel25Flash.setOnClickListener { etModel.setText("gemini-2.5-flash") }
        btnModel35Lite.setOnClickListener { etModel.setText("gemini-3.5-flash-lite") }

        seekSpeechRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tempRate = 0.75f + (progress * 0.05f)
                tvRateLabel.text = String.format(Locale.US, "Rýchlosť slovenskej reči: %.2fx", tempRate)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tempFont = 18f + progress
                tvFontLabel.text = "Veľkosť písma titulkov: ${tempFont.toInt()} sp"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(view)
            .setPositiveButton("Uložiť") { _, _ ->
                apiKey = etApiKey.text.toString().trim()
                modelName = etModel.text.toString().trim().ifBlank { DEFAULT_MODEL }
                targetLang = if (rbCzech.isChecked) "cs" else "sk"
                showOriginal = cbShowOriginal.isChecked
                speechRate = tempRate
                fontSizeSp = tempFont

                adapter.showOriginal = showOriginal
                adapter.fontSizeSp = fontSizeSp
                speechEngine.setTargetLanguage(targetLang)
                speechEngine.setSpeechRate(speechRate)
                savePrefs()
                updateVoiceInfoBar()
                if (!isListening && activeTab != TAB_CAMERA) updateStatusPrompt()
                Toast.makeText(this, "Nastavenia uložené.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Zrušiť", null)
            .create()

        btnClearHistory.setOnClickListener {
            adapter.clearAll()
            recentContext.clear()
            lastCameraSubtitle = ""
            Toast.makeText(this, "História vymazaná.", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    override fun onPause() {
        super.onPause()
        if (activeTab == TAB_CAMERA) {
            stopAutoCameraLoop()
            cameraScanner?.stop()
        }
    }

    override fun onResume() {
        super.onResume()
        if (activeTab == TAB_CAMERA) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) startCameraPreview()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoCameraLoop()
        cameraScanner?.stop()
        stopListening()
        scope.cancel()
        speechEngine.shutdown()
    }
}
