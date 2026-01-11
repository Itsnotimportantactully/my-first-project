package com.example.appgym

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val db = remember { AppDb.get(ctx) }
    val nav = rememberNavController()

    // Init/seed en IO (evita tocar DB en UI)
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            ensureSeeded(db)
            val existing = db.timerDao().getActive()
            if (existing == null) db.timerDao().upsertActive(ActiveTimerStateEntity())
        }
    }

    MaterialTheme {
        NavHost(navController = nav, startDestination = "home") {
            composable("home") { HomeScreen(db, onNav = { nav.navigate(it) }) }
            composable("session") { SessionScreen(db) }
            composable("exercises") { ExercisesScreen(db) }
            composable("timer") { TimerScreen(db) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(db: AppDb, onNav: (String) -> Unit) {
    val ctx = LocalContext.current

    var manualText by remember { mutableStateOf("") }
    var lastVoice by remember { mutableStateOf<String?>(null) }
    var lastResult by remember { mutableStateOf<String?>(null) }

    val applier = remember {
        VoiceApplier(
            exerciseDao = db.exerciseDao(),
            workoutDao = db.workoutDao(),
            timerDao = db.timerDao(),
            voiceDao = db.voiceDao()
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("AppGym") }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onNav("session") }) { Text("Sesión") }
                Button(onClick = { onNav("timer") }) { Text("Timer") }
                OutlinedButton(onClick = { onNav("exercises") }) { Text("Ejercicios") }
            }

            Divider()

            // Plan A: Voz (en emulador puede fallar)
            Button(onClick = {
                startSpeech(
                    context = ctx,
                    onText = { txt ->
                        lastVoice = txt
                        // aplica en IO internamente
                        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {                            val res = applier.ingestAndApply(txt, "es-ES")
                            withContext(Dispatchers.Main) { lastResult = res }
                        }
                    },
                    onError = { err -> lastResult = "Speech error: $err" }
                )
            }) { Text("Voz (micro)") }

            // Plan B: Texto manual (fiable)
            OutlinedTextField(
                value = manualText,
                onValueChange = { manualText = it },
                label = { Text("Texto (Plan B)") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = {
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {                    val res = applier.ingestAndApply(manualText, "es-ES")
                    withContext(Dispatchers.Main) { lastResult = res }
                }
            }) { Text("Aplicar texto") }

            if (lastVoice != null) Text("Último dictado: $lastVoice")
            if (lastResult != null) Text("Resultado: $lastResult")

            Text(
                "Ejemplos:\n" +
                        "• press banca 80 kilos 7 reps rpe 9\n" +
                        "• descanso 90\n" +
                        "• nota: hoy dormi poco\n" +
                        "• empieza entreno / termina entreno"
            )
        }
    }
}

