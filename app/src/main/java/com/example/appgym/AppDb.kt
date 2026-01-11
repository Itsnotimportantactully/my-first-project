package com.example.appgym

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.text.Normalizer
import java.util.UUID

// -------------------- ENTITIES --------------------

@Entity(indices = [Index("name")])
data class ExerciseEntity(
    @PrimaryKey val exerciseId: String,
    val name: String,
    val isArchived: Boolean = false
)

@Entity(
    primaryKeys = ["aliasNorm"],
    indices = [Index("exerciseId")]
)
data class ExerciseAliasEntity(
    val aliasNorm: String,
    val exerciseId: String
)

@Entity(indices = [Index("startedAt")])
data class WorkoutSessionEntity(
    @PrimaryKey val sessionId: String,
    val startedAt: Long,
    val endedAt: Long? = null
)

@Entity(
    indices = [Index(value = ["sessionId", "createdAt"]), Index(value = ["sessionId", "exerciseId"])]
)
data class WorkoutSetEntity(
    @PrimaryKey val setId: String,
    val sessionId: String,
    val exerciseId: String,
    val setNumber: Int,
    val weightKg: Double? = null,
    val reps: Int,
    val rpe: Double? = null,
    val createdAt: Long,
    val source: String // MANUAL / VOICE
)

@Entity(indices = [Index("at")])
data class VoiceRawEventEntity(
    @PrimaryKey val voiceEventId: String,
    val at: Long,
    val locale: String,
    val rawText: String,
    val status: String, // RECEIVED / PARSED / APPLIED / REJECTED
    val error: String? = null
)

@Entity
data class ActiveTimerStateEntity(
    @PrimaryKey val id: Int = 1,
    val startedAt: Long? = null,
    val accumulatedPausedMs: Long = 0L,
    val targetMs: Long? = null,
    val isRunning: Boolean = false
)

// -------------------- DAOs --------------------

@Dao
interface ExerciseDao {
    @Query("SELECT * FROM ExerciseEntity WHERE isArchived = 0 ORDER BY name")
    fun observeExercises(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM ExerciseEntity WHERE isArchived = 0 ORDER BY name")
    suspend fun listExercises(): List<ExerciseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExercise(e: ExerciseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlias(a: ExerciseAliasEntity)

    @Query("SELECT exerciseId FROM ExerciseAliasEntity WHERE aliasNorm = :aliasNorm LIMIT 1")
    suspend fun resolveAlias(aliasNorm: String): String?

    @Query("SELECT * FROM ExerciseEntity WHERE name LIKE :q ORDER BY name LIMIT 10")
    suspend fun searchByName(q: String): List<ExerciseEntity>
}

@Dao
interface WorkoutDao {
    @Query("SELECT sessionId FROM WorkoutSessionEntity WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActiveSessionId(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(s: WorkoutSessionEntity)

    @Query("UPDATE WorkoutSessionEntity SET endedAt = :endedAt WHERE sessionId = :id")
    suspend fun endSessionInternal(id: String, endedAt: Long)

    @Query("""
        SELECT * FROM WorkoutSetEntity
        WHERE sessionId = :sessionId
        ORDER BY createdAt ASC
    """)
    fun observeSetsForSession(sessionId: String): Flow<List<WorkoutSetEntity>>

    @Query("""
        SELECT COALESCE(MAX(setNumber), 0)
        FROM WorkoutSetEntity
        WHERE sessionId = :sessionId AND exerciseId = :exerciseId
    """)
    suspend fun maxSetNumber(sessionId: String, exerciseId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSet(s: WorkoutSetEntity)
}

@Dao
interface VoiceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRaw(e: VoiceRawEventEntity)

    @Query("UPDATE VoiceRawEventEntity SET status = :status, error = :error WHERE voiceEventId = :id")
    suspend fun updateStatus(id: String, status: String, error: String? = null)
}

@Dao
interface TimerDao {
    @Query("SELECT * FROM ActiveTimerStateEntity WHERE id = 1 LIMIT 1")
    suspend fun getActive(): ActiveTimerStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertActive(s: ActiveTimerStateEntity)

    @Transaction
    suspend fun startRest(seconds: Int) {
        upsertActive(
            ActiveTimerStateEntity(
                id = 1,
                startedAt = System.currentTimeMillis(),
                accumulatedPausedMs = 0L,
                targetMs = seconds * 1000L,
                isRunning = true
            )
        )
    }

    @Transaction
    suspend fun stop() {
        upsertActive(
            ActiveTimerStateEntity(
                id = 1,
                startedAt = null,
                accumulatedPausedMs = 0L,
                targetMs = null,
                isRunning = false
            )
        )
    }
}

// -------------------- DB --------------------

@Database(
    entities = [
        ExerciseEntity::class,
        ExerciseAliasEntity::class,
        WorkoutSessionEntity::class,
        WorkoutSetEntity::class,
        VoiceRawEventEntity::class,
        ActiveTimerStateEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun voiceDao(): VoiceDao
    abstract fun timerDao(): TimerDao

    companion object {
        @Volatile private var INSTANCE: AppDb? = null

        fun get(context: Context): AppDb =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDb::class.java,
                    "appgym.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}

// -------------------- UTILS --------------------

fun uuid(): String = UUID.randomUUID().toString()

fun norm(s: String): String =
    Normalizer.normalize(s.trim().lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .replace(Regex("\\s+"), " ")
