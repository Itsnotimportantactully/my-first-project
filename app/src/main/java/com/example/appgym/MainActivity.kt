package com.example.appgym

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.room.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    val db = remember { AppGymDb.get(context.applicationContext) }
    val repo = remember { WorkoutRepository(db.workoutDao()) }
    val vm: AppViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = AppViewModelFactory(repo)
    )

    var tab by rememberSaveable { mutableStateOf(Tab.Workout) }

    // Shared timer state (single source of truth across tabs)
    var timerRunning by rememberSaveable { mutableStateOf(false) }
    var timerRemainingSec by rememberSaveable { mutableIntStateOf(0) }
    var timerTotalSec by rememberSaveable { mutableIntStateOf(0) }

    // Settings (kept simple; if later quieres persistirlo, se mete en DataStore)
    var autoStartTimer by rememberSaveable { mutableStateOf(true) }
    var defaultRestSec by rememberSaveable { mutableIntStateOf(120) }

    // Timer ticking (shared)
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
                modifier = modifier,
                vm = vm
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
   Workout Screen (DB = source of truth)
--------------------------- */

@Composable
private fun WorkoutScreen(
    modifier: Modifier,
    vm: AppViewModel,
    autoStartTimer: Boolean,
    defaultRestSec: Int,
    onStartTimer: (Int) -> Unit
) {
    val today = remember { todayIso() }
    val todaySets by vm.observeSetsByDate(today).collectAsStateWithLifecycle(initialValue = emptyList())

    var exercise by rememberSaveable { mutableStateOf("") }
    var weight by rememberSaveable { mutableStateOf("") }
    var reps by rememberSaveable { mutableStateOf("") }
    var rpe by rememberSaveable { mutableStateOf("") }
    var restSec by rememberSaveable { mutableStateOf(defaultRestSec.toString()) }

    var lastHeard by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // si el usuario cambia Settings, reflejamos rest por defecto al menos cuando vacío
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
            Text("Today: $today", style = MaterialTheme.typography.titleMedium)

            // Input row
            OutlinedTextField(
                value = exercise,
                onValueChange = { exercise = it },
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

                // Voz (Iteración 6 robusta): permiso + relaunch si concede
                VoiceInputButton(
                    modifier = Modifier.align(Alignment.CenterVertically),
                    onHeard = { text ->
                        lastHeard = text
                        scope.launch {
                            snackbarHostState.showSnackbar("Heard: $text")
                        }
                        // Parsing MVP (opción C no ahora, pero suficiente para auto-fill)
                        val parsed = parseWorkoutSpeech(text)
                        if (parsed.exercise.isNotBlank()) exercise = parsed.exercise
                        parsed.weightKg?.let { weight = stripTrailingZeros(it) }
                        parsed.reps?.let { reps = it.toString() }
                        parsed.rpe?.let { rpe = it.toString() }
                    }
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val ex = exercise.trim()
                        if (ex.isBlank()) {
                            scope.launch { snackbarHostState.showSnackbar("Exercise is required") }
                            return@Button
                        }
                        val w = weight.toFloatOrNull()
                        val rp = reps.toIntOrNull()
                        if (w == null || rp == null) {
                            scope.launch { snackbarHostState.showSnackbar("Weight and reps must be numeric") }
                            return@Button
                        }
                        val rpeInt = rpe.toIntOrNull()
                        val rest = restSec.toIntOrNull() ?: defaultRestSec

                        vm.addSet(
                            date = today,
                            exercise = ex,
                            weightKg = w,
                            reps = rp,
                            rpe = rpeInt
                        )

                        if (autoStartTimer) onStartTimer(rest)

                        // UX: limpia reps/RPE pero deja ejercicio y peso (conveniente)
                        reps = ""
                        rpe = ""
                    }
                ) { Text("Add set") }

                OutlinedButton(
                    onClick = {
                        exercise = ""
                        weight = ""
                        reps = ""
                        rpe = ""
                        restSec = defaultRestSec.toString()
                        lastHeard = null
                    }
                ) { Text("Clear") }
            }

            HorizontalDivider()

            Text("Sets", style = MaterialTheme.typography.titleMedium)

            if (todaySets.isEmpty()) {
                Text("No sets yet.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(todaySets, key = { it.id }) { s ->
                        SetRow(
                            set = s,
                            onDelete = { vm.deleteSet(s) }
                        )
                    }
                }
            }

            lastHeard?.let {
                Spacer(Modifier.height(4.dp))
                Text("Last heard: $it", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SetRow(set: WorkoutSetEntity, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(set.exercise, style = MaterialTheme.typography.titleSmall)
                val rpeText = set.rpe?.let { " | RPE $it" } ?: ""
                Text("${stripTrailingZeros(set.weightKg)} kg × ${set.reps}$rpeText", style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
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
            if (!running) {
                Button(onClick = onStart) { Text("Start") }
            } else {
                Button(onClick = onStop) { Text("Stop") }
            }
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
            onClick = {
                val secs = input.toIntOrNull()
                if (secs != null) onSet(secs)
            }
        ) { Text("Apply") }

        Spacer(Modifier.height(12.dp))
        Text("Total: ${totalSec}s", style = MaterialTheme.typography.bodySmall)
        Text("Running: $running", style = MaterialTheme.typography.bodySmall)
    }
}

/* --------------------------
   History Screen (useful, DB-backed)
--------------------------- */

private enum class HistoryRange(val label: String, val days: Int) {
    Last7("Last 7 days", 7),
    Last30("Last 30 days", 30),
    Last90("Last 90 days", 90),
    All("All", 3650)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryScreen(
    modifier: Modifier,
    vm: AppViewModel
) {
    var range by rememberSaveable { mutableStateOf(HistoryRange.Last30) }
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }

    val summaries by vm.observeDaySummaries(range.days).collectAsStateWithLifecycle(initialValue = emptyList())
    val setsForSelected by vm.observeSetsByDate(selectedDate ?: "").collectAsStateWithLifecycle(initialValue = emptyList())

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("History", style = MaterialTheme.typography.titleLarge)

        // Range selector
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = range.label,
                onValueChange = {},
                readOnly = true,
                label = { Text("Range") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                HistoryRange.entries.forEach { r ->
                    DropdownMenuItem(
                        text = { Text(r.label) },
                        onClick = {
                            range = r
                            expanded = false
                            selectedDate = null
                        }
                    )
                }
            }
        }

        HorizontalDivider()

        if (selectedDate == null) {
            if (summaries.isEmpty()) {
                Text("No history yet.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                    items(summaries, key = { it.date }) { d ->
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .clickable { selectedDate = d.date }
                        ) {
                            Row(
                                Modifier.padding(12.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.date, style = MaterialTheme.typography.titleSmall)
                                    Text("${d.setCount} sets", style = MaterialTheme.typography.bodySmall)
                                }
                                val vol = (d.volume ?: 0f)
                                Text("${stripTrailingZeros(vol)} kg·reps", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selectedDate = null }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Text(selectedDate!!, style = MaterialTheme.typography.titleMedium)
            }

            if (setsForSelected.isEmpty()) {
                Text("No sets for that day.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                    items(setsForSelected, key = { it.id }) { s ->
                        SetRow(set = s, onDelete = { vm.deleteSet(s) })
                    }
                }
            }
        }
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
            val v = restInput.toIntOrNull()
            if (v != null) onDefaultRestSecChange(v)
        }) { Text("Apply") }

        HorizontalDivider()

        Text(
            "Note: Mic sensitivity in emulator/Windows is an external issue; app side is stable.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/* --------------------------
   VoiceInputButton (Iteración 6 robusta)
--------------------------- */

@Composable
private fun VoiceInputButton(
    modifier: Modifier = Modifier,
    onHeard: (String) -> Unit
) {
    val context = LocalContext.current
    var pendingLaunch by remember { mutableStateOf(false) }
    var launchInProgress by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted && pendingLaunch && !launchInProgress) {
                pendingLaunch = false
                launchInProgress = true
                speechLauncher.launch(buildSpeechIntent())
            } else {
                pendingLaunch = false
                launchInProgress = false
            }
        }
    )

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { res ->
            launchInProgress = false
            val data = res.data ?: return@rememberLauncherForActivityResult
            val results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = results?.firstOrNull()?.trim()
            if (!text.isNullOrBlank()) onHeard(text)
        }
    )

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

        // Tolerancia a pausas / silencio (mejora UX, aunque en emulador puede ser irrelevante)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1800L)
    }
}