private fun startSpeech(
    context: Context,
    onText: (String) -> Unit,
    onError: (String) -> Unit
) {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
        onError("SpeechRecognizer no disponible (emulador: normal).")
        return
    }
    val sr = SpeechRecognizer.createSpeechRecognizer(context)
    sr.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) { sr.destroy(); onError(error.toString()) }
        override fun onResults(results: Bundle?) {
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val txt = texts?.firstOrNull()
            sr.destroy()
            if (txt != null) onText(txt) else onError("sin texto")
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    })

    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
    sr.startListening(intent)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(db: AppDb) {
    var sessionId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { sessionId = db.workoutDao().getActiveSessionId() }

    val setsState = remember(sessionId) {
        sessionId?.let { db.workoutDao().observeSetsForSession(it) }
    }?.collectAsState(initial = emptyList())

    Scaffold(topBar = { TopAppBar(title = { Text("Sesión") }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {                        if (db.workoutDao().getActiveSessionId() == null) {
                            val id = uuid()
                            db.workoutDao().upsertSession(
                                WorkoutSessionEntity(
                                    sessionId = id,
                                    startedAt = System.currentTimeMillis(),
                                    endedAt = null
                                )
                            )
                            withContext(Dispatchers.Main) { sessionId = id }
                        }
                    }
                }) { Text("Iniciar sesión") }

                OutlinedButton(onClick = {
                    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {                        val id = db.workoutDao().getActiveSessionId()
                        if (id != null) {
                            db.workoutDao().endSessionInternal(id, System.currentTimeMillis())
                            withContext(Dispatchers.Main) { sessionId = null }
                        }
                    }
                }) { Text("Terminar") }
            }

            Text("Sesión activa: ${sessionId ?: "ninguna"}")

            if (setsState == null) {
                Text("No hay sets (no hay sesión activa).")
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f, true),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(setsState.value) { s ->
                        ElevatedCard {
                            Column(Modifier.padding(12.dp)) {
                                Text("Set ${s.setNumber}: reps=${s.reps} kg=${s.weightKg ?: "-"} RPE=${s.rpe ?: "-"}")
                                Text("exerciseId=${s.exerciseId} src=${s.source}")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisesScreen(db: AppDb) {
    val exercises by db.exerciseDao().observeExercises().collectAsState(initial = emptyList())

    var newName by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("Ejercicios") }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("Nuevo ejercicio") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = {
                val name = newName.trim()
                if (name.isBlank()) return@Button
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {                    val id = uuid()
                    db.exerciseDao().upsertExercise(ExerciseEntity(exerciseId = id, name = name))
                    db.exerciseDao().upsertAlias(ExerciseAliasEntity(aliasNorm = norm(name), exerciseId = id))
                    withContext(Dispatchers.Main) {
                        selectedId = id
                        newName = ""
                    }
                }
            }) { Text("Crear") }

            Divider()
            Text("Selecciona un ejercicio para añadir alias:")

            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, true),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(exercises) { e ->
                    ElevatedCard(onClick = { selectedId = e.exerciseId }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(e.name)
                            if (selectedId == e.exerciseId) Text("SELECCIONADO")
                        }
                    }
                }
            }

            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it },
                label = { Text("Alias (ej.: banca / bench / press banca)") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(enabled = selectedId != null, onClick = {
                val a = alias.trim()
                val id = selectedId ?: return@Button
                if (a.isBlank()) return@Button
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {                    db.exerciseDao().upsertAlias(ExerciseAliasEntity(aliasNorm = norm(a), exerciseId = id))
                    withContext(Dispatchers.Main) { alias = "" }
                }
            }) { Text("Añadir alias") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerScreen(db: AppDb) {
    var state by remember { mutableStateOf<ActiveTimerStateEntity?>(null) }
    var remainingMs by remember { mutableStateOf<Long?>(null) }

    suspend fun reload() { state = db.timerDao().getActive() }

    fun computeRemaining(s: ActiveTimerStateEntity): Long? {
        val target = s.targetMs ?: return null
        val startedAt = s.startedAt ?: return null
        val elapsed = (System.currentTimeMillis() - startedAt) - s.accumulatedPausedMs
        return max(0L, target - elapsed)
    }

    LaunchedEffect(Unit) { reload() }

    LaunchedEffect(state?.isRunning, state?.startedAt, state?.targetMs, state?.accumulatedPausedMs) {
        while (true) {
            val s = state
            if (s != null && s.isRunning) {
                remainingMs = computeRemaining(s)
                if ((remainingMs ?: 1L) == 0L) {
                    db.timerDao().stop()
                    reload()
                }
            } else {
                remainingMs = null
            }
            delay(250)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Timer") }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Estado: ${if (state?.isRunning == true) "RUNNING" else "STOP"}")
            Text("Remaining: ${remainingMs?.let { msToMmSs(it) } ?: "-"}")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {                        db.timerDao().startRest(90)
                        withContext(Dispatchers.Main) { reload() }
                    }
                }) { Text("Start 90s") }

                OutlinedButton(onClick = {
                    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {                        db.timerDao().stop()
                        withContext(Dispatchers.Main) { reload() }
                    }
                }) { Text("Stop") }
            }

            Divider()
            Text("Desde voz/manual: \"descanso 90\" o \"timer 1:30\".")
        }
    }
}

private fun msToMmSs(ms: Long): String {
    val sec = ms / 1000
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}

// Seed mínimo e idempotente
private suspend fun ensureSeeded(db: AppDb) {
    val dao = db.exerciseDao()
    val existing = dao.listExercises()
    if (existing.isNotEmpty()) return

    val bench = ExerciseEntity("ex_bench", "Press banca")
    val squat = ExerciseEntity("ex_squat", "Sentadilla")
    val dead  = ExerciseEntity("ex_dead", "Peso muerto")

    dao.upsertExercise(bench)
    dao.upsertExercise(squat)
    dao.upsertExercise(dead)

    listOf("press banca", "banca", "bench", "bench press").forEach {
        dao.upsertAlias(ExerciseAliasEntity(norm(it), bench.exerciseId))
    }
    listOf("sentadilla", "squat").forEach {
        dao.upsertAlias(ExerciseAliasEntity(norm(it), squat.exerciseId))
    }
    listOf("peso muerto", "deadlift").forEach {
        dao.upsertAlias(ExerciseAliasEntity(norm(it), dead.exerciseId))
    }
}

