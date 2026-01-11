package com.example.appgym

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppGymApp() }
    }
}

/* --------------------------
   App root + Navigation
--------------------------- */

private enum class Tab { Workout, Timer, History, Settings }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppGymApp() {
    val context = LocalContext.current
    val db = remember { AppDb.get(context.applicationContext) }

    val repo = remember {
        WorkoutRepository(
            workoutDao = db.workoutDao(),
            exerciseDao = db.exerciseDao(),
            timerDao = db.timerDao(),
            voiceDao = db.voiceDao()
        )
    }

    val vm: AppViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = AppViewModelFactory(repo)
    )

    var tab by rememberSaveable { mutableStateOf(Tab.Workout) }

    // Shared timer state (single source of truth across tabs)
    var timerRunning by rememberSaveable { mutableStateOf(false) }
    var timerRemainingSec by rememberSaveable { mutableIntStateOf(0) }
    var timerTotalSec by rememberSaveable { mutableIntStateOf(0) }

    // Settings (MVP in-memory; si quieres persistir, DataStore)
    var autoStartTimer by rememberSaveable { mutableStateOf(true) }
    var defaultRestSec by rememberSaveable { mutableIntStateOf(120) }

    // Timer ticking
    LaunchedEffect(timerRunning, timerRemainingSec) {
        if (!timerRunning) return@LaunchedEffect
        if (timerRemainingSec <= 0) {
            timerRunning = false
            return@LaunchedEffect
        }
        delay(1000)
        timerRemainingSec -= 1
    }

    fun startTimer(seconds: Int) {
        timerTotalSec = seconds.coerceAtLeast(0)
        timerRemainingSec = seconds.coerceAtLeast(0)
        timerRunning = seconds > 0
    }

    fun stopTimer() {
        timerRunning = false
    }

    fun resetTimer() {
        timerRunning = false
        timerRemainingSec = timerTotalSec
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("AppGym") }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.Workout,
                    onClick = { tab = Tab.Workout },
                    icon = { Icon(Icons.Default.FitnessCenter, contentDescription = null) },
                    label = { Text("Workout") }
                )
                NavigationBarItem(
                    selected = tab == Tab.Timer,
                    onClick = { tab = Tab.Timer },
                    icon = { Icon(Icons.Default.Timer, contentDescription = null) },
                    label = { Text("Timer") }
                )
                NavigationBarItem(
                    selected = tab == Tab.History,
                    onClick = { tab = Tab.History },
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text("History") }
                )
                NavigationBarItem(
                    selected = tab == Tab.Settings,
                    onClick = { tab = Tab.Settings },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        val modifier = Modifier
            .padding(padding)
            .fillMaxSize()

        when (tab) {
            Tab.Workout -> WorkoutScreen(
                modifier = modifier,
                vm = vm,
                autoStartTimer = autoStartTimer,
                defaultRestSec = defaultRestSec,
                onStartTimer = { secs -> startTimer(secs) }
            )

            Tab.Timer -> TimerScreen(
                modifier = modifier,
                running = timerRunning,
                remainingSec = timerRemainingSec,
                totalSec = timerTotalSec,
                onStart = { startTimer(timerRemainingSec.takeIf { it > 0 } ?: timerTotalSec) },
                onStop = { stopTimer() },
                onReset = { resetTimer() },
                onSet = { secs ->
                    timerTotalSec = secs.coerceAtLeast(0)
                    timerRemainingSec = secs.coerceAtLeast(0)
                    timerRunning = false
                }
            )

            Tab.History -> HistoryScreen(
                modifier = modifier
            )

            Tab.Settings -> SettingsScreen(
                modifier = modifier,
                autoStartTimer = autoStartTimer,
                onAutoStartTimerChange = { autoStartTimer = it },
                defaultRestSec = defaultRestSec,
                onDefaultRestSecChange = { defaultRestSec = it }
            )
        }
    }
}

/* --------------------------
   Workout Screen (DB-backed via repo)
--------------------------- */

