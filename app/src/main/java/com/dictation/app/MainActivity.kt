package com.dictation.app

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import java.util.*
import kotlin.coroutines.resume

enum class StepMode(val title: String) {
    WORDS("По словам"),
    SENTENCES("По предложениям")
}

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private val availableVoicesState = mutableStateListOf<Voice>()
    private var isTtsReady by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DictationScreen(
                        isTtsReady = isTtsReady,
                        availableVoices = availableVoicesState,
                        tts = tts
                    )
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let { engine ->
                engine.language = Locale("ru", "RU")
                try {
                    val voices = engine.voices
                    if (!voices.isNullOrEmpty()) {
                        val ruVoices = voices.filter { it.locale.language.equals("ru", ignoreCase = true) }
                        val others = voices.filter { !it.locale.language.equals("ru", ignoreCase = true) }
                        availableVoicesState.clear()
                        availableVoicesState.addAll(ruVoices + others)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                isTtsReady = true
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictationScreen(
    isTtsReady: Boolean,
    availableVoices: List<Voice>,
    tts: TextToSpeech?
) {
    var rawText by remember { mutableStateOf("") }
    var tokens by remember { mutableStateOf(listOf<String>()) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }

    // Настройки
    var selectedVoice by remember { mutableStateOf<Voice?>(null) }
    var speechRate by remember { mutableFloatStateOf(0.80f) }
    var speechPitch by remember { mutableFloatStateOf(1.0f) }
    var basePauseSec by remember { mutableFloatStateOf(0.3f) }
    var charMultiplierMs by remember { mutableIntStateOf(120) }
    var mode by remember { mutableStateOf(StepMode.WORDS) }

    var showSettingsSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var loopJob by remember { mutableStateOf<Job?>(null) }
    val listState = rememberLazyListState()

    // Автоматический выбор первого русского голоса
    LaunchedEffect(availableVoices) {
        if (selectedVoice == null && availableVoices.isNotEmpty()) {
            selectedVoice = availableVoices.firstOrNull { it.locale.language == "ru" } ?: availableVoices.first()
        }
    }

    fun parseTokens() {
        if (rawText.isBlank()) {
            tokens = emptyList()
            return
        }
        tokens = if (mode == StepMode.WORDS) {
            rawText.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        } else {
            rawText.trim().split("(?<=[.?!])\\s+".toRegex()).filter { it.isNotEmpty() }
        }
        currentIndex = 0
    }

    fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
        tts?.stop()
        isPlaying = false
    }

    suspend fun speakToken(text: String): Boolean = suspendCancellableCoroutine { cont ->
        val utteranceId = UUID.randomUUID().toString()

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                if (id == utteranceId && cont.isActive) cont.resume(true)
            }
            override fun onError(id: String?) {
                if (id == utteranceId && cont.isActive) cont.resume(false)
            }
        })

        selectedVoice?.let { tts?.voice = it }
        tts?.setSpeechRate(speechRate)
        tts?.setPitch(speechPitch)

        val res = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (res != TextToSpeech.SUCCESS && cont.isActive) {
            cont.resume(false)
        }
    }

    fun startLoop() {
        if (tokens.isEmpty()) parseTokens()
        if (tokens.isEmpty()) return

        isPlaying = true
        loopJob = scope.launch(Dispatchers.Default) {
            while (isActive && currentIndex < tokens.size) {
                val currentText = tokens[currentIndex]
                withContext(Dispatchers.Main) {
                    listState.animateScrollToItem(currentIndex)
                }

                speakToken(currentText)

                if (!isActive) break

                val letters = currentText.filter { it.isLetterOrDigit() }.length.coerceAtLeast(1)
                val totalDelay = (basePauseSec * 1000).toLong() + (letters * charMultiplierMs).toLong()

                delay(totalDelay)

                withContext(Dispatchers.Main) {
                    if (currentIndex < tokens.size - 1) {
                        currentIndex++
                    } else {
                        isPlaying = false
                    }
                }
            }
            withContext(Dispatchers.Main) { isPlaying = false }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Диктовка Material", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        stopLoop()
                        if (currentIndex > 0) currentIndex--
                    }) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Назад")
                    }

                    Button(
                        onClick = {
                            if (isPlaying) stopLoop() else startLoop()
                        },
                        modifier = Modifier.height(56.dp).weight(1f).padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isPlaying) "Пауза" else "Старт", fontSize = 16.sp)
                    }

                    IconButton(onClick = {
                        stopLoop()
                        if (currentIndex < tokens.size - 1) currentIndex++
                    }) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Вперед")
                    }

                    IconButton(onClick = {
                        stopLoop()
                        currentIndex = 0
                    }) {
                        Icon(Icons.Default.RestartAlt, contentDescription = "Сброс")
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Окно чтения
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                if (tokens.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Вставь текст ниже и нажми «Загрузить»",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(tokens) { idx, token ->
                            val isCurrent = idx == currentIndex
                            Text(
                                text = token,
                                fontSize = if (isCurrent) 22.sp else 18.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .clickable {
                                        stopLoop()
                                        currentIndex = idx
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            // Поле ввода
            OutlinedTextField(
                value = rawText,
                onValueChange = { rawText = it },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                label = { Text("Исходный текст конспекта") },
                shape = RoundedCornerShape(16.dp)
            )

            Button(
                onClick = {
                    stopLoop()
                    parseTokens()
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Загрузить текст в ленту")
            }
        }
    }

    // Шторка настроек Material You
    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    "Настройки диктовки",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Выбор любого голоса
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Системный голос", fontWeight = FontWeight.SemiBold)
                    var expanded by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            selectedVoice?.let { "${it.name} [${it.locale}]" } ?: "Выбрать голос",
                            maxLines = 1
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        availableVoices.forEach { voice ->
                            DropdownMenuItem(
                                text = { Text("${voice.name} (${voice.locale.displayName})") },
                                onClick = {
                                    selectedVoice = voice
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                // Скорость речи (Слайдер + Ручной ввод)
                SettingInputRow(
                    title = "Скорость речи",
                    value = speechRate,
                    unit = "x",
                    range = 0.05f..2.0f,
                    onValueChange = { speechRate = it }
                )

                // Тон речи (Pitch)
                SettingInputRow(
                    title = "Высота тона (Питч)",
                    value = speechPitch,
                    unit = "",
                    range = 0.5f..2.0f,
                    onValueChange = { speechPitch = it }
                )

                // Базовая пауза
                SettingInputRow(
                    title = "Базовая пауза",
                    value = basePauseSec,
                    unit = "сек",
                    range = 0.0f..10.0f,
                    onValueChange = { basePauseSec = it }
                )

                // Множитель за букву
                SettingInputRow(
                    title = "Множитель за символ",
                    value = charMultiplierMs.toFloat(),
                    unit = "мс",
                    range = 0f..1000f,
                    onValueChange = { charMultiplierMs = it.toInt() }
                )

                // Режим разбиения
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Режим шага", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StepMode.values().forEach { m ->
                            FilterChip(
                                selected = mode == m,
                                onClick = {
                                    mode = m
                                    parseTokens()
                                },
                                label = { Text(m.title) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun SettingInputRow(
    title: String,
    value: Float,
    unit: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    var textVal by remember(value) { mutableStateOf(String.format(Locale.US, "%.2f", value)) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

            OutlinedTextField(
                value = textVal,
                onValueChange = { str ->
                    textVal = str
                    str.toFloatOrNull()?.let { num ->
                        onValueChange(num)
                    }
                },
                modifier = Modifier.width(90.dp).height(50.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                singleLine = true,
                shape = RoundedCornerShape(8.dp)
            )
        }

        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { onValueChange(it) },
            valueRange = range,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