/* --------------------------
   Parsing MVP (suficiente para autofill hoy)
--------------------------- */

private data class ParsedSpeech(
    val exercise: String,
    val weightKg: Float?,
    val reps: Int?,
    val rpe: Int?
)

// Ejemplos que cubre razonablemente:
// "press banca 80 kilos 7 reps rpe 9"
// "banca 82,5 6 rpe 8"
// "sentadilla 120 x5 rpe9"
private fun parseWorkoutSpeech(text: String): ParsedSpeech {
    val normalized = text
        .lowercase(Locale("es", "ES"))
        .replace(',', '.')
        .trim()

    // Peso: primer número con posible decimal seguido opcionalmente de "kg/kilos"
    val weightRegex = Regex("""(\d{1,3}(?:\.\d{1,2})?)\s*(kg|kilo|kilos)?""")
    val weight = weightRegex.find(normalized)?.groupValues?.getOrNull(1)?.toFloatOrNull()

    // Reps: busca "x5" o "5 rep" o "5 reps" o "repeticiones 5"
    val reps =
        Regex("""x\s*(\d{1,2})""").find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(\d{1,2})\s*(rep|reps|repeticiones)""").find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()

    // RPE: "rpe 9" o "rpe9"
    val rpe =
        Regex("""rpe\s*(\d{1,2})""").find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()

    // Ejercicio: heurística: texto hasta el peso o hasta "x"
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

private fun stripTrailingZeros(value: Float): String {
    val i = value.roundToInt()
    return if (kotlin.math.abs(value - i.toFloat()) < 0.0001f) i.toString() else value.toString()
}

/* --------------------------
   Date helpers
--------------------------- */

private fun todayIso(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    return sdf.format(Date())
}

private fun isoDaysAgo(days: Int): String {
    val ms = System.currentTimeMillis() - (days.toLong() * 24L * 60L * 60L * 1000L)
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    return sdf.format(Date(ms))
}

/* --------------------------
   Room: Entity + DAO + DB
--------------------------- */

@Entity(tableName = "workout_sets")
data class WorkoutSetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(index = true) val date: String, // ISO yyyy-MM-dd (string sort OK)
    val exercise: String,
    val weightKg: Float,
    val reps: Int,
    val rpe: Int? = null,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class DaySummary(
    val date: String,
    val setCount: Int,
    val volume: Float?
)

@Dao
interface WorkoutDao {
    @Insert
    suspend fun insertSet(set: WorkoutSetEntity)

    @Delete
    suspend fun deleteSet(set: WorkoutSetEntity)

    @Query("SELECT * FROM workout_sets WHERE date = :date ORDER BY createdAtMs DESC")
    fun observeSetsByDate(date: String): Flow<List<WorkoutSetEntity>>

    @Query("""
        SELECT date as date,
               COUNT(*) as setCount,
               SUM(weightKg * reps) as volume
        FROM workout_sets
        WHERE date >= :fromDate
        GROUP BY date
        ORDER BY date DESC
    """)
    fun observeDaySummaries(fromDate: String): Flow<List<DaySummary>>
}

@Database(
    entities = [WorkoutSetEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppGymDb : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao

    companion object {
        @Volatile private var INSTANCE: AppGymDb? = null

        fun get(context: Context): AppGymDb {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context,
                    AppGymDb::class.java,
                    "appgym.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

/* --------------------------
   Repo + ViewModel
--------------------------- */

private class WorkoutRepository(private val dao: WorkoutDao) {
    fun observeSetsByDate(date: String): Flow<List<WorkoutSetEntity>> = dao.observeSetsByDate(date)
    fun observeDaySummaries(daysBack: Int): Flow<List<DaySummary>> {
        val from = isoDaysAgo(daysBack - 1)
        return dao.observeDaySummaries(from)
    }

    suspend fun addSet(date: String, exercise: String, weightKg: Float, reps: Int, rpe: Int?) {
        dao.insertSet(
            WorkoutSetEntity(
                date = date,
                exercise = exercise,
                weightKg = weightKg,
                reps = reps,
                rpe = rpe
            )
        )
    }

    suspend fun deleteSet(set: WorkoutSetEntity) = dao.deleteSet(set)
}

private class AppViewModel(private val repo: WorkoutRepository) : ViewModel() {
    fun observeSetsByDate(date: String): Flow<List<WorkoutSetEntity>> {
        if (date.isBlank()) {
            // Para evitar queries raras cuando selectedDate es null
            return kotlinx.coroutines.flow.flowOf(emptyList())
        }
        return repo.observeSetsByDate(date)
    }

    fun observeDaySummaries(daysBack: Int): Flow<List<DaySummary>> = repo.observeDaySummaries(daysBack)

    fun addSet(date: String, exercise: String, weightKg: Float, reps: Int, rpe: Int?) {
        viewModelScope.launch {
            repo.addSet(date, exercise, weightKg, reps, rpe)
        }
    }

    fun deleteSet(set: WorkoutSetEntity) {
        viewModelScope.launch {
            repo.deleteSet(set)
        }
    }
}

private class AppViewModelFactory(private val repo: WorkoutRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AppViewModel(repo) as T
    }
}
