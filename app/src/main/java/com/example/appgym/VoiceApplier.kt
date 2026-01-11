package com.example.appgym

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

sealed class VoiceIntent {
    data class LogSet(
        val exerciseText: String,
        val exerciseId: String?,
        val weightKg: Double?,
        val reps: Int?,
        val rpe: Double?
    ) : VoiceIntent()

    data class StartTimer(val seconds: Int) : VoiceIntent()
    data class Note(val text: String) : VoiceIntent()
    object OpenSession : VoiceIntent()
    object EndSession : VoiceIntent()
    object Unknown : VoiceIntent()
}

class VoiceApplier(
    private val exerciseDao: ExerciseDao,
    private val workoutDao: WorkoutDao,
    private val timerDao: TimerDao,
    private val voiceDao: VoiceDao
) {
    suspend fun ingestAndApply(rawText: String, locale: String): String = withContext(Dispatchers.IO) {
        val eventId = uuid()
        voiceDao.upsertRaw(
            VoiceRawEventEntity(
                voiceEventId = eventId,
                at = System.currentTimeMillis(),
                locale = locale,
                rawText = rawText,
                status = "RECEIVED"
            )
        )

        val intent = try {
            parse(rawText)
        } catch (e: Exception) {
            voiceDao.updateStatus(eventId, "REJECTED", e.message)
            return@withContext "Parse error: ${e.message}"
        }

        voiceDao.updateStatus(eventId, "PARSED", null)

        try {
            val res = applyIntent(intent)
            voiceDao.updateStatus(eventId, "APPLIED", null)
            res
        } catch (e: Exception) {
            voiceDao.updateStatus(eventId, "REJECTED", e.message)
            "Apply error: ${e.message}"
        }
    }

    private suspend fun applyIntent(intent: VoiceIntent): String {
        return when (intent) {
            VoiceIntent.OpenSession -> {
                if (workoutDao.getActiveSessionId() == null) {
                    workoutDao.upsertSession(
                        WorkoutSessionEntity(
                            sessionId = uuid(),
                            startedAt = System.currentTimeMillis(),
                            endedAt = null
                        )
                    )
                }
                "OK: session started"
            }

            VoiceIntent.EndSession -> {
                val id = workoutDao.getActiveSessionId() ?: return "No active session"
                workoutDao.endSessionInternal(id, System.currentTimeMillis())
                "OK: session ended"
            }

            is VoiceIntent.StartTimer -> {
                timerDao.startRest(intent.seconds)
                "OK: timer ${intent.seconds}s"
            }

            is VoiceIntent.Note -> {
                "OK: note (MVP no persistence): ${intent.text}"
            }

            is VoiceIntent.LogSet -> {
                val sessionId = workoutDao.getActiveSessionId() ?: run {
                    val newId = uuid()
                    workoutDao.upsertSession(
                        WorkoutSessionEntity(
                            sessionId = newId,
                            startedAt = System.currentTimeMillis(),
                            endedAt = null
                        )
                    )
                    newId
                }

                val exId = intent.exerciseId ?: resolveExerciseFallback(intent.exerciseText)
                require(exId != null) { "No reconozco ejercicio '${intent.exerciseText}'. Añade alias en Ejercicios." }

                val reps = intent.reps ?: error("Faltan reps. Ej: '7 reps'.")
                val nextSet = workoutDao.maxSetNumber(sessionId, exId) + 1

                workoutDao.insertSet(
                    WorkoutSetEntity(
                        setId = uuid(),
                        sessionId = sessionId,
                        exerciseId = exId,
                        setNumber = nextSet,
                        weightKg = intent.weightKg,
                        reps = reps,
                        rpe = intent.rpe,
                        createdAt = System.currentTimeMillis(),
                        source = "VOICE"
                    )
                )
                "OK: set logged (${intent.exerciseText})"
            }

            VoiceIntent.Unknown -> "UNKNOWN: no intent"
        }
    }

    private suspend fun resolveExerciseFallback(exText: String): String? {
        val q = "%${exText.take(20)}%"
        return exerciseDao.searchByName(q).firstOrNull()?.exerciseId
    }

    private suspend fun parse(raw: String): VoiceIntent {
        val t = norm(raw)

        if (t.contains("empieza entreno") || t.contains("inicia entreno") || t.contains("empezar entreno")) {
            return VoiceIntent.OpenSession
        }
        if (t.contains("termina entreno") || t.contains("finaliza entreno") || t.contains("acaba entreno")) {
            return VoiceIntent.EndSession
        }

        if (t.startsWith("nota:") || t.startsWith("apunta:")) {
            return VoiceIntent.Note(t.substringAfter(":").trim())
        }

        // Timer: "descanso 90" / "timer 1:30"
        Regex("""\btimer\s+(\d+):(\d+)\b""").find(t)?.let {
            val m = it.groupValues[1].toInt()
            val s = it.groupValues[2].toInt()
            return VoiceIntent.StartTimer(m * 60 + s)
        }
        Regex("""\bdescanso\s+(\d+)\b""").find(t)?.let {
            return VoiceIntent.StartTimer(it.groupValues[1].toInt())
        }
        Regex("""\bdescanso\s+(\d+)\s*(m|min|minutos)\b""").find(t)?.let {
            return VoiceIntent.StartTimer(it.groupValues[1].toInt() * 60)
        }

        val reps = Regex("""(\d+)\s*(reps|rep|repeticiones)\b""").find(t)?.groupValues?.get(1)?.toIntOrNull()
        val rpe = Regex("""\brpe\s*(\d+([.,]\d+)?)\b""").find(t)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        val weightKg = parseWeightKg(t)
        val exText = guessExerciseText(t)

        if (reps != null || rpe != null || weightKg != null) {
            val exId = exerciseDao.resolveAlias(norm(exText))
            return VoiceIntent.LogSet(exText, exId, weightKg, reps, rpe)
        }

        return VoiceIntent.Unknown
    }

    private fun parseWeightKg(t: String): Double? {
        Regex("""(\d+([.,]\d+)?)\s*(kg|kilos|kilo)\b""").find(t)?.let {
            return it.groupValues[1].replace(',', '.').toDoubleOrNull()
        }
        Regex("""(\d+([.,]\d+)?)\s*(lb|libras|libra)\b""").find(t)?.let {
            val lb = it.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val kg = lb * 0.45359237
            return ((kg * 100.0).roundToInt() / 100.0)
        }
        return null
    }

    private fun guessExerciseText(t: String): String {
        return t
            .replace(Regex("""\b(\d+([.,]\d+)?)\s*(kg|kilos|kilo|lb|libras|libra)\b"""), " ")
            .replace(Regex("""\b(\d+)\s*(reps|rep|repeticiones)\b"""), " ")
            .replace(Regex("""\brpe\s*\d+([.,]\d+)?\b"""), " ")
            .replace(Regex("""\b(con|a|de|del|la|el|y|acabo|hacer|hice|hecho)\b"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")
    }
}
