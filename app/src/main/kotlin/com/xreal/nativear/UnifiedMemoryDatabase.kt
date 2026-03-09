package com.xreal.nativear

import android.content.ContentValues
import android.content.Context
import android.util.Log
import com.xreal.nativear.core.ErrorReporter
import com.xreal.nativear.core.ErrorSeverity
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import io.requery.android.database.sqlite.SQLiteCustomExtension
import io.requery.android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UnifiedMemoryDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val TAG = "UnifiedMemoryDB"
    private val nativeLibDir = context.applicationInfo.nativeLibraryDir

    data class DailyValueReport(
        val id: Long = 0,
        val reportDate: String,           // "2026-03-04"
        val expertConsultations: Int = 0,
        val dataDecisions: Int = 0,
        val memoriesReferenced: Int = 0,
        val goalProgressPct: Float = 0f,
        val feedbackScore: Float = -1f,   // -1 = 미입력
        val tokenCostKrw: Float = 0f,
        val valuePerTokenKrw: Float = 0f,
        val aiSummary: String? = null,
        val createdAt: Long = System.currentTimeMillis()
    )

    data class StructuredDataRecord(
        val id: Long = 0,
        val domain: String,
        val dataKey: String,
        val value: String,
        val tags: String? = null,
        val createdAt: Long,
        val updatedAt: Long,
        val syncedAt: Long? = null
    )

    data class MemoryNode(
        val id: Long = 0,
        val timestamp: Long,
        val role: String, // USER, GEMINI, CAMERA, WHISPER, SYSTEM_SUMMARY, PERSONA_SUMMARY
        val content: String,
        val level: Int = 0,
        val parentId: Long? = null,
        val embedding: ByteArray? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val metadata: String? = null, // JSON string
        val personaId: String? = null, // AI persona identifier
        val sessionId: String? = null,  // ★ Phase C: LifeSession ID
        val importanceScore: Float = 0.5f  // ★ Phase I: 규칙 기반 중요도 (0.0~1.0)
    )

    // ★ Phase J: 멀티-AI 세션 기록 (가치 마이닝)
    data class MultiAISession(
        val id: Long = 0,
        val sessionDate: String,            // "2026-03-04"
        val queryText: String,
        val personaIds: String,             // JSON 배열: ["vision_analyst","safety_monitor"]
        val consensusLevel: Float = 0f,
        val synthesisMode: String,          // HIGH_CONSENSUS|MID_CONSENSUS|COORDINATOR|DISAGREEMENT
        val synthesizedText: String? = null,
        val wasHelpful: Int = -1,           // -1=미응답, 1=긍정, 0=부정
        val totalLatencyMs: Long = 0,
        val tokenCostUsd: Float = 0f,
        val timestamp: Long = System.currentTimeMillis()
    )

    // ★ Phase J: 페르소나 조합 통계 (getMultiAISessionStats DAO 반환값)
    data class PersonaCompositionStat(
        val personaIds: String,             // JSON 배열
        val totalSessions: Int,
        val helpfulRate: Float,             // -1 = 피드백 없음
        val avgConsensus: Float,
        val avgLatencyMs: Long
    )

    companion object {
        private const val DATABASE_NAME = "unified_memory_v3.db"
        private const val DATABASE_VERSION = 21
        private const val TABLE_MEMORY = "memory_nodes"
        private const val TABLE_STRUCTURED_DATA = "structured_data"
        private const val TABLE_USER_TODOS = "user_todos"
        private const val TABLE_SCHEDULE_BLOCKS = "schedule_blocks"
        private const val TABLE_DELIBERATION_SESSIONS = "deliberation_sessions"
        private const val TABLE_GOALS = "user_goals"
        private const val TABLE_GOAL_PROGRESS = "goal_progress"

        private const val COLUMN_ID = "id"
        private const val COLUMN_TIMESTAMP = "timestamp"
        private const val COLUMN_ROLE = "role"
        private const val COLUMN_CONTENT = "content"
        private const val COLUMN_LEVEL = "level"
        private const val COLUMN_PARENT_ID = "parent_id"
        private const val COLUMN_EMBEDDING = "embedding"
        private const val COLUMN_LATITUDE = "latitude"
        private const val COLUMN_LONGITUDE = "longitude"
        private const val COLUMN_METADATA = "metadata"
        private const val COLUMN_PERSONA_ID = "persona_id"
        private const val COLUMN_SESSION_ID = "session_id"
        private const val COLUMN_IMPORTANCE_SCORE = "importance_score"
        private const val TABLE_LIFE_SESSIONS = "life_sessions"
        private const val TABLE_DAILY_VALUE_REPORTS = "daily_value_reports"

        private const val TEXT_EMBEDDING_DIM = 100
        private const val VEC_TABLE_TEXT = "vec_memory_text"

        fun floatArrayToBlob(floats: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in floats) buffer.putFloat(f)
            return buffer.array()
        }

        fun blobToFloatArray(bytes: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val floats = FloatArray(bytes.size / 4)
            for (i in floats.indices) floats[i] = buffer.getFloat()
            return floats
        }
    }

    override fun createConfiguration(path: String, openFlags: Int): SQLiteDatabaseConfiguration {
        val config = super.createConfiguration(path, openFlags)
        config.customExtensions.add(
            SQLiteCustomExtension("$nativeLibDir/libvec0", "sqlite3_vec_init")
        )
        Log.i(TAG, "sqlite-vec extension configured from $nativeLibDir/libvec0")
        return config
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) {
            try {
                // io.requery: PRAGMA가 결과를 반환하므로 execSQL 대신 rawQuery 사용
                db.rawQuery("PRAGMA journal_mode=WAL", null)?.close()
                db.rawQuery("PRAGMA synchronous=NORMAL", null)?.close()
                Log.i(TAG, "SQLite WAL mode enabled")
            } catch (e: Exception) {
                Log.w(TAG, "SQLite PRAGMA 설정 실패 (비치명적): ${e.message}")
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = ("CREATE TABLE $TABLE_MEMORY (" +
                "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$COLUMN_TIMESTAMP LONG, " +
                "$COLUMN_ROLE TEXT, " +
                "$COLUMN_CONTENT TEXT, " +
                "$COLUMN_LEVEL INTEGER DEFAULT 0, " +
                "$COLUMN_PARENT_ID INTEGER, " +
                "$COLUMN_EMBEDDING BLOB, " +
                "$COLUMN_LATITUDE DOUBLE, " +
                "$COLUMN_LONGITUDE DOUBLE, " +
                "$COLUMN_METADATA TEXT, " +
                "$COLUMN_PERSONA_ID TEXT, " +
                "$COLUMN_SESSION_ID TEXT, " +
                "$COLUMN_IMPORTANCE_SCORE REAL DEFAULT 0.5)")
        db.execSQL(createTable)

        // Index for faster searching by level and parent
        db.execSQL("CREATE INDEX idx_level_parent ON $TABLE_MEMORY ($COLUMN_LEVEL, $COLUMN_PARENT_ID)")
        db.execSQL("CREATE INDEX idx_timestamp ON $TABLE_MEMORY ($COLUMN_TIMESTAMP)")
        db.execSQL("CREATE INDEX idx_persona_id ON $TABLE_MEMORY ($COLUMN_PERSONA_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_session_id ON $TABLE_MEMORY ($COLUMN_SESSION_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_importance_score ON $TABLE_MEMORY ($COLUMN_IMPORTANCE_SCORE)")
        createLifeSessionsTable(db)

        // Structured data table for AI agents
        createStructuredDataTable(db)

        // Todo and Schedule tables (Phase 3)
        createPlanTables(db)

        // Deliberation sessions table (Phase 5)
        createDeliberationTable(db)

        // Goal tracking tables (Phase 6)
        createGoalTables(db)

        // Outcome tracking tables (Phase 7)
        createOutcomeTables(db)

        // AI activity log table (Phase 9)
        createAIActivityTable(db)

        // Capability requests table (Phase 11)
        createCapabilityTable(db)

        // Familiarity engine tables (Phase 12)
        createFamiliarityTables(db)

        // Relationship intelligence tables (Phase 13)
        createRelationshipTables(db)

        // Agent personality evolution tables (Phase 16)
        createAgentTables(db)

        // Cross-table integrity indexes (Fix 3)
        createIntegrityIndexes(db)

        // Analytics tables (Phase 17: SystemAnalyticsService)
        createAnalyticsTables(db)

        // ★ Error logging table (Phase 18: SystemEvent.Error 에러 추적 파이프라인)
        createErrorLogsTable(db)

        // ★ Expert Evolution tables (Phase 19: 전문가 자기진화 시스템)
        createExpertEvolutionTables(db)

        // Vector search table for text embeddings
        createVecTables(db)

        // ★ Phase H: 일일 가치 리포트 테이블
        createDailyValueReportsTable(db)

        // ★ Phase J: 멀티-AI 세션 가치 마이닝 테이블
        createMultiAISessionsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_timestamp ON $TABLE_MEMORY ($COLUMN_TIMESTAMP)")
            createVecTables(db)
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $TABLE_MEMORY ADD COLUMN $COLUMN_PERSONA_ID TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_persona_id ON $TABLE_MEMORY ($COLUMN_PERSONA_ID)")
        }
        if (oldVersion < 4) {
            createStructuredDataTable(db)
        }
        if (oldVersion < 5) {
            createPlanTables(db)
        }
        if (oldVersion < 6) {
            createDeliberationTable(db)
        }
        if (oldVersion < 7) {
            createGoalTables(db)
        }
        if (oldVersion < 8) {
            createOutcomeTables(db)
        }
        if (oldVersion < 9) {
            createAIActivityTable(db)
        }
        if (oldVersion < 10) {
            createCapabilityTable(db)
        }
        if (oldVersion < 11) {
            createFamiliarityTables(db)
        }
        if (oldVersion < 12) {
            createRelationshipTables(db)
        }
        if (oldVersion < 13) {
            createAgentTables(db)
        }
        if (oldVersion < 14) {
            createIntegrityIndexes(db)
        }
        if (oldVersion < 15) {
            createAnalyticsTables(db)
        }
        if (oldVersion < 16) {
            createErrorLogsTable(db)
        }
        if (oldVersion < 17) {
            createExpertEvolutionTables(db)
        }
        if (oldVersion < 18) {
            // ★ Phase C: 세션 시스템 — life_sessions 테이블 + memory_nodes session_id 컬럼
            createLifeSessionsTable(db)
            db.execSQL("ALTER TABLE $TABLE_MEMORY ADD COLUMN $COLUMN_SESSION_ID TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_session_id ON $TABLE_MEMORY ($COLUMN_SESSION_ID)")
            try {
                db.execSQL("ALTER TABLE decision_log ADD COLUMN session_id TEXT")
            } catch (e: Exception) {
                Log.w(TAG, "decision_log session_id already exists or failed: ${e.message}")
            }
            Log.i(TAG, "v18: life_sessions + memory_nodes.session_id created")
        }
        if (oldVersion < 19) {
            // ★ Phase H: 일일 가치 리포트 테이블
            createDailyValueReportsTable(db)
            Log.i(TAG, "v19: daily_value_reports created")
        }
        if (oldVersion < 20) {
            // ★ Phase I: 메모리 중요도 컬럼 추가
            db.execSQL("ALTER TABLE $TABLE_MEMORY ADD COLUMN $COLUMN_IMPORTANCE_SCORE REAL DEFAULT 0.5")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_importance_score ON $TABLE_MEMORY ($COLUMN_IMPORTANCE_SCORE)")
            Log.i(TAG, "v20: memory_nodes.importance_score 컬럼 추가")
        }
        if (oldVersion < 21) {
            // ★ Phase J: 멀티-AI 세션 가치 마이닝 테이블
            createMultiAISessionsTable(db)
            Log.i(TAG, "v21: multi_ai_sessions created")
        }
    }

    private fun createVecTables(db: SQLiteDatabase) {
        try {
            db.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS $VEC_TABLE_TEXT USING vec0(
                    memory_id INTEGER PRIMARY KEY,
                    text_embedding FLOAT[$TEXT_EMBEDDING_DIM] distance_metric=cosine
                )
            """.trimIndent())
            Log.i(TAG, "vec_memory_text table created (dim=$TEXT_EMBEDDING_DIM)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create vec table: ${e.message}", e)
        }
    }

    // ==================== Plan Tables (Phase 3) ====================

    private fun createPlanTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_USER_TODOS (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                description TEXT,
                priority INTEGER DEFAULT 2,
                deadline INTEGER,
                status INTEGER DEFAULT 0,
                category TEXT,
                assigned_expert TEXT,
                created_by TEXT,
                parent_goal_id TEXT,
                context_tags TEXT,
                recurrence TEXT,
                completed_at INTEGER,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_todo_status ON $TABLE_USER_TODOS (status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_todo_priority ON $TABLE_USER_TODOS (priority)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_todo_deadline ON $TABLE_USER_TODOS (deadline)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_SCHEDULE_BLOCKS (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                start_time INTEGER NOT NULL,
                end_time INTEGER NOT NULL,
                type INTEGER DEFAULT 1,
                linked_todo_ids TEXT,
                reminder INTEGER,
                notes TEXT,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_schedule_start ON $TABLE_SCHEDULE_BLOCKS (start_time)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_schedule_end ON $TABLE_SCHEDULE_BLOCKS (end_time)")
        Log.i(TAG, "Plan tables created (user_todos, schedule_blocks)")
    }

    // ==================== Deliberation Table (Phase 5) ====================

    private fun createDeliberationTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_DELIBERATION_SESSIONS (
                id TEXT PRIMARY KEY,
                topic TEXT NOT NULL,
                situation TEXT,
                participants TEXT,
                proposals TEXT,
                votes TEXT,
                decision TEXT,
                status INTEGER DEFAULT 0,
                context_summary TEXT,
                started_at INTEGER NOT NULL,
                ended_at INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_delib_situation ON $TABLE_DELIBERATION_SESSIONS (situation)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_delib_timestamp ON $TABLE_DELIBERATION_SESSIONS (started_at)")
        Log.i(TAG, "Deliberation sessions table created")
    }

    // ==================== Goal Tables (Phase 6) ====================

    private fun createGoalTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_GOALS (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                description TEXT,
                timeframe INTEGER DEFAULT 0,
                parent_goal_id TEXT,
                target_value REAL,
                current_value REAL DEFAULT 0,
                unit TEXT,
                status INTEGER DEFAULT 0,
                assigned_domain TEXT,
                category INTEGER DEFAULT 8,
                created_at INTEGER NOT NULL,
                deadline INTEGER,
                completed_at INTEGER,
                streak_days INTEGER DEFAULT 0,
                last_progress_at INTEGER,
                milestones TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_goal_status ON $TABLE_GOALS (status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_goal_timeframe ON $TABLE_GOALS (timeframe)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_goal_parent ON $TABLE_GOALS (parent_goal_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_goal_category ON $TABLE_GOALS (category)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_GOAL_PROGRESS (
                id TEXT PRIMARY KEY,
                goal_id TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                previous_value REAL DEFAULT 0,
                new_value REAL DEFAULT 0,
                delta REAL DEFAULT 0,
                source TEXT,
                notes TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gp_goal ON $TABLE_GOAL_PROGRESS (goal_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gp_timestamp ON $TABLE_GOAL_PROGRESS (timestamp)")
        Log.i(TAG, "Goal tables created (user_goals, goal_progress)")
    }

    // ==================== Outcome Tables (Phase 7) ====================

    private fun createOutcomeTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ai_interventions (
                id TEXT PRIMARY KEY,
                expert_id TEXT NOT NULL,
                situation TEXT,
                action TEXT NOT NULL,
                context_summary TEXT,
                timestamp INTEGER NOT NULL,
                outcome INTEGER DEFAULT 0,
                outcome_timestamp INTEGER,
                satisfaction REAL,
                notes TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_interv_expert ON ai_interventions (expert_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_interv_situation ON ai_interventions (situation)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_interv_timestamp ON ai_interventions (timestamp)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS strategy_records (
                id TEXT PRIMARY KEY,
                expert_id TEXT NOT NULL,
                situation TEXT,
                action_summary TEXT,
                success_count INTEGER DEFAULT 0,
                total_count INTEGER DEFAULT 0,
                effectiveness REAL DEFAULT 0,
                avg_satisfaction REAL DEFAULT 0,
                first_used_at INTEGER,
                last_used_at INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_strat_expert ON strategy_records (expert_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_strat_situation ON strategy_records (situation)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_strat_effectiveness ON strategy_records (effectiveness)")
        Log.i(TAG, "Outcome tables created (ai_interventions, strategy_records)")
    }

    // ==================== AI Activity Table (Phase 9) ====================

    private fun createAIActivityTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ai_activity_log (
                id TEXT PRIMARY KEY,
                timestamp INTEGER NOT NULL,
                expert_id TEXT NOT NULL,
                domain_id TEXT,
                provider_id TEXT NOT NULL,
                model_name TEXT,
                action TEXT NOT NULL,
                input_tokens INTEGER DEFAULT 0,
                output_tokens INTEGER DEFAULT 0,
                total_tokens INTEGER DEFAULT 0,
                cost_usd REAL DEFAULT 0,
                latency_ms INTEGER DEFAULT 0,
                situation TEXT,
                was_accepted INTEGER,
                tool_calls TEXT,
                context_summary TEXT,
                response_summary TEXT,
                error_message TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ai_activity_timestamp ON ai_activity_log (timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ai_activity_expert ON ai_activity_log (expert_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ai_activity_provider ON ai_activity_log (provider_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ai_activity_model ON ai_activity_log (model_name)")
        Log.i(TAG, "AI activity log table created")
    }

    // ==================== Capability Requests Table (Phase 11) ====================

    private fun createCapabilityTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS capability_requests (
                id TEXT PRIMARY KEY,
                timestamp INTEGER NOT NULL,
                requesting_expert_id TEXT NOT NULL,
                requesting_domain_id TEXT,
                type INTEGER NOT NULL,
                title TEXT NOT NULL,
                description TEXT NOT NULL,
                current_limitation TEXT,
                expected_benefit TEXT,
                priority INTEGER DEFAULT 2,
                situation TEXT,
                status INTEGER DEFAULT 0,
                user_response TEXT,
                implementation_notes TEXT,
                resolved_at INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cap_status ON capability_requests (status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cap_expert ON capability_requests (requesting_expert_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cap_timestamp ON capability_requests (timestamp)")
        Log.i(TAG, "Capability requests table created")
    }

    // ==================== Familiarity Engine Tables (Phase 12) ====================

    private fun createFamiliarityTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS entity_familiarity (
                id TEXT PRIMARY KEY,
                entity_type INTEGER NOT NULL,
                entity_label TEXT NOT NULL,
                entity_ref_id TEXT,
                total_encounters INTEGER DEFAULT 1,
                recent_encounters INTEGER DEFAULT 1,
                first_seen_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL,
                familiarity_score REAL DEFAULT 0,
                familiarity_level INTEGER DEFAULT 0,
                insight_depth INTEGER DEFAULT 0,
                last_insight_at INTEGER,
                context_diversity REAL DEFAULT 0,
                emotional_valence REAL DEFAULT 0,
                metadata TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_entity_type_label ON entity_familiarity (entity_type, entity_label)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_entity_ref ON entity_familiarity (entity_ref_id)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS insight_history (
                id TEXT PRIMARY KEY,
                entity_id TEXT NOT NULL,
                depth INTEGER NOT NULL,
                category INTEGER NOT NULL,
                content TEXT NOT NULL,
                situation TEXT,
                was_appreciated INTEGER,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_insight_entity ON insight_history (entity_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_insight_category ON insight_history (entity_id, category)")
        Log.i(TAG, "Familiarity engine tables created (entity_familiarity, insight_history)")
    }

    // ==================== Relationship Intelligence Tables (Phase 13) ====================

    private fun createRelationshipTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS relationship_profiles (
                id TEXT PRIMARY KEY,
                person_id INTEGER NOT NULL,
                person_name TEXT NOT NULL,
                relationship_type INTEGER DEFAULT 0,
                closeness_score REAL DEFAULT 0,
                sentiment_trend REAL DEFAULT 0,
                interaction_frequency REAL DEFAULT 0,
                top_topics TEXT,
                pending_requests TEXT,
                shared_memories TEXT,
                last_mood_observed TEXT,
                mood_history TEXT,
                communication_style TEXT,
                notable_traits TEXT,
                last_interaction_at INTEGER,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rel_person ON relationship_profiles (person_id)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS conversation_journal (
                id TEXT PRIMARY KEY,
                person_id INTEGER NOT NULL,
                timestamp INTEGER NOT NULL,
                location TEXT,
                situation TEXT,
                topics TEXT,
                key_points TEXT,
                promises TEXT,
                emotion_observed TEXT,
                my_emotion TEXT,
                transcript TEXT,
                ai_summary TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_conv_person ON conversation_journal (person_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_conv_time ON conversation_journal (timestamp)")
        Log.i(TAG, "Relationship intelligence tables created (relationship_profiles, conversation_journal)")
    }

    private fun createAgentTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS agent_characters (
                agent_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                core_traits TEXT NOT NULL,
                evolved_traits TEXT,
                specializations TEXT,
                catchphrases TEXT,
                success_count INTEGER DEFAULT 0,
                failure_count INTEGER DEFAULT 0,
                total_interactions INTEGER DEFAULT 0,
                user_trust_score REAL DEFAULT 0.5,
                growth_stage INTEGER DEFAULT 0,
                last_reflection TEXT,
                last_reflection_at INTEGER,
                created_at INTEGER NOT NULL,
                last_active_at INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS agent_journal (
                id TEXT PRIMARY KEY,
                agent_id TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                type INTEGER NOT NULL,
                content TEXT NOT NULL,
                emotional_weight REAL DEFAULT 0,
                related_entity_id TEXT,
                was_successful INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_journal_agent ON agent_journal (agent_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_journal_time ON agent_journal (timestamp)")
        Log.i(TAG, "Agent personality tables created (agent_characters, agent_journal)")
    }

    private fun createIntegrityIndexes(db: SQLiteDatabase) {
        // Cross-table integrity indexes (Fix 3: DB Integration Refactoring)
        // These indexes support application-level referential integrity checks
        // since SQLite doesn't support ALTER TABLE ADD FOREIGN KEY.

        // AI activity trail linking: ai_activity_log ↔ ai_interventions (expert_id + timestamp)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ai_activity_expert_ts ON ai_activity_log (expert_id, timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_interv_expert_ts ON ai_interventions (expert_id, timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_strat_expert_sit ON strategy_records (expert_id, situation)")

        // Goal integrity: goal_progress → user_goals
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gp_goal_ts ON goal_progress (goal_id, timestamp)")

        // Memory role index for voice transcript dedup auditing
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_role ON memory_nodes (role)")

        // Agent journal: compound index for efficient per-agent queries
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_journal_agent_type ON agent_journal (agent_id, type)")

        // Conversation journal: compound index for person history
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_conv_person_ts ON conversation_journal (person_id, timestamp)")

        // Insight history: compound index for entity + time queries
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_insight_entity_ts ON insight_history (entity_id, created_at)")

        Log.i(TAG, "Cross-table integrity indexes created (Fix 3)")
    }

    // ==================== Analytics Tables (Phase 17) ====================

    private fun createAnalyticsTables(db: SQLiteDatabase) {
        // Tool call audit log — preserves all tool results (search, weather, route, etc.)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS tool_activity_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                expert_id TEXT,
                tool_name TEXT NOT NULL,
                args_json TEXT,
                result_summary TEXT,
                tokens_used INTEGER DEFAULT 0,
                latency_ms INTEGER DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tool_activity_expert ON tool_activity_log (expert_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tool_activity_tool ON tool_activity_log (tool_name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tool_activity_ts ON tool_activity_log (timestamp)")

        // Token usage daily history — tracks per-provider budget utilization
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS token_usage_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT NOT NULL,
                provider_id TEXT NOT NULL,
                tokens_used INTEGER NOT NULL,
                budget INTEGER NOT NULL,
                tier TEXT NOT NULL,
                UNIQUE(date, provider_id)
            )
        """.trimIndent())

        // Decision log — captures context→decision→outcome for edge delegation analysis
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS decision_log (
                id TEXT PRIMARY KEY,
                timestamp INTEGER NOT NULL,
                situation TEXT,
                context_hash TEXT,
                visible_objects TEXT,
                user_state TEXT,
                hour_of_day INTEGER,
                expert_id TEXT,
                action_type TEXT,
                tool_calls TEXT,
                response_summary TEXT,
                outcome TEXT,
                satisfaction REAL,
                tokens_used INTEGER,
                latency_ms INTEGER,
                pattern_hash TEXT,
                pattern_count INTEGER DEFAULT 1,
                edge_delegatable INTEGER DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_decision_pattern ON decision_log (pattern_hash)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_decision_outcome ON decision_log (outcome)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_decision_expert ON decision_log (expert_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_decision_ts ON decision_log (timestamp)")

        Log.i(TAG, "Analytics tables created (tool_activity_log, token_usage_log, decision_log)")
    }

    // ==================== Error Logs Table (Phase 18) ====================

    private fun createErrorLogsTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS error_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                error_code TEXT NOT NULL,
                message TEXT NOT NULL,
                stack_trace TEXT,
                component TEXT,       -- 발생 컴포넌트 (INPUT_COORDINATOR, VISION_COORDINATOR, SERVER_AI 등)
                resolved INTEGER DEFAULT 0,  -- 1 = 자동 복구됨
                synced INTEGER DEFAULT 0     -- 1 = Steam Deck에 전송됨
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_errlog_ts ON error_logs (timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_errlog_code ON error_logs (error_code)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_errlog_synced ON error_logs (synced)")
        Log.i(TAG, "error_logs table created (DB v16)")
    }

    /**
     * 에러 로그 삽입. SystemEvent.Error 수신 시 호출.
     * @return inserted row id, or -1 on failure
     */
    fun insertErrorLog(
        errorCode: String,
        message: String,
        stackTrace: String? = null,
        component: String? = null
    ): Long {
        return try {
            val db = this.writableDatabase
            val values = ContentValues().apply {
                put("timestamp", System.currentTimeMillis())
                put("error_code", errorCode)
                put("message", message.take(500))
                put("stack_trace", stackTrace?.take(2000))
                put("component", component ?: errorCode.split("_").firstOrNull() ?: "UNKNOWN")
                put("resolved", 0)
                put("synced", 0)
            }
            db.insert("error_logs", null, values)
        } catch (e: Exception) {
            Log.w(TAG, "insertErrorLog 실패: ${e.message}")
            -1L
        }
    }

    /**
     * BackupSyncWorker용: 아직 Steam Deck에 전송하지 않은 에러 로그 조회.
     */
    fun getUnsyncedErrorLogs(limit: Int = 100): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()
        return try {
            val db = this.readableDatabase
            val cursor = db.query(
                "error_logs", null,
                "synced = 0", null, null, null,
                "timestamp ASC", limit.toString()
            )
            cursor.use {
                while (it.moveToNext()) {
                    result.add(mapOf(
                        "id" to it.getLong(it.getColumnIndexOrThrow("id")),
                        "timestamp" to it.getLong(it.getColumnIndexOrThrow("timestamp")),
                        "error_code" to it.getString(it.getColumnIndexOrThrow("error_code")),
                        "message" to it.getString(it.getColumnIndexOrThrow("message")),
                        "component" to (it.getString(it.getColumnIndexOrThrow("component")) ?: ""),
                        "stack_trace" to it.getString(it.getColumnIndexOrThrow("stack_trace"))
                    ))
                }
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "getUnsyncedErrorLogs 실패: ${e.message}")
            result
        }
    }

    /**
     * 전송 완료 표시. BackupSyncWorker 성공 후 호출.
     */
    fun markErrorLogsSynced(ids: List<Long>) {
        if (ids.isEmpty()) return
        try {
            val db = this.writableDatabase
            val placeholders = ids.joinToString(",") { "?" }
            db.execSQL(
                "UPDATE error_logs SET synced = 1 WHERE id IN ($placeholders)",
                ids.map { it.toString() }.toTypedArray()
            )
        } catch (e: Exception) {
            Log.w(TAG, "markErrorLogsSynced 실패: ${e.message}")
        }
    }

    /**
     * 30일 초과 에러 로그 정리. 주기적으로 호출해 DB 용량 관리.
     */
    fun pruneOldErrorLogs(keepDays: Int = 30) {
        try {
            val cutoff = System.currentTimeMillis() - keepDays * 86_400_000L
            val db = this.writableDatabase
            val deleted = db.delete("error_logs", "timestamp < ?", arrayOf(cutoff.toString()))
            if (deleted > 0) Log.d(TAG, "에러 로그 정리: $deleted 건 삭제 (${keepDays}일 초과)")
        } catch (e: Exception) {
            Log.w(TAG, "pruneOldErrorLogs 실패: ${e.message}")
        }
    }

    // ==================== Structured Data Methods ====================

    private fun createStructuredDataTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_STRUCTURED_DATA (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                domain TEXT NOT NULL,
                data_key TEXT NOT NULL,
                value TEXT NOT NULL,
                tags TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                synced_at INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_sd_domain_key ON $TABLE_STRUCTURED_DATA (domain, data_key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sd_domain ON $TABLE_STRUCTURED_DATA (domain)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sd_created_at ON $TABLE_STRUCTURED_DATA (created_at)")
        Log.i(TAG, "structured_data table created")
    }

    fun upsertStructuredData(domain: String, dataKey: String, value: String, tags: String? = null): Long {
        val db = this.writableDatabase
        val now = System.currentTimeMillis()
        // Try update first
        val updated = db.update(
            TABLE_STRUCTURED_DATA,
            ContentValues().apply {
                put("value", value)
                put("tags", tags)
                put("updated_at", now)
                putNull("synced_at") // mark as unsynced on update
            },
            "domain = ? AND data_key = ?",
            arrayOf(domain, dataKey)
        )
        if (updated > 0) return -1L // updated existing

        // Insert new
        return db.insert(TABLE_STRUCTURED_DATA, null, ContentValues().apply {
            put("domain", domain)
            put("data_key", dataKey)
            put("value", value)
            put("tags", tags)
            put("created_at", now)
            put("updated_at", now)
        })
    }

    fun queryStructuredData(
        domain: String,
        dataKey: String? = null,
        tags: String? = null,
        limit: Int = 50
    ): List<StructuredDataRecord> {
        val records = mutableListOf<StructuredDataRecord>()
        val db = this.readableDatabase

        val conditions = mutableListOf("domain = ?")
        val args = mutableListOf(domain)
        if (dataKey != null) {
            conditions.add("data_key LIKE ?")
            args.add("%$dataKey%")
        }
        if (tags != null) {
            conditions.add("tags LIKE ?")
            args.add("%$tags%")
        }

        val cursor = db.query(
            TABLE_STRUCTURED_DATA, null,
            conditions.joinToString(" AND "),
            args.toTypedArray(),
            null, null, "updated_at DESC", limit.toString()
        )
        while (cursor.moveToNext()) {
            records.add(cursorToStructuredData(cursor))
        }
        cursor.close()
        return records
    }

    fun listDataDomains(): List<Pair<String, Int>> {
        val domains = mutableListOf<Pair<String, Int>>()
        val db = this.readableDatabase
        val cursor = db.rawQuery(
            "SELECT domain, COUNT(*) as cnt FROM $TABLE_STRUCTURED_DATA GROUP BY domain ORDER BY cnt DESC",
            null
        )
        while (cursor.moveToNext()) {
            domains.add(cursor.getString(0) to cursor.getInt(1))
        }
        cursor.close()
        return domains
    }

    fun deleteStructuredData(domain: String, dataKey: String): Int {
        val db = this.writableDatabase
        return db.delete(TABLE_STRUCTURED_DATA, "domain = ? AND data_key = ?", arrayOf(domain, dataKey))
    }

    fun getUnsyncedStructuredData(limit: Int = 100): List<StructuredDataRecord> {
        val records = mutableListOf<StructuredDataRecord>()
        val db = this.readableDatabase
        val cursor = db.query(
            TABLE_STRUCTURED_DATA, null,
            "synced_at IS NULL",
            null, null, null, "updated_at ASC", limit.toString()
        )
        while (cursor.moveToNext()) {
            records.add(cursorToStructuredData(cursor))
        }
        cursor.close()
        return records
    }

    fun markStructuredDataSynced(ids: List<Long>) {
        if (ids.isEmpty()) return
        val db = this.writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for (id in ids) {
                db.update(
                    TABLE_STRUCTURED_DATA,
                    ContentValues().apply { put("synced_at", now) },
                    "id = ?",
                    arrayOf(id.toString())
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun cursorToStructuredData(cursor: android.database.Cursor): StructuredDataRecord {
        return StructuredDataRecord(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            domain = cursor.getString(cursor.getColumnIndexOrThrow("domain")),
            dataKey = cursor.getString(cursor.getColumnIndexOrThrow("data_key")),
            value = cursor.getString(cursor.getColumnIndexOrThrow("value")),
            tags = if (cursor.isNull(cursor.getColumnIndexOrThrow("tags"))) null else cursor.getString(cursor.getColumnIndexOrThrow("tags")),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
            syncedAt = if (cursor.isNull(cursor.getColumnIndexOrThrow("synced_at"))) null else cursor.getLong(cursor.getColumnIndexOrThrow("synced_at"))
        )
    }

    // ==================== Existing Methods ====================

    fun insertNode(node: MemoryNode): Long {
        return try {
            val db = this.writableDatabase
            val values = ContentValues().apply {
                put(COLUMN_TIMESTAMP, node.timestamp)
                put(COLUMN_ROLE, node.role)
                put(COLUMN_CONTENT, node.content)
                put(COLUMN_LEVEL, node.level)
                put(COLUMN_PARENT_ID, node.parentId)
                put(COLUMN_EMBEDDING, node.embedding)
                put(COLUMN_LATITUDE, node.latitude)
                put(COLUMN_LONGITUDE, node.longitude)
                put(COLUMN_METADATA, node.metadata)
                put(COLUMN_PERSONA_ID, node.personaId)
                put(COLUMN_SESSION_ID, node.sessionId)  // ★ Phase C
            }
            val rowId = db.insert(TABLE_MEMORY, null, values)
            if (rowId < 0) {
                ErrorReporter.report(TAG, "insertNode failed (db.insert returned -1, role=${node.role})", severity = ErrorSeverity.CRITICAL)
            }
            rowId
        } catch (e: Exception) {
            ErrorReporter.report(TAG, "insertNode threw exception (role=${node.role})", e, ErrorSeverity.CRITICAL)
            -1L
        }
    }

    fun updateNodeContent(id: Long, content: String) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_CONTENT, content)
        }
        db.update(TABLE_MEMORY, values, "$COLUMN_ID = ?", arrayOf(id.toString()))
    }

    fun getUnsummarizedNodes(level: Int, limit: Int = 50): List<MemoryNode> {
        val nodes = mutableListOf<MemoryNode>()
        val db = this.readableDatabase
        // ★ Phase I: 낮은 importance 노드 우선 압축 (중요한 기억 보존)
        val cursor = db.query(
            TABLE_MEMORY, null,
            "$COLUMN_LEVEL = ? AND $COLUMN_PARENT_ID IS NULL",
            arrayOf(level.toString()),
            null, null, "$COLUMN_IMPORTANCE_SCORE ASC, $COLUMN_TIMESTAMP ASC", limit.toString()
        )

        while (cursor.moveToNext()) {
            nodes.add(cursorToNode(cursor))
        }
        cursor.close()
        return nodes
    }

    fun setParentId(nodeIds: List<Long>, parentId: Long) {
        val db = this.writableDatabase
        db.beginTransaction()
        try {
            for (id in nodeIds) {
                val values = ContentValues().apply { put(COLUMN_PARENT_ID, parentId) }
                db.update(TABLE_MEMORY, values, "$COLUMN_ID = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getNodesInTimeRange(startTime: Long, endTime: Long): List<MemoryNode> {
        val nodes = mutableListOf<MemoryNode>()
        val db = this.readableDatabase
        val cursor = db.query(
            TABLE_MEMORY, null,
            "$COLUMN_TIMESTAMP BETWEEN ? AND ?",
            arrayOf(startTime.toString(), endTime.toString()),
            null, null, "$COLUMN_TIMESTAMP ASC"
        )
        while (cursor.moveToNext()) {
            nodes.add(cursorToNode(cursor))
        }
        cursor.close()
        return nodes
    }

    fun getNodesInSpatialRange(lat: Double, lon: Double, radiusKm: Double): List<MemoryNode> {
        val delta = radiusKm / 111.0
        val minLat = lat - delta
        val maxLat = lat + delta
        val minLon = lon - delta
        val maxLon = lon + delta

        val nodes = mutableListOf<MemoryNode>()
        val db = this.readableDatabase
        val cursor = db.query(
            TABLE_MEMORY, null,
            "$COLUMN_LATITUDE BETWEEN ? AND ? AND $COLUMN_LONGITUDE BETWEEN ? AND ?",
            arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString()),
            null, null, "$COLUMN_TIMESTAMP ASC"
        )
        while (cursor.moveToNext()) {
            nodes.add(cursorToNode(cursor))
        }
        cursor.close()
        return nodes
    }

    fun searchNodesByKeyword(query: String): List<MemoryNode> {
        val nodes = mutableListOf<MemoryNode>()
        val db = this.readableDatabase
        val cursor = db.query(
            TABLE_MEMORY, null,
            "$COLUMN_CONTENT LIKE ?",
            arrayOf("%$query%"),
            null, null, "$COLUMN_TIMESTAMP DESC", "20"
        )
        while (cursor.moveToNext()) {
            nodes.add(cursorToNode(cursor))
        }
        cursor.close()
        return nodes
    }

    fun getCount(level: Int = 0): Int {
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_MEMORY WHERE $COLUMN_LEVEL = ?", arrayOf(level.toString()))
        var count = 0
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0)
        }
        cursor.close()
        return count
    }

    fun getAllNodes(): List<MemoryNode> {
        val nodes = mutableListOf<MemoryNode>()
        val db = this.readableDatabase
        val cursor = db.query(TABLE_MEMORY, null, null, null, null, null, "$COLUMN_TIMESTAMP DESC")
        while (cursor.moveToNext()) {
            nodes.add(cursorToNode(cursor))
        }
        cursor.close()
        return nodes
    }

    // ==================== Vector Search Methods ====================

    /**
     * Insert text embedding into vec0 virtual table.
     * @param memoryId: rowid from memory_nodes
     * @param embedding: FloatArray[100] from TextEmbedder
     */
    fun insertTextEmbedding(memoryId: Long, embedding: FloatArray) {
        try {
            val db = this.writableDatabase
            val blob = floatArrayToBlob(embedding)
            db.execSQL(
                "INSERT INTO $VEC_TABLE_TEXT(memory_id, text_embedding) VALUES (?, ?)",
                arrayOf(memoryId, blob)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert text embedding for id=$memoryId: ${e.message}")
        }
    }

    /**
     * KNN search on text embeddings.
     * @return List of (memoryId, distance) ordered by similarity
     */
    fun searchByTextEmbedding(queryEmbedding: FloatArray, limit: Int = 10): List<Pair<Long, Float>> {
        val results = mutableListOf<Pair<Long, Float>>()
        try {
            val db = this.readableDatabase
            val blob = floatArrayToBlob(queryEmbedding)
            val cursor = db.rawQuery(
                "SELECT memory_id, distance FROM $VEC_TABLE_TEXT WHERE text_embedding MATCH ? ORDER BY distance LIMIT ?",
                arrayOf(blob, limit.toString())
            )
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val distance = cursor.getFloat(1)
                results.add(id to distance)
            }
            cursor.close()
        } catch (e: Exception) {
            Log.e(TAG, "Vector search failed: ${e.message}", e)
        }
        return results
    }

    /**
     * Get memory nodes by IDs (for joining with vector search results).
     */
    fun getNodesByIds(ids: List<Long>): List<MemoryNode> {
        if (ids.isEmpty()) return emptyList()
        val nodes = mutableListOf<MemoryNode>()
        val db = this.readableDatabase
        val placeholders = ids.joinToString(",") { "?" }
        val cursor = db.rawQuery(
            "SELECT * FROM $TABLE_MEMORY WHERE $COLUMN_ID IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray()
        )
        while (cursor.moveToNext()) {
            nodes.add(cursorToNode(cursor))
        }
        cursor.close()
        return nodes
    }

    // ==================== Utilities ====================

    private fun cursorToNode(cursor: android.database.Cursor): MemoryNode {
        val personaIdx = cursor.getColumnIndex(COLUMN_PERSONA_ID)
        val sessionIdx = cursor.getColumnIndex(COLUMN_SESSION_ID)
        val importanceIdx = cursor.getColumnIndex(COLUMN_IMPORTANCE_SCORE)
        return MemoryNode(
            cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
            cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
            cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ROLE)),
            cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CONTENT)),
            cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_LEVEL)),
            if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_PARENT_ID))) null else cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_PARENT_ID)),
            cursor.getBlob(cursor.getColumnIndexOrThrow(COLUMN_EMBEDDING)),
            if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE))) null else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE)),
            if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))) null else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE)),
            cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_METADATA)),
            if (personaIdx >= 0 && !cursor.isNull(personaIdx)) cursor.getString(personaIdx) else null,
            if (sessionIdx >= 0 && !cursor.isNull(sessionIdx)) cursor.getString(sessionIdx) else null,
            if (importanceIdx >= 0 && !cursor.isNull(importanceIdx)) cursor.getFloat(importanceIdx) else 0.5f
        )
    }

    // ==================== Persona-filtered Queries ====================

    fun getNodesByPersonaId(personaId: String, limit: Int = 20): List<MemoryNode> {
        val nodes = mutableListOf<MemoryNode>()
        val db = this.readableDatabase
        val cursor = db.query(
            TABLE_MEMORY, null,
            "$COLUMN_PERSONA_ID = ?",
            arrayOf(personaId),
            null, null, "$COLUMN_TIMESTAMP DESC", limit.toString()
        )
        while (cursor.moveToNext()) {
            nodes.add(cursorToNode(cursor))
        }
        cursor.close()
        return nodes
    }

    fun getPersonaInsights(personaId: String, limit: Int = 5): List<MemoryNode> {
        val nodes = mutableListOf<MemoryNode>()
        val db = this.readableDatabase
        val cursor = db.query(
            TABLE_MEMORY, null,
            "$COLUMN_PERSONA_ID = ? AND $COLUMN_LEVEL > 0",
            arrayOf(personaId),
            null, null, "$COLUMN_LEVEL DESC, $COLUMN_TIMESTAMP DESC", limit.toString()
        )
        while (cursor.moveToNext()) {
            nodes.add(cursorToNode(cursor))
        }
        cursor.close()
        return nodes
    }

    fun getUnsummarizedNodesByPersona(personaId: String, level: Int, limit: Int = 50): List<MemoryNode> {
        val nodes = mutableListOf<MemoryNode>()
        val db = this.readableDatabase
        // ★ Phase I: 낮은 importance 노드 우선 압축
        val cursor = db.query(
            TABLE_MEMORY, null,
            "$COLUMN_LEVEL = ? AND $COLUMN_PARENT_ID IS NULL AND $COLUMN_PERSONA_ID = ?",
            arrayOf(level.toString(), personaId),
            null, null, "$COLUMN_IMPORTANCE_SCORE ASC, $COLUMN_TIMESTAMP ASC", limit.toString()
        )
        while (cursor.moveToNext()) {
            nodes.add(cursorToNode(cursor))
        }
        cursor.close()
        return nodes
    }

    // ==================== Phase I: importance_score DAO ====================

    /**
     * importance_score 업데이트 (MemorySaveHelper에서 저장 후 호출).
     */
    fun updateImportanceScore(nodeId: Long, score: Float) {
        try {
            writableDatabase.execSQL(
                "UPDATE $TABLE_MEMORY SET $COLUMN_IMPORTANCE_SCORE = ? WHERE $COLUMN_ID = ?",
                arrayOf(score, nodeId)
            )
        } catch (e: Exception) {
            Log.w(TAG, "updateImportanceScore 실패: ${e.message}")
        }
    }

    /**
     * importance × recency 복합 정렬로 메모리 조회 (PersonaMemoryService용).
     *
     * 공식: importance_score * 0.6 + normalized_timestamp * 0.4
     * normalized_timestamp = timestamp / 1_800_000_000_000 (2027년 기준 ≈ 1.0)
     */
    fun getMemoriesByImportanceRecency(personaId: String, limit: Int): List<MemoryNode> {
        val nodes = mutableListOf<MemoryNode>()
        val db = this.readableDatabase
        val cursor = db.rawQuery(
            """SELECT * FROM $TABLE_MEMORY
               WHERE $COLUMN_LEVEL = 0 AND $COLUMN_PERSONA_ID = ?
               ORDER BY ($COLUMN_IMPORTANCE_SCORE * 0.6 + CAST($COLUMN_TIMESTAMP AS REAL) / 1800000000000.0 * 0.4) DESC
               LIMIT ?""",
            arrayOf(personaId, limit.toString())
        )
        while (cursor.moveToNext()) {
            nodes.add(cursorToNode(cursor))
        }
        cursor.close()
        return nodes
    }

    // ==================== Role-filtered Queries (for Strategist) ====================

    fun getNodesByRole(role: String, limit: Int = 30): List<MemoryNode> {
        val nodes = mutableListOf<MemoryNode>()
        val db = this.readableDatabase
        val cursor = db.query(
            TABLE_MEMORY, null,
            "$COLUMN_ROLE = ?",
            arrayOf(role),
            null, null, "$COLUMN_TIMESTAMP DESC", limit.toString()
        )
        while (cursor.moveToNext()) {
            nodes.add(cursorToNode(cursor))
        }
        cursor.close()
        return nodes
    }

    fun getRecentNodesByRoles(roles: List<String>, limit: Int = 20): List<MemoryNode> {
        if (roles.isEmpty()) return emptyList()
        val nodes = mutableListOf<MemoryNode>()
        val db = this.readableDatabase
        val placeholders = roles.joinToString(",") { "?" }
        val cursor = db.rawQuery(
            "SELECT * FROM $TABLE_MEMORY WHERE $COLUMN_ROLE IN ($placeholders) ORDER BY $COLUMN_TIMESTAMP DESC LIMIT ?",
            roles.toTypedArray() + arrayOf(limit.toString())
        )
        while (cursor.moveToNext()) {
            nodes.add(cursorToNode(cursor))
        }
        cursor.close()
        return nodes
    }

    fun getCountByPersona(personaId: String, level: Int = 0): Int {
        val db = this.readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_MEMORY WHERE $COLUMN_LEVEL = ? AND $COLUMN_PERSONA_ID = ? AND $COLUMN_PARENT_ID IS NULL",
            arrayOf(level.toString(), personaId)
        )
        var count = 0
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0)
        }
        cursor.close()
        return count
    }

    // ==================== Analytics Table Methods (Phase 17) ====================

    fun insertToolActivityLog(
        expertId: String?,
        toolName: String,
        argsJson: String?,
        resultSummary: String?,
        tokensUsed: Int = 0,
        latencyMs: Long = 0
    ): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("expert_id", expertId)
            put("tool_name", toolName)
            put("args_json", argsJson)
            put("result_summary", resultSummary?.take(500))
            put("tokens_used", tokensUsed)
            put("latency_ms", latencyMs)
        }
        return db.insert("tool_activity_log", null, values)
    }

    fun queryToolActivityLog(
        expertId: String? = null,
        toolName: String? = null,
        sinceDays: Int = 7,
        limit: Int = 100
    ): List<Map<String, Any?>> {
        val db = this.readableDatabase
        val since = System.currentTimeMillis() - sinceDays * 86_400_000L
        val conditions = mutableListOf("timestamp > ?")
        val args = mutableListOf(since.toString())

        expertId?.let { conditions.add("expert_id = ?"); args.add(it) }
        toolName?.let { conditions.add("tool_name = ?"); args.add(it) }

        val cursor = db.rawQuery(
            "SELECT * FROM tool_activity_log WHERE ${conditions.joinToString(" AND ")} ORDER BY timestamp DESC LIMIT ?",
            args.toTypedArray() + arrayOf(limit.toString())
        )
        val results = mutableListOf<Map<String, Any?>>()
        while (cursor.moveToNext()) {
            results.add(mapOf(
                "id" to cursor.getLong(0),
                "timestamp" to cursor.getLong(1),
                "expert_id" to cursor.getString(2),
                "tool_name" to cursor.getString(3),
                "args_json" to cursor.getString(4),
                "result_summary" to cursor.getString(5),
                "tokens_used" to cursor.getInt(6),
                "latency_ms" to cursor.getLong(7)
            ))
        }
        cursor.close()
        return results
    }

    fun upsertTokenUsageLog(
        date: String,
        providerId: String,
        tokensUsed: Int,
        budget: Int,
        tier: String
    ) {
        val db = this.writableDatabase
        db.execSQL(
            "INSERT OR REPLACE INTO token_usage_log (date, provider_id, tokens_used, budget, tier) VALUES (?, ?, ?, ?, ?)",
            arrayOf(date, providerId, tokensUsed, budget, tier)
        )
    }

    fun queryTokenUsageLog(days: Int = 7): List<Map<String, Any?>> {
        val db = this.readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM token_usage_log ORDER BY date DESC, provider_id LIMIT ?",
            arrayOf((days * 4).toString()) // up to 4 providers per day
        )
        val results = mutableListOf<Map<String, Any?>>()
        while (cursor.moveToNext()) {
            results.add(mapOf(
                "date" to cursor.getString(1),
                "provider_id" to cursor.getString(2),
                "tokens_used" to cursor.getInt(3),
                "budget" to cursor.getInt(4),
                "tier" to cursor.getString(5)
            ))
        }
        cursor.close()
        return results
    }

    fun insertDecisionLog(
        id: String,
        situation: String?,
        contextHash: String?,
        visibleObjects: String?,
        userState: String?,
        hourOfDay: Int,
        expertId: String?,
        actionType: String?,
        toolCalls: String?,
        responseSummary: String?,
        tokensUsed: Int = 0,
        latencyMs: Long = 0
    ): Long {
        val db = this.writableDatabase
        val patternHash = "${situation}_${actionType}_${expertId}".hashCode().toString()

        // Increment pattern count
        val countCursor = db.rawQuery(
            "SELECT COUNT(*) FROM decision_log WHERE pattern_hash = ?", arrayOf(patternHash)
        )
        val patternCount = if (countCursor.moveToFirst()) countCursor.getInt(0) + 1 else 1
        countCursor.close()

        val values = ContentValues().apply {
            put("id", id)
            put("timestamp", System.currentTimeMillis())
            put("situation", situation)
            put("context_hash", contextHash)
            put("visible_objects", visibleObjects)
            put("user_state", userState)
            put("hour_of_day", hourOfDay)
            put("expert_id", expertId)
            put("action_type", actionType)
            put("tool_calls", toolCalls)
            put("response_summary", responseSummary?.take(200))
            put("outcome", "PENDING")
            put("tokens_used", tokensUsed)
            put("latency_ms", latencyMs)
            put("pattern_hash", patternHash)
            put("pattern_count", patternCount)
        }
        return db.insert("decision_log", null, values)
    }

    fun updateDecisionOutcome(decisionId: String, outcome: String, satisfaction: Float = 0f) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put("outcome", outcome)
            put("satisfaction", satisfaction)
        }
        db.update("decision_log", values, "id = ?", arrayOf(decisionId))
    }

    fun queryEdgeDelegationCandidates(minCount: Int = 10, minSatisfaction: Float = 0.8f): List<Map<String, Any?>> {
        val db = this.readableDatabase
        val cursor = db.rawQuery("""
            SELECT pattern_hash, situation, action_type, expert_id,
                   COUNT(*) as total, AVG(satisfaction) as avg_sat,
                   SUM(CASE WHEN outcome = 'FOLLOWED' THEN 1 ELSE 0 END) as followed_count
            FROM decision_log
            WHERE outcome != 'PENDING'
            GROUP BY pattern_hash
            HAVING total >= ? AND avg_sat >= ?
            ORDER BY total DESC
        """.trimIndent(), arrayOf(minCount.toString(), minSatisfaction.toString()))

        val results = mutableListOf<Map<String, Any?>>()
        while (cursor.moveToNext()) {
            results.add(mapOf(
                "pattern_hash" to cursor.getString(0),
                "situation" to cursor.getString(1),
                "action_type" to cursor.getString(2),
                "expert_id" to cursor.getString(3),
                "total" to cursor.getInt(4),
                "avg_satisfaction" to cursor.getFloat(5),
                "followed_count" to cursor.getInt(6)
            ))
        }
        cursor.close()
        return results
    }

    // ==================== Expert Evolution Tables (Phase 19 — DB v17) ====================

    private fun createExpertEvolutionTables(db: SQLiteDatabase) {
        // 전문가 팀 조합 효율 추적
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS expert_composition_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                situation TEXT NOT NULL,
                expert_ids TEXT NOT NULL,   -- JSON 배열: ["behavior_analyst","speech_lang"]
                domain_ids TEXT NOT NULL,   -- JSON 배열: ["special_education","health"]
                session_start INTEGER NOT NULL,
                session_end INTEGER,
                outcome_score REAL,
                notes TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_comp_situation ON expert_composition_records (situation)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_comp_start ON expert_composition_records (session_start)")

        // 전문가 AI 역량 강화 자기요청 (peer requests)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS expert_peer_requests (
                id TEXT PRIMARY KEY,
                requesting_expert_id TEXT NOT NULL,
                request_type TEXT NOT NULL,       -- PROMPT_ADDITION, TOOL_ACCESS, EXPERT_COLLABORATION, CONTEXT_EXPANSION
                content TEXT NOT NULL,
                rationale TEXT NOT NULL,
                situation TEXT,
                status TEXT NOT NULL DEFAULT 'PENDING',
                reviewer_notes TEXT,
                approved_content TEXT,
                applied_at INTEGER,
                expires_at INTEGER,
                created_at INTEGER NOT NULL,
                outcome_score REAL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_peer_req_status ON expert_peer_requests (status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_peer_req_expert ON expert_peer_requests (requesting_expert_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_peer_req_created ON expert_peer_requests (created_at)")

        Log.i(TAG, "expert_composition_records + expert_peer_requests tables created (DB v17)")
    }

    // ==================== Life Session Tables (Phase C — DB v18) ====================

    private fun createLifeSessionsTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_LIFE_SESSIONS (
                id TEXT PRIMARY KEY,
                situation TEXT,
                start_at INTEGER NOT NULL,
                end_at INTEGER,
                summary TEXT,
                memory_count INTEGER DEFAULT 0
            )
        """.trimIndent())
        Log.i(TAG, "life_sessions table created (DB v18)")
    }

    fun insertLifeSession(session: com.xreal.nativear.session.LifeSession): Boolean {
        return try {
            val db = this.writableDatabase
            val values = ContentValues().apply {
                put("id", session.id)
                put("situation", session.situation)
                put("start_at", session.startAt)
                put("end_at", session.endAt)
                put("summary", session.summary)
                put("memory_count", session.memoryCount)
            }
            db.insertOrThrow(TABLE_LIFE_SESSIONS, null, values) > 0
        } catch (e: Exception) {
            Log.e(TAG, "insertLifeSession failed: ${e.message}")
            false
        }
    }

    fun updateLifeSessionEnd(sessionId: String, endAt: Long, summary: String?): Boolean {
        return try {
            val db = this.writableDatabase
            val values = ContentValues().apply {
                put("end_at", endAt)
                if (summary != null) put("summary", summary)
            }
            db.update(TABLE_LIFE_SESSIONS, values, "id = ?", arrayOf(sessionId)) > 0
        } catch (e: Exception) {
            Log.e(TAG, "updateLifeSessionEnd failed: ${e.message}")
            false
        }
    }

    fun getRecentLifeSessions(sinceMsAgo: Long): List<com.xreal.nativear.session.LifeSession> {
        val sessions = mutableListOf<com.xreal.nativear.session.LifeSession>()
        return try {
            val db = this.readableDatabase
            val since = System.currentTimeMillis() - sinceMsAgo
            val cursor = db.rawQuery(
                "SELECT * FROM $TABLE_LIFE_SESSIONS WHERE start_at > ? ORDER BY start_at DESC",
                arrayOf(since.toString())
            )
            while (cursor.moveToNext()) {
                val endAtIdx = cursor.getColumnIndex("end_at")
                sessions.add(com.xreal.nativear.session.LifeSession(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    situation = cursor.getString(cursor.getColumnIndexOrThrow("situation")),
                    startAt = cursor.getLong(cursor.getColumnIndexOrThrow("start_at")),
                    endAt = if (endAtIdx >= 0 && !cursor.isNull(endAtIdx)) cursor.getLong(endAtIdx) else null,
                    summary = cursor.getString(cursor.getColumnIndexOrThrow("summary")),
                    memoryCount = cursor.getInt(cursor.getColumnIndexOrThrow("memory_count"))
                ))
            }
            cursor.close()
            sessions
        } catch (e: Exception) {
            Log.e(TAG, "getRecentLifeSessions failed: ${e.message}")
            sessions
        }
    }

    fun incrementSessionMemoryCount(sessionId: String) {
        try {
            val db = this.writableDatabase
            db.execSQL(
                "UPDATE $TABLE_LIFE_SESSIONS SET memory_count = memory_count + 1 WHERE id = ?",
                arrayOf(sessionId)
            )
        } catch (e: Exception) {
            Log.w(TAG, "incrementSessionMemoryCount failed: ${e.message}")
        }
    }

    // ==================== Phase H: Structured Data Exact Lookup ====================

    /**
     * domain + data_key 정확 일치로 단일 레코드 조회.
     * queryStructuredData()의 LIKE 검색과 달리 exact match.
     */
    fun getStructuredDataExact(domain: String, dataKey: String): StructuredDataRecord? {
        return try {
            val db = this.readableDatabase
            val cursor = db.query(
                TABLE_STRUCTURED_DATA, null,
                "domain = ? AND data_key = ?",
                arrayOf(domain, dataKey),
                null, null, null, "1"
            )
            val record = if (cursor.moveToFirst()) cursorToStructuredData(cursor) else null
            cursor.close()
            record
        } catch (e: Exception) {
            Log.w(TAG, "getStructuredDataExact failed: ${e.message}")
            null
        }
    }

    // ==================== Phase H: Count Helpers (TrainingReadinessChecker) ====================

    /** level=0 (원본) 메모리 노드 수. 훈련 준비도 조건 확인용. */
    fun getMemoryNodeCount(): Int {
        return try {
            val db = this.readableDatabase
            val cursor = db.rawQuery(
                "SELECT COUNT(*) FROM $TABLE_MEMORY WHERE $COLUMN_LEVEL = 0", null
            )
            var count = 0
            if (cursor.moveToFirst()) count = cursor.getInt(0)
            cursor.close()
            count
        } catch (e: Exception) {
            Log.w(TAG, "getMemoryNodeCount failed: ${e.message}")
            0
        }
    }

    /** ai_interventions (OutcomeTracker 기록) 총 건수. 훈련 준비도 조건 확인용. */
    fun getOutcomeRecordCount(): Int {
        return try {
            val db = this.readableDatabase
            val cursor = db.rawQuery(
                "SELECT COUNT(*) FROM ai_interventions", null
            )
            var count = 0
            if (cursor.moveToFirst()) count = cursor.getInt(0)
            cursor.close()
            count
        } catch (e: Exception) {
            Log.w(TAG, "getOutcomeRecordCount failed: ${e.message}")
            0
        }
    }

    // ==================== Phase H: DailyValueReport DAO ====================

    private fun createDailyValueReportsTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_DAILY_VALUE_REPORTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                report_date TEXT NOT NULL UNIQUE,
                expert_consultations INTEGER DEFAULT 0,
                data_decisions INTEGER DEFAULT 0,
                memories_referenced INTEGER DEFAULT 0,
                goal_progress_pct REAL DEFAULT 0.0,
                feedback_score REAL DEFAULT -1.0,
                token_cost_krw REAL DEFAULT 0.0,
                value_per_token_krw REAL DEFAULT 0.0,
                ai_summary TEXT,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_dvr_date ON $TABLE_DAILY_VALUE_REPORTS (report_date)")
        Log.i(TAG, "daily_value_reports table created")
    }

    // ==================== Phase J: MultiAISession 테이블 ====================

    private fun createMultiAISessionsTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS multi_ai_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_date TEXT NOT NULL,
                query_text TEXT NOT NULL,
                persona_ids TEXT NOT NULL,
                consensus_level REAL DEFAULT 0.0,
                synthesis_mode TEXT NOT NULL,
                synthesized_text TEXT,
                was_helpful INTEGER DEFAULT -1,
                total_latency_ms INTEGER DEFAULT 0,
                token_cost_usd REAL DEFAULT 0.0,
                timestamp INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mas_date ON multi_ai_sessions(session_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mas_helpful ON multi_ai_sessions(was_helpful)")
        Log.i(TAG, "multi_ai_sessions table created")
    }

    fun insertMultiAISession(session: MultiAISession): Long {
        return try {
            val cv = ContentValues().apply {
                put("session_date", session.sessionDate)
                put("query_text", session.queryText)
                put("persona_ids", session.personaIds)
                put("consensus_level", session.consensusLevel)
                put("synthesis_mode", session.synthesisMode)
                put("synthesized_text", session.synthesizedText)
                put("was_helpful", session.wasHelpful)
                put("total_latency_ms", session.totalLatencyMs)
                put("token_cost_usd", session.tokenCostUsd)
                put("timestamp", session.timestamp)
            }
            writableDatabase.insert("multi_ai_sessions", null, cv)
        } catch (e: Exception) {
            Log.w(TAG, "insertMultiAISession 실패: ${e.message}")
            -1L
        }
    }

    fun updateMultiAISessionHelpful(id: Long, wasHelpful: Int) {
        try {
            val cv = ContentValues().apply { put("was_helpful", wasHelpful) }
            writableDatabase.update("multi_ai_sessions", cv, "id = ?", arrayOf(id.toString()))
        } catch (e: Exception) {
            Log.w(TAG, "updateMultiAISessionHelpful 실패: ${e.message}")
        }
    }

    fun getMultiAISessionStats(days: Int): List<PersonaCompositionStat> {
        val result = mutableListOf<PersonaCompositionStat>()
        return try {
            val cutoff = System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000
            val cursor = readableDatabase.rawQuery(
                """
                SELECT persona_ids,
                       COUNT(*) as total,
                       AVG(CASE WHEN was_helpful=1 THEN 1.0
                                WHEN was_helpful=0 THEN 0.0
                                ELSE NULL END) as helpful_rate,
                       AVG(consensus_level) as avg_consensus,
                       AVG(total_latency_ms) as avg_latency
                FROM multi_ai_sessions
                WHERE timestamp > ?
                GROUP BY persona_ids
                ORDER BY helpful_rate DESC
                LIMIT 10
                """.trimIndent(),
                arrayOf(cutoff.toString())
            )
            while (cursor.moveToNext()) {
                result.add(PersonaCompositionStat(
                    personaIds = cursor.getString(0) ?: "[]",
                    totalSessions = cursor.getInt(1),
                    helpfulRate = if (cursor.isNull(2)) -1f else cursor.getFloat(2),
                    avgConsensus = cursor.getFloat(3),
                    avgLatencyMs = cursor.getLong(4)
                ))
            }
            cursor.close()
            result
        } catch (e: Exception) {
            Log.w(TAG, "getMultiAISessionStats 실패: ${e.message}")
            result
        }
    }

    fun insertOrUpdateDailyReport(report: DailyValueReport) {
        try {
            val db = this.writableDatabase
            val existing = getDailyReport(report.reportDate)
            val cv = ContentValues().apply {
                put("report_date", report.reportDate)
                put("expert_consultations", report.expertConsultations)
                put("data_decisions", report.dataDecisions)
                put("memories_referenced", report.memoriesReferenced)
                put("goal_progress_pct", report.goalProgressPct)
                put("feedback_score", report.feedbackScore)
                put("token_cost_krw", report.tokenCostKrw)
                put("value_per_token_krw", report.valuePerTokenKrw)
                put("ai_summary", report.aiSummary)
                put("created_at", report.createdAt)
            }
            if (existing != null) {
                db.update(TABLE_DAILY_VALUE_REPORTS, cv, "report_date = ?", arrayOf(report.reportDate))
            } else {
                db.insert(TABLE_DAILY_VALUE_REPORTS, null, cv)
            }
        } catch (e: Exception) {
            Log.e(TAG, "insertOrUpdateDailyReport failed: ${e.message}", e)
        }
    }

    fun getDailyReport(date: String): DailyValueReport? {
        return try {
            val db = this.readableDatabase
            val cursor = db.query(
                TABLE_DAILY_VALUE_REPORTS, null,
                "report_date = ?", arrayOf(date),
                null, null, null, "1"
            )
            val report = if (cursor.moveToFirst()) cursorToDailyReport(cursor) else null
            cursor.close()
            report
        } catch (e: Exception) {
            Log.w(TAG, "getDailyReport failed: ${e.message}")
            null
        }
    }

    fun getRecentReports(days: Int): List<DailyValueReport> {
        val reports = mutableListOf<DailyValueReport>()
        return try {
            val db = this.readableDatabase
            val cursor = db.query(
                TABLE_DAILY_VALUE_REPORTS, null,
                null, null, null, null,
                "report_date DESC", days.toString()
            )
            while (cursor.moveToNext()) reports.add(cursorToDailyReport(cursor))
            cursor.close()
            reports
        } catch (e: Exception) {
            Log.w(TAG, "getRecentReports failed: ${e.message}")
            reports
        }
    }

    /** 최근 N일 value_per_token_krw 평균. 0 이상인 값만 포함. */
    fun getAverageValueScore(days: Int): Float {
        return try {
            val db = this.readableDatabase
            val cursor = db.rawQuery(
                "SELECT AVG(value_per_token_krw) FROM $TABLE_DAILY_VALUE_REPORTS " +
                "WHERE value_per_token_krw > 0 ORDER BY report_date DESC LIMIT ?",
                arrayOf(days.toString())
            )
            var avg = 0f
            if (cursor.moveToFirst() && !cursor.isNull(0)) avg = cursor.getFloat(0)
            cursor.close()
            avg
        } catch (e: Exception) {
            Log.w(TAG, "getAverageValueScore failed: ${e.message}")
            0f
        }
    }

    private fun cursorToDailyReport(cursor: android.database.Cursor): DailyValueReport {
        return DailyValueReport(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            reportDate = cursor.getString(cursor.getColumnIndexOrThrow("report_date")),
            expertConsultations = cursor.getInt(cursor.getColumnIndexOrThrow("expert_consultations")),
            dataDecisions = cursor.getInt(cursor.getColumnIndexOrThrow("data_decisions")),
            memoriesReferenced = cursor.getInt(cursor.getColumnIndexOrThrow("memories_referenced")),
            goalProgressPct = cursor.getFloat(cursor.getColumnIndexOrThrow("goal_progress_pct")),
            feedbackScore = cursor.getFloat(cursor.getColumnIndexOrThrow("feedback_score")),
            tokenCostKrw = cursor.getFloat(cursor.getColumnIndexOrThrow("token_cost_krw")),
            valuePerTokenKrw = cursor.getFloat(cursor.getColumnIndexOrThrow("value_per_token_krw")),
            aiSummary = if (cursor.isNull(cursor.getColumnIndexOrThrow("ai_summary"))) null
                        else cursor.getString(cursor.getColumnIndexOrThrow("ai_summary")),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
        )
    }

}
