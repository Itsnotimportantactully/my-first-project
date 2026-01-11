package com.example.appgym

import kotlinx.coroutines.flow.Flow

class WorkoutRepository(
    private val workoutDao: WorkoutDao,
    private val exerciseDao: ExerciseDao,
    private val timerDao: TimerDao,
    private val voiceDao: VoiceDao
) {

    // -------- Exercises --------

    fun observeExercises(): Flow<List<ExerciseEntity>> =
        exerciseDao.observeExercises()

    /**
     * Ensures an Exercise exists for the provided name and returns its exerciseId.
     * MVP rule: name normalized as stored name (trim).
     */
    suspend fun ensureExerciseIdByName(name: String): String {
        val clean = name.trim()
        if (clean.isBlank()) return "UNKNOWN"

        // 1) Try alias resolution
        val aliasNorm = norm(clean)
        val resolved = exerciseDao.resolveAlias(aliasNorm)
        if (resolved != null) return resolved

        // 2) Create new exercise and seed alias
        val id = uuid()
        exerciseDao.upsertExercise(
            ExerciseEntity(
                exerciseId = id,
                name = clean,
                isArchived = false
            )
        )
        exerciseDao.upsertAlias(
            ExerciseAliasEntity(
                aliasNorm = aliasNorm,
                exerciseId = id
            )
        )
        return id
    }

    // -------- Sessions --------

    suspend fun getOrCreateActiveSessionId(): String {
        val existing = workoutDao.getActiveSessionId()
        if (existing != null) return existing

        val id = uuid()
        workoutDao.upsertSession(
            WorkoutSessionEntity(
                sessionId = id,
                startedAt = System.currentTimeMillis(),
                endedAt = null
            )
        )
        return id
    }

    suspend fun endActiveSessionIfAny() {
        val id = workoutDao.getActiveSessionId() ?: return
        workoutDao.endSessionInternal(id, System.currentTimeMillis())
    }

    // -------- Sets --------

    fun observeSetsForSession(sessionId: String): Flow<List<WorkoutSetEntity>> =
        workoutDao.observeSetsForSession(sessionId)

    suspend fun addSet(
        sessionId: String,
        exerciseId: String,
        reps: Int,
        weightKg: Double? = null,
        rpe: Double? = null,
        source: String = "MANUAL"
    ) {
        val nextNum = workoutDao.maxSetNumber(sessionId, exerciseId) + 1
        workoutDao.insertSet(
            WorkoutSetEntity(
                setId = uuid(),
                sessionId = sessionId,
                exerciseId = exerciseId,
                setNumber = nextNum,
                weightKg = weightKg,
                reps = reps,
                rpe = rpe,
                createdAt = System.currentTimeMillis(),
                source = source
            )
        )
    }

    // -------- Timer --------

    suspend fun startRest(seconds: Int) {
        timerDao.startRest(seconds)
    }

    suspend fun stopRest() {
        timerDao.stop()
    }

    // -------- Voice logging --------

    suspend fun logVoiceRaw(text: String, locale: String, status: String, error: String? = null) {
        voiceDao.upsertRaw(
            VoiceRawEventEntity(
                voiceEventId = uuid(),
                at = System.currentTimeMillis(),
                locale = locale,
                rawText = text,
                status = status,
                error = error
            )
        )
    }
}