@Composable
private fun WorkoutScreen(
    modifier: Modifier,
    vm: AppViewModel,
    autoStartTimer: Boolean,
    defaultRestSec: Int,
    onStartTimer: (Int) -> Unit
) {
    val activeSessionId by vm.activeSessionId.collectAsStateWithLifecycle()

    val sets by vm.observeActiveSessionSets()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val nameById by vm.exerciseNameById
        .collectAsStateWithLifecycle(initialValue = emptyMap())

    var exerciseName by rememberSaveable { mutableStateOf("") }
    var weight by rememberSaveable { mutableStateOf("") }
    var reps by rememberSaveable { mutableStateOf("") }
    var rpe by rememberSaveable { mutableStateOf("") }
    var restSec by rememberSaveable { mutableStateOf(defaultRestSec.toString()) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(defaultRestSec) {
        if (restSec.isBlank()) restSec = defaultRestSec.toString()
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (activeSessionId == null) "Session: (creating...)" else "Session: active",
                style = MaterialTheme.typography.titleMedium
            )

            OutlinedTextField(
                value = exerciseName,
                onValueChange = { exerciseName = it },
                label = { Text("Exercise") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it },
                    label = { Text("Kg") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = reps,
                    onValueChange = { reps = it },
                    label = { Text("Reps") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = rpe,
                    onValueChange = { rpe = it },
                    label = { Text("RPE") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = restSec,
                    onValueChange = { restSec = it },
                    label = { Text("Rest (sec)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )

                VoiceInputButton(
                    modifier = Modifier.align(Alignment.CenterVertically),
                    onHeard = { text ->
                        scope.launch { snackbarHostState.showSnackbar("Heard: $text") }

                        val parsed = parseWorkoutSpeech(text)
                        if (parsed.exercise.isNotBlank()) exerciseName = parsed.exercise
                        parsed.weightKg?.let { weight = stripTrailingZeros(it) }
                        parsed.reps?.let { reps = it.toString() }
                        parsed.rpe?.let { rpe = stripTrailingZeros(it) }
                    }
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val ex = exerciseName.trim()
                        if (ex.isBlank()) {
                            scope.launch { snackbarHostState.showSnackbar("Exercise is required") }
                            return@Button
                        }

                        val w = weight.toDoubleOrNull()
                        val rp = reps.toIntOrNull()
                        if (w == null || rp == null) {
                            scope.launch { snackbarHostState.showSnackbar("Weight and reps must be numeric") }
                            return@Button
                        }

                        val rpeVal = rpe.toDoubleOrNull()
                        val rest = restSec.toIntOrNull() ?: defaultRestSec

                        vm.addSet(
                            exerciseName = ex,
                            weightKg = w,
                            reps = rp,
                            rpe = rpeVal
                        )

                        if (autoStartTimer) onStartTimer(rest)

                        // Limpia reps/RPE, conserva ejercicio/peso
                        reps = ""
                        rpe = ""
                    }
                ) { Text("Add set") }

                OutlinedButton(
                    onClick = {
                        exerciseName = ""
                        weight = ""
                        reps = ""
                        rpe = ""
                        restSec = defaultRestSec.toString()
                    }
                ) { Text("Clear") }
            }

            HorizontalDivider()

            Text("Sets (active session)", style = MaterialTheme.typography.titleMedium)

            if (sets.isEmpty()) {
                Text("No sets yet.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sets, key = { it.setId }) { s ->
                        SetRow(
                            set = s,
                            exerciseName = nameById[s.exerciseId] ?: s.exerciseId
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SetRow(set: WorkoutSetEntity, exerciseName: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(exerciseName, style = MaterialTheme.typography.titleSmall)
                val rpeText = set.rpe?.let { " | RPE ${stripTrailingZeros(it)}" } ?: ""
                val wText = set.weightKg?.let { stripTrailingZeros(it) } ?: "-"
                Text("$wText kg × ${set.reps}$rpeText", style = MaterialTheme.typography.bodyMedium)
            }
            Text("#${set.setNumber}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/* --------------------------
   Timer Screen (shared)
--------------------------- */

@Composable
private fun TimerScreen(
    modifier: Modifier,
    running: Boolean,
    remainingSec: Int,
    totalSec: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
    onSet: (Int) -> Unit
) {
    var input by rememberSaveable { mutableStateOf(totalSec.takeIf { it > 0 }?.toString() ?: "120") }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Timer", style = MaterialTheme.typography.titleLarge)

        val mm = (remainingSec / 60).toString().padStart(2, '0')
        val ss = (remainingSec % 60).toString().padStart(2, '0')
        Text("$mm:$ss", style = MaterialTheme.typography.displayMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!running) Button(onClick = onStart) { Text("Start") }
            else Button(onClick = onStop) { Text("Stop") }

            OutlinedButton(onClick = onReset) { Text("Reset") }
        }

        HorizontalDivider()

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Set seconds") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = { input.toIntOrNull()?.let { onSet(it) } }
        ) { Text("Apply") }

        Spacer(Modifier.height(12.dp))
        Text("Total: ${totalSec}s", style = MaterialTheme.typography.bodySmall)
        Text("Running: $running", style = MaterialTheme.typography.bodySmall)
    }
}

/* --------------------------
   History Screen (placeholder estable)
--------------------------- */

@Composable
private fun HistoryScreen(modifier: Modifier) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("History", style = MaterialTheme.typography.titleLarge)
        Text(
            "History UI will be implemented next (requires a couple of extra DAO queries: " +
                    "observeAllSets() and day grouping).",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/* --------------------------
   Settings Screen
--------------------------- */

@Composable
private fun SettingsScreen(
    modifier: Modifier,
    autoStartTimer: Boolean,
    onAutoStartTimerChange: (Boolean) -> Unit,
    defaultRestSec: Int,
    onDefaultRestSecChange: (Int) -> Unit
) {
    var restInput by rememberSaveable { mutableStateOf(defaultRestSec.toString()) }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = autoStartTimer, onCheckedChange = onAutoStartTimerChange)
            Spacer(Modifier.width(12.dp))
            Text("Auto-start timer on Add set")
        }

        OutlinedTextField(
            value = restInput,
            onValueChange = { restInput = it },
            label = { Text("Default rest seconds") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Button(onClick = {
            restInput.toIntOrNull()?.let { onDefaultRestSecChange(it) }
        }) { Text("Apply") }

        HorizontalDivider()

        Text(
            "Note: Mic sensitivity in emulator/Windows is an external issue; app side is stable.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/* --------------------------
   VoiceInputButton (robusto)
--------------------------- */

@Composable
private fun VoiceInputButton(
    modifier: Modifier = Modifier,
    onHeard: (String) -> Unit
) {
    val context = LocalContext.current
    var pendingLaunch by remember { mutableStateOf(false) }
    var launchInProgress by remember { mutableStateOf(false) }

    // 1) Speech launcher primero (porque permission callback lo usa)
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        launchInProgress = false
        val data = res.data ?: return@rememberLauncherForActivityResult
        val results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val text = results?.firstOrNull()?.trim()
        if (!text.isNullOrBlank()) onHeard(text)
    }

    // 2) Permission launcher después
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingLaunch && !launchInProgress) {
            pendingLaunch = false
            launchInProgress = true
            speechLauncher.launch(buildSpeechIntent())
        } else {
            pendingLaunch = false
            launchInProgress = false
        }
    }

    IconButton(
        modifier = modifier,
        onClick = {
            if (launchInProgress) return@IconButton

            val hasPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPerm) {
                pendingLaunch = true
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return@IconButton
            }

            launchInProgress = true
            speechLauncher.launch(buildSpeechIntent())
        }
    ) {
        Icon(Icons.Default.Mic, contentDescription = "Voice input")
    }
}

private fun buildSpeechIntent(): Intent {
    return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)

        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1800L)
    }
}

/* --------------------------
   Parsing MVP (autofill)
--------------------------- */

private data class ParsedSpeech(
    val exercise: String,
    val weightKg: Double?,
    val reps: Int?,
    val rpe: Double?
)

private fun parseWorkoutSpeech(text: String): ParsedSpeech {
    val normalized = text
        .lowercase(Locale("es", "ES"))
        .replace(',', '.')
        .trim()

    val weightRegex = Regex("""(\d{1,3}(?:\.\d{1,3})?)\s*(kg|kilo|kilos)?""")
    val weight = weightRegex.find(normalized)?.groupValues?.getOrNull(1)?.toDoubleOrNull()

    val reps =
        Regex("""x\s*(\d{1,2})""").find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(\d{1,2})\s*(rep|reps|repeticiones)""").find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()

    val rpe =
        Regex("""rpe\s*(\d{1,2}(?:\.\d)?)""").find(normalized)?.groupValues?.getOrNull(1)?.toDoubleOrNull()

    val cutIdx = run {
        val idxWeight = weightRegex.find(normalized)?.range?.first ?: Int.MAX_VALUE
        val idxX = normalized.indexOf('x').takeIf { it >= 0 } ?: Int.MAX_VALUE
        minOf(idxWeight, idxX)
    }
    val exercise = normalized.substring(0, cutIdx.coerceAtMost(normalized.length)).trim()
        .replace(Regex("""\s+"""), " ")
        .ifBlank { normalized }

    return ParsedSpeech(
        exercise = exercise,
        weightKg = weight,
        reps = reps,
        rpe = rpe
    )
}

private fun stripTrailingZeros(value: Double): String {
    val i = value.roundToInt()
    return if (abs(value - i.toDouble()) < 0.0001) i.toString() else value.toString()
}

/* --------------------------
   ViewModel (adaptado a AppDb + WorkoutRepository)
--------------------------- */

private class AppViewModel(private val repo: WorkoutRepository) : ViewModel() {

    // Active session
    private val _activeSessionId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val activeSessionId: kotlinx.coroutines.flow.StateFlow<String?> = _activeSessionId

    // exerciseId -> name mapping (para pintar listas)
    val exerciseNameById: Flow<Map<String, String>> =
        repo.observeExercises()
            .map { list -> list.associate { it.exerciseId to it.name } }

    init {
        viewModelScope.launch {
            _activeSessionId.value = repo.getOrCreateActiveSessionId()
        }
    }

    fun observeActiveSessionSets(): Flow<List<WorkoutSetEntity>> {
        val sid = _activeSessionId.value
        return if (sid.isNullOrBlank()) flowOf(emptyList())
        else repo.observeSetsForSession(sid)
    }

    fun addSet(
        exerciseName: String,
        weightKg: Double?,
        reps: Int,
        rpe: Double?
    ) {
        viewModelScope.launch {
            val sid = _activeSessionId.value ?: repo.getOrCreateActiveSessionId().also { _activeSessionId.value = it }
            val exerciseId = repo.ensureExerciseIdByName(exerciseName)
            repo.addSet(
                sessionId = sid,
                exerciseId = exerciseId,
                reps = reps,
                weightKg = weightKg,
                rpe = rpe,
                source = "MANUAL"
            )
        }
    }
}

private class AppViewModelFactory(private val repo: WorkoutRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AppViewModel(repo) as T
    }
}

