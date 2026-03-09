package com.xreal.nativear

import android.content.ContentValues
import android.content.Context
import android.util.Log
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import io.requery.android.database.sqlite.SQLiteCustomExtension
import io.requery.android.database.sqlite.SQLiteOpenHelper
import com.xreal.nativear.spatial.ISpatialDatabase
import com.xreal.nativear.spatial.SpatialAnchorRecord
import com.xreal.nativear.spatial.PlaceSignatureRecord

class SceneDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION),
    com.xreal.nativear.spatial.ISpatialDatabase {

    private val TAG = "SceneDatabase"
    private val nativeLibDir = context.applicationInfo.nativeLibraryDir

    data class SceneNode(
        val id: Long = 0,
        val timestamp: Long,
        val label: String,
        val embedding: ByteArray,
        val latitude: Double? = null,
        val longitude: Double? = null
    )

    data class VoiceLog(
        val id: Long = 0,
        val timestamp: Long,
        val transcript: String,
        val audioEmbedding: ByteArray? = null,
        val emotion: String? = null,
        val emotionScore: Float? = null,
        val latitude: Double? = null,
        val longitude: Double? = null
    )

    data class AudioEventRecord(
        val id: Long = 0,
        val timestamp: Long,
        val labels: String,
        val scores: String,
        val embedding: ByteArray,
        val latitude: Double? = null,
        val longitude: Double? = null
    )

    data class PersonRecord(
        val id: Long = 0,
        val name: String?,
        val relationship: String?,
        val registeredAt: Long,
        val photoCount: Int = 0,
        val notes: String? = null
    )

    data class PersonFaceRecord(
        val id: Long = 0,
        val personId: Long,
        val embedding: ByteArray,
        val faceCropPath: String?,
        val capturedAt: Long,
        val qualityScore: Float = 0f
    )

    data class InteractionRecord(
        val id: Long = 0,
        val personId: Long,
        val timestamp: Long,
        val transcript: String?,
        val expression: String?,
        val expressionScore: Float?,
        val audioEmotion: String?,
        val sceneNodeId: Long?,
        val audioEventId: Long?,
        val latitude: Double?,
        val longitude: Double?
    )

    data class PersonVoiceRecord(
        val id: Long = 0,
        val personId: Long,
        val voiceEmbedding: ByteArray,
        val capturedAt: Long,
        val transcript: String?
    )

    companion object {
        private const val DATABASE_NAME = "scene_graph_v2.db"
        private const val DATABASE_VERSION = 11
        private const val TABLE_SCENE = "scene_nodes"
        private const val TABLE_VOICE = "voice_logs"
        private const val TABLE_AUDIO_EVENTS = "audio_events"
        private const val TABLE_PERSONS = "persons"
        private const val TABLE_PERSON_FACES = "person_faces"
        private const val TABLE_INTERACTIONS = "interactions"
        private const val TABLE_PERSON_VOICES = "person_voices"
        private const val TABLE_SPATIAL_ANCHORS = "spatial_anchors"
        private const val TABLE_PLACE_SIGNATURES = "place_signatures"
        private const val TABLE_INTERACTION_TEMPLATES = "interaction_templates"
        private const val VEC_TABLE_PLACE_VISUAL = "vec_place_visual"
        private const val PLACE_VISUAL_DIM = 1280

        private const val COLUMN_ID = "id"
        private const val COLUMN_TIMESTAMP = "timestamp"
        private const val COLUMN_LABEL = "label"
        private const val COLUMN_EMBEDDING = "embedding"
        private const val COLUMN_TRANSCRIPT = "transcript"
        private const val COLUMN_AUDIO_EMBEDDING = "audio_embedding"
        private const val COLUMN_EMOTION = "emotion"
        private const val COLUMN_EMOTION_SCORE = "emotion_score"
        private const val COLUMN_LATITUDE = "latitude"
        private const val COLUMN_LONGITUDE = "longitude"
        private const val COLUMN_LABELS = "labels"
        private const val COLUMN_SCORES = "scores"

        private const val IMAGE_EMBEDDING_DIM = 1280
        private const val AUDIO_EMBEDDING_DIM = 768
        private const val YAMNET_EMBEDDING_DIM = 1024
        private const val FACE_EMBEDDING_DIM = 192

        private const val VEC_TABLE_IMAGES = "vec_scene_images"
        private const val VEC_TABLE_AUDIO = "vec_voice_audio"
        private const val VEC_TABLE_AUDIO_EVENTS = "vec_audio_events"
        private const val VEC_TABLE_PERSON_FACES = "vec_person_faces"
        private const val VEC_TABLE_PERSON_VOICES = "vec_person_voices"
        private const val VOICE_EMBEDDING_DIM = 768
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
                // requery: PRAGMA returns a result row → must use rawQuery, NOT execSQL
                db.rawQuery("PRAGMA journal_mode=WAL", null)?.close()
                db.rawQuery("PRAGMA synchronous=NORMAL", null)?.close()
                Log.i(TAG, "SQLite WAL mode enabled")
            } catch (e: Exception) {
                Log.w(TAG, "SQLite PRAGMA 설정 실패 (비치명적): ${e.message}")
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createSceneTable = ("CREATE TABLE $TABLE_SCENE (" +
                "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$COLUMN_TIMESTAMP LONG, " +
                "$COLUMN_LABEL TEXT, " +
                "$COLUMN_EMBEDDING BLOB, " +
                "$COLUMN_LATITUDE DOUBLE, " +
                "$COLUMN_LONGITUDE DOUBLE)")
        db.execSQL(createSceneTable)

        val createVoiceTable = ("CREATE TABLE $TABLE_VOICE (" +
                "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$COLUMN_TIMESTAMP LONG, " +
                "$COLUMN_TRANSCRIPT TEXT, " +
                "$COLUMN_AUDIO_EMBEDDING BLOB, " +
                "$COLUMN_EMOTION TEXT, " +
                "$COLUMN_EMOTION_SCORE REAL, " +
                "$COLUMN_LATITUDE DOUBLE, " +
                "$COLUMN_LONGITUDE DOUBLE)")
        db.execSQL(createVoiceTable)

        val createAudioEventsTable = ("CREATE TABLE $TABLE_AUDIO_EVENTS (" +
                "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$COLUMN_TIMESTAMP LONG, " +
                "$COLUMN_LABELS TEXT, " +
                "$COLUMN_SCORES TEXT, " +
                "$COLUMN_EMBEDDING BLOB, " +
                "$COLUMN_LATITUDE DOUBLE, " +
                "$COLUMN_LONGITUDE DOUBLE)")
        db.execSQL(createAudioEventsTable)

        createPersonTables(db)
        createPersonVoiceTables(db)
        createSpatialAnchorTables(db)
        createPlaceSignatureTables(db)
        createInteractionTemplateTables(db)
        createVecTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_SCENE ADD COLUMN $COLUMN_LATITUDE DOUBLE")
            db.execSQL("ALTER TABLE $TABLE_SCENE ADD COLUMN $COLUMN_LONGITUDE DOUBLE")

            val createVoiceTable = ("CREATE TABLE $TABLE_VOICE (" +
                    "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "$COLUMN_TIMESTAMP LONG, " +
                    "$COLUMN_TRANSCRIPT TEXT, " +
                    "$COLUMN_AUDIO_EMBEDDING BLOB, " +
                    "$COLUMN_LATITUDE DOUBLE, " +
                    "$COLUMN_LONGITUDE DOUBLE)")
            db.execSQL(createVoiceTable)
        }
        if (oldVersion < 3) {
            try {
                db.execSQL("ALTER TABLE $TABLE_VOICE ADD COLUMN $COLUMN_AUDIO_EMBEDDING BLOB")
            } catch (_: Exception) { /* column may already exist */ }
        }
        if (oldVersion < 5) {
            createVecTables(db)
            migrateExistingEmbeddings(db)
        }
        if (oldVersion < 6) {
            db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_AUDIO_EVENTS (" +
                    "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "$COLUMN_TIMESTAMP LONG, " +
                    "$COLUMN_LABELS TEXT, " +
                    "$COLUMN_SCORES TEXT, " +
                    "$COLUMN_EMBEDDING BLOB, " +
                    "$COLUMN_LATITUDE DOUBLE, " +
                    "$COLUMN_LONGITUDE DOUBLE)")
            try {
                db.execSQL("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS $VEC_TABLE_AUDIO_EVENTS USING vec0(
                        event_id INTEGER PRIMARY KEY,
                        event_embedding FLOAT[$YAMNET_EMBEDDING_DIM] distance_metric=cosine
                    )
                """.trimIndent())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create vec_audio_events on upgrade: ${e.message}", e)
            }
        }
        if (oldVersion < 7) {
            createPersonTables(db)
            try {
                db.execSQL("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS $VEC_TABLE_PERSON_FACES USING vec0(
                        face_id INTEGER PRIMARY KEY,
                        face_embedding FLOAT[$FACE_EMBEDDING_DIM] distance_metric=cosine
                    )
                """.trimIndent())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create vec_person_faces on upgrade: ${e.message}", e)
            }
        }
        if (oldVersion < 8) {
            createPersonVoiceTables(db)
            try {
                db.execSQL("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS $VEC_TABLE_PERSON_VOICES USING vec0(
                        voice_id INTEGER PRIMARY KEY,
                        voice_embedding FLOAT[$VOICE_EMBEDDING_DIM] distance_metric=cosine
                    )
                """.trimIndent())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create vec_person_voices on upgrade: ${e.message}", e)
            }
        }
        if (oldVersion < 9) {
            createSpatialAnchorTables(db)
        }
        if (oldVersion < 10) {
            createPlaceSignatureTables(db)
        }
        if (oldVersion < 11) {
            createInteractionTemplateTables(db)
        }
    }

    private fun createPersonTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_PERSONS (" +
                "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT, " +
                "relationship TEXT, " +
                "registered_at LONG, " +
                "photo_count INTEGER DEFAULT 0, " +
                "notes TEXT)")

        db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_PERSON_FACES (" +
                "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "person_id INTEGER, " +
                "$COLUMN_EMBEDDING BLOB, " +
                "face_crop_path TEXT, " +
                "captured_at LONG, " +
                "quality_score REAL)")

        db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_INTERACTIONS (" +
                "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "person_id INTEGER, " +
                "$COLUMN_TIMESTAMP LONG, " +
                "$COLUMN_TRANSCRIPT TEXT, " +
                "expression TEXT, " +
                "expression_score REAL, " +
                "audio_emotion TEXT, " +
                "scene_node_id INTEGER, " +
                "audio_event_id INTEGER, " +
                "$COLUMN_LATITUDE DOUBLE, " +
                "$COLUMN_LONGITUDE DOUBLE)")

        Log.i(TAG, "Person tables created (persons, person_faces, interactions)")
    }

    private fun createPersonVoiceTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_PERSON_VOICES (" +
                "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "person_id INTEGER, " +
                "voice_embedding BLOB, " +
                "captured_at LONG, " +
                "$COLUMN_TRANSCRIPT TEXT)")
        Log.i(TAG, "Person voice table created")
    }

    /**
     * 공간 앵커 테이블 생성 (Level 1-2 지속성용).
     *
     * spatial_anchors: 앵커 메타데이터 + VIO 좌표 + GPS + 시각 임베딩
     */
    private fun createSpatialAnchorTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_SPATIAL_ANCHORS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                label TEXT NOT NULL,
                type TEXT NOT NULL,
                gps_latitude REAL,
                gps_longitude REAL,
                local_x REAL NOT NULL,
                local_y REAL NOT NULL,
                local_z REAL NOT NULL,
                depth_meters REAL NOT NULL,
                depth_source TEXT NOT NULL,
                confidence REAL NOT NULL,
                seen_count INTEGER DEFAULT 1,
                created_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL,
                visual_embedding BLOB,
                metadata TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_spatial_anchors_gps ON $TABLE_SPATIAL_ANCHORS(gps_latitude, gps_longitude)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_spatial_anchors_label ON $TABLE_SPATIAL_ANCHORS(label)")
        Log.i(TAG, "Spatial anchor table created")
    }

    private fun createVecTables(db: SQLiteDatabase) {
        try {
            db.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS $VEC_TABLE_IMAGES USING vec0(
                    scene_id INTEGER PRIMARY KEY,
                    image_embedding FLOAT[$IMAGE_EMBEDDING_DIM] distance_metric=cosine
                )
            """.trimIndent())
            db.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS $VEC_TABLE_AUDIO USING vec0(
                    voice_id INTEGER PRIMARY KEY,
                    audio_embedding FLOAT[$AUDIO_EMBEDDING_DIM] distance_metric=cosine
                )
            """.trimIndent())
            db.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS $VEC_TABLE_AUDIO_EVENTS USING vec0(
                    event_id INTEGER PRIMARY KEY,
                    event_embedding FLOAT[$YAMNET_EMBEDDING_DIM] distance_metric=cosine
                )
            """.trimIndent())
            db.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS $VEC_TABLE_PERSON_FACES USING vec0(
                    face_id INTEGER PRIMARY KEY,
                    face_embedding FLOAT[$FACE_EMBEDDING_DIM] distance_metric=cosine
                )
            """.trimIndent())
            db.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS $VEC_TABLE_PERSON_VOICES USING vec0(
                    voice_id INTEGER PRIMARY KEY,
                    voice_embedding FLOAT[$VOICE_EMBEDDING_DIM] distance_metric=cosine
                )
            """.trimIndent())
            Log.i(TAG, "vec tables created (image=$IMAGE_EMBEDDING_DIM, audio=$AUDIO_EMBEDDING_DIM, yamnet=$YAMNET_EMBEDDING_DIM, face=$FACE_EMBEDDING_DIM, voice=$VOICE_EMBEDDING_DIM)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create vec tables: ${e.message}", e)
        }
    }

    private fun migrateExistingEmbeddings(db: SQLiteDatabase) {
        try {
            // Migrate scene_nodes embeddings → vec_scene_images
            val sceneCursor = db.query(TABLE_SCENE, arrayOf(COLUMN_ID, COLUMN_EMBEDDING), "$COLUMN_EMBEDDING IS NOT NULL", null, null, null, null)
            var sceneCount = 0
            while (sceneCursor.moveToNext()) {
                val id = sceneCursor.getLong(0)
                val embBlob = sceneCursor.getBlob(1) ?: continue
                if (embBlob.size == IMAGE_EMBEDDING_DIM * 4) {
                    db.execSQL("INSERT OR IGNORE INTO $VEC_TABLE_IMAGES(scene_id, image_embedding) VALUES (?, ?)", arrayOf(id, embBlob))
                    sceneCount++
                }
            }
            sceneCursor.close()

            // Migrate voice_logs audio_embeddings → vec_voice_audio
            val voiceCursor = db.query(TABLE_VOICE, arrayOf(COLUMN_ID, COLUMN_AUDIO_EMBEDDING), "$COLUMN_AUDIO_EMBEDDING IS NOT NULL", null, null, null, null)
            var voiceCount = 0
            while (voiceCursor.moveToNext()) {
                val id = voiceCursor.getLong(0)
                val embBlob = voiceCursor.getBlob(1) ?: continue
                if (embBlob.size == AUDIO_EMBEDDING_DIM * 4) {
                    db.execSQL("INSERT OR IGNORE INTO $VEC_TABLE_AUDIO(voice_id, audio_embedding) VALUES (?, ?)", arrayOf(id, embBlob))
                    voiceCount++
                }
            }
            voiceCursor.close()

            Log.i(TAG, "Migrated $sceneCount scene embeddings, $voiceCount voice embeddings to vec tables")
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed: ${e.message}", e)
        }
    }

    // ==================== Insert Methods ====================

    fun insertNode(timestamp: Long, label: String, embedding: ByteArray, lat: Double? = null, lon: Double? = null): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_TIMESTAMP, timestamp)
            put(COLUMN_LABEL, label)
            put(COLUMN_EMBEDDING, embedding)
            put(COLUMN_LATITUDE, lat)
            put(COLUMN_LONGITUDE, lon)
        }
        val id = db.insert(TABLE_SCENE, null, values)

        // Also insert into vec table for KNN search
        if (id > 0 && embedding.size == IMAGE_EMBEDDING_DIM * 4) {
            try {
                db.execSQL(
                    "INSERT INTO $VEC_TABLE_IMAGES(scene_id, image_embedding) VALUES (?, ?)",
                    arrayOf(id, embedding)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert scene vec: ${e.message}")
            }
        }

        return id
    }

    fun insertVoiceLog(timestamp: Long, transcript: String, audioEmbedding: ByteArray? = null, emotion: String? = null, emotionScore: Float? = null, lat: Double? = null, lon: Double? = null): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_TIMESTAMP, timestamp)
            put(COLUMN_TRANSCRIPT, transcript)
            put(COLUMN_AUDIO_EMBEDDING, audioEmbedding)
            put(COLUMN_EMOTION, emotion)
            put(COLUMN_EMOTION_SCORE, emotionScore)
            put(COLUMN_LATITUDE, lat)
            put(COLUMN_LONGITUDE, lon)
        }
        val id = db.insert(TABLE_VOICE, null, values)

        // Also insert into vec table for KNN search
        if (id > 0 && audioEmbedding != null && audioEmbedding.size == AUDIO_EMBEDDING_DIM * 4) {
            try {
                db.execSQL(
                    "INSERT INTO $VEC_TABLE_AUDIO(voice_id, audio_embedding) VALUES (?, ?)",
                    arrayOf(id, audioEmbedding)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert voice vec: ${e.message}")
            }
        }

        return id
    }

    fun insertAudioEvent(timestamp: Long, labels: String, scores: String, embedding: ByteArray, lat: Double? = null, lon: Double? = null): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_TIMESTAMP, timestamp)
            put(COLUMN_LABELS, labels)
            put(COLUMN_SCORES, scores)
            put(COLUMN_EMBEDDING, embedding)
            put(COLUMN_LATITUDE, lat)
            put(COLUMN_LONGITUDE, lon)
        }
        val id = db.insert(TABLE_AUDIO_EVENTS, null, values)

        if (id > 0 && embedding.size == YAMNET_EMBEDDING_DIM * 4) {
            try {
                db.execSQL(
                    "INSERT INTO $VEC_TABLE_AUDIO_EVENTS(event_id, event_embedding) VALUES (?, ?)",
                    arrayOf(id, embedding)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert audio event vec: ${e.message}")
            }
        }

        return id
    }

    // ==================== Query Methods ====================

    fun getCount(): Int {
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_SCENE", null)
        var count = 0
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0)
        }
        cursor.close()
        return count
    }

    fun getAllNodes(): List<SceneNode> {
        val db = this.readableDatabase
        val cursor = db.query(TABLE_SCENE, null, null, null, null, null, "$COLUMN_TIMESTAMP DESC")
        val nodes = mutableListOf<SceneNode>()

        while (cursor.moveToNext()) {
            nodes.add(cursorToSceneNode(cursor))
        }
        cursor.close()
        return nodes
    }

    fun getAllVoiceLogs(): List<VoiceLog> {
        val db = this.readableDatabase
        val cursor = db.query(TABLE_VOICE, null, null, null, null, null, "$COLUMN_TIMESTAMP DESC")
        val logs = mutableListOf<VoiceLog>()

        while (cursor.moveToNext()) {
            logs.add(cursorToVoiceLog(cursor))
        }
        cursor.close()
        return logs
    }

    fun getNodesBetween(startTime: Long, endTime: Long): List<SceneNode> {
        val db = this.readableDatabase
        val cursor = db.query(
            TABLE_SCENE, null,
            "$COLUMN_TIMESTAMP >= ? AND $COLUMN_TIMESTAMP <= ?",
            arrayOf(startTime.toString(), endTime.toString()),
            null, null, "$COLUMN_TIMESTAMP DESC"
        )
        val nodes = mutableListOf<SceneNode>()
        while (cursor.moveToNext()) {
            nodes.add(cursorToSceneNode(cursor))
        }
        cursor.close()
        return nodes
    }

    fun getNodesNearLocation(lat: Double, lon: Double, radiusMeters: Double): List<SceneNode> {
        val allNodes = getAllNodes()
        return allNodes.filter { node ->
            if (node.latitude == null || node.longitude == null) return@filter false
            val distance = haversineDistance(lat, lon, node.latitude, node.longitude)
            distance <= radiusMeters
        }
    }

    // ==================== Vector KNN Search ====================

    /**
     * Find similar scenes using sqlite-vec KNN search.
     * @param queryEmbedding: BLOB (ByteArray) of image embedding
     * @param topK: number of results
     * @return List of (SceneNode, distance) pairs
     */
    fun findSimilarScenes(queryEmbedding: ByteArray, topK: Int = 5): List<Pair<SceneNode, Float>> {
        try {
            val db = this.readableDatabase
            // ★ sqlite-vec knn: 바인딩 파라미터 LIMIT ?는 인식 안됨 → WHERE k = ? 사용
            val cursor = db.rawQuery(
                """SELECT v.scene_id, v.distance, s.*
                   FROM $VEC_TABLE_IMAGES v
                   JOIN $TABLE_SCENE s ON s.$COLUMN_ID = v.scene_id
                   WHERE v.image_embedding MATCH ?
                   AND k = ?""",
                arrayOf(queryEmbedding, topK.toString())
            )

            val results = mutableListOf<Pair<SceneNode, Float>>()
            while (cursor.moveToNext()) {
                val distance = cursor.getFloat(1)
                // SceneNode columns start after scene_id and distance (offset 2)
                val node = SceneNode(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                    timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
                    label = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_LABEL)),
                    embedding = cursor.getBlob(cursor.getColumnIndexOrThrow(COLUMN_EMBEDDING)),
                    latitude = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE))) null else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE)),
                    longitude = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))) null else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))
                )
                results.add(node to distance)
            }
            cursor.close()
            return results
        } catch (e: Exception) {
            Log.e(TAG, "findSimilarScenes KNN failed: ${e.message}", e)
            return emptyList()
        }
    }

    /**
     * Find similar voice logs using sqlite-vec KNN search.
     */
    fun findSimilarVoiceLogs(queryAudioEmbedding: ByteArray, topK: Int = 5): List<Pair<VoiceLog, Float>> {
        try {
            val db = this.readableDatabase
            val cursor = db.rawQuery(
                """SELECT v.voice_id, v.distance, vl.*
                   FROM $VEC_TABLE_AUDIO v
                   JOIN $TABLE_VOICE vl ON vl.$COLUMN_ID = v.voice_id
                   WHERE v.audio_embedding MATCH ?
                   ORDER BY v.distance LIMIT ?""",
                arrayOf(queryAudioEmbedding, topK.toString())
            )

            val results = mutableListOf<Pair<VoiceLog, Float>>()
            while (cursor.moveToNext()) {
                val distance = cursor.getFloat(1)
                val log = VoiceLog(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                    timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
                    transcript = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TRANSCRIPT)),
                    audioEmbedding = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_AUDIO_EMBEDDING))) null else cursor.getBlob(cursor.getColumnIndexOrThrow(COLUMN_AUDIO_EMBEDDING)),
                    emotion = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_EMOTION))) null else cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_EMOTION)),
                    emotionScore = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_EMOTION_SCORE))) null else cursor.getFloat(cursor.getColumnIndexOrThrow(COLUMN_EMOTION_SCORE)),
                    latitude = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE))) null else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE)),
                    longitude = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))) null else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))
                )
                results.add(log to distance)
            }
            cursor.close()
            return results
        } catch (e: Exception) {
            Log.e(TAG, "findSimilarVoiceLogs KNN failed: ${e.message}", e)
            return emptyList()
        }
    }

    fun getVoiceLogsByEmotion(emotion: String, minScore: Float = 0.5f): List<VoiceLog> {
        val db = this.readableDatabase
        val cursor = db.query(
            TABLE_VOICE, null,
            "$COLUMN_EMOTION = ? AND $COLUMN_EMOTION_SCORE >= ?",
            arrayOf(emotion, minScore.toString()),
            null, null, "$COLUMN_TIMESTAMP DESC"
        )

        val logs = mutableListOf<VoiceLog>()
        while (cursor.moveToNext()) {
            logs.add(cursorToVoiceLog(cursor))
        }
        cursor.close()
        return logs
    }

    // ==================== Audio Event Queries ====================

    fun getAudioEventsBetween(startTime: Long, endTime: Long): List<AudioEventRecord> {
        val db = this.readableDatabase
        val cursor = db.query(
            TABLE_AUDIO_EVENTS, null,
            "$COLUMN_TIMESTAMP >= ? AND $COLUMN_TIMESTAMP <= ?",
            arrayOf(startTime.toString(), endTime.toString()),
            null, null, "$COLUMN_TIMESTAMP DESC"
        )
        val records = mutableListOf<AudioEventRecord>()
        while (cursor.moveToNext()) {
            records.add(cursorToAudioEventRecord(cursor))
        }
        cursor.close()
        return records
    }

    fun findSimilarAudioEvents(queryEmbedding: ByteArray, topK: Int = 5): List<Pair<AudioEventRecord, Float>> {
        try {
            val db = this.readableDatabase
            val cursor = db.rawQuery(
                """SELECT v.event_id, v.distance, ae.*
                   FROM $VEC_TABLE_AUDIO_EVENTS v
                   JOIN $TABLE_AUDIO_EVENTS ae ON ae.$COLUMN_ID = v.event_id
                   WHERE v.event_embedding MATCH ?
                   ORDER BY v.distance LIMIT ?""",
                arrayOf(queryEmbedding, topK.toString())
            )

            val results = mutableListOf<Pair<AudioEventRecord, Float>>()
            while (cursor.moveToNext()) {
                val distance = cursor.getFloat(1)
                val record = AudioEventRecord(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                    timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
                    labels = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_LABELS)),
                    scores = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SCORES)),
                    embedding = cursor.getBlob(cursor.getColumnIndexOrThrow(COLUMN_EMBEDDING)),
                    latitude = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE))) null else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE)),
                    longitude = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))) null else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))
                )
                results.add(record to distance)
            }
            cursor.close()
            return results
        } catch (e: Exception) {
            Log.e(TAG, "findSimilarAudioEvents KNN failed: ${e.message}", e)
            return emptyList()
        }
    }

    // ==================== Person Methods ====================

    fun insertPerson(name: String?, relationship: String? = null): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put("name", name)
            put("relationship", relationship)
            put("registered_at", System.currentTimeMillis())
            put("photo_count", 0)
        }
        return db.insert(TABLE_PERSONS, null, values)
    }

    fun insertPersonFace(personId: Long, embedding: ByteArray, faceCropPath: String? = null, qualityScore: Float = 0f): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put("person_id", personId)
            put(COLUMN_EMBEDDING, embedding)
            put("face_crop_path", faceCropPath)
            put("captured_at", System.currentTimeMillis())
            put("quality_score", qualityScore)
        }
        val id = db.insert(TABLE_PERSON_FACES, null, values)

        if (id > 0 && embedding.size == FACE_EMBEDDING_DIM * 4) {
            try {
                db.execSQL(
                    "INSERT INTO $VEC_TABLE_PERSON_FACES(face_id, face_embedding) VALUES (?, ?)",
                    arrayOf(id, embedding)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert face vec: ${e.message}")
            }
        }

        // Update photo count
        db.execSQL("UPDATE $TABLE_PERSONS SET photo_count = photo_count + 1 WHERE $COLUMN_ID = ?", arrayOf(personId))

        return id
    }

    fun findSimilarFaces(queryEmbedding: ByteArray, topK: Int = 3): List<Pair<PersonFaceRecord, Float>> {
        try {
            val db = this.readableDatabase
            val cursor = db.rawQuery(
                """SELECT v.face_id, v.distance, pf.*
                   FROM $VEC_TABLE_PERSON_FACES v
                   JOIN $TABLE_PERSON_FACES pf ON pf.$COLUMN_ID = v.face_id
                   WHERE v.face_embedding MATCH ?
                   ORDER BY v.distance LIMIT ?""",
                arrayOf(queryEmbedding, topK.toString())
            )

            val results = mutableListOf<Pair<PersonFaceRecord, Float>>()
            while (cursor.moveToNext()) {
                val distance = cursor.getFloat(1)
                val record = PersonFaceRecord(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                    personId = cursor.getLong(cursor.getColumnIndexOrThrow("person_id")),
                    embedding = cursor.getBlob(cursor.getColumnIndexOrThrow(COLUMN_EMBEDDING)),
                    faceCropPath = if (cursor.isNull(cursor.getColumnIndexOrThrow("face_crop_path"))) null else cursor.getString(cursor.getColumnIndexOrThrow("face_crop_path")),
                    capturedAt = cursor.getLong(cursor.getColumnIndexOrThrow("captured_at")),
                    qualityScore = cursor.getFloat(cursor.getColumnIndexOrThrow("quality_score"))
                )
                results.add(record to distance)
            }
            cursor.close()
            return results
        } catch (e: Exception) {
            Log.e(TAG, "findSimilarFaces KNN failed: ${e.message}", e)
            return emptyList()
        }
    }

    fun getPersonById(personId: Long): PersonRecord? {
        val db = this.readableDatabase
        val cursor = db.query(TABLE_PERSONS, null, "$COLUMN_ID = ?", arrayOf(personId.toString()), null, null, null)
        var person: PersonRecord? = null
        if (cursor.moveToFirst()) {
            person = PersonRecord(
                id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                name = if (cursor.isNull(cursor.getColumnIndexOrThrow("name"))) null else cursor.getString(cursor.getColumnIndexOrThrow("name")),
                relationship = if (cursor.isNull(cursor.getColumnIndexOrThrow("relationship"))) null else cursor.getString(cursor.getColumnIndexOrThrow("relationship")),
                registeredAt = cursor.getLong(cursor.getColumnIndexOrThrow("registered_at")),
                photoCount = cursor.getInt(cursor.getColumnIndexOrThrow("photo_count")),
                notes = if (cursor.isNull(cursor.getColumnIndexOrThrow("notes"))) null else cursor.getString(cursor.getColumnIndexOrThrow("notes"))
            )
        }
        cursor.close()
        return person
    }

    fun getAllPersons(): List<PersonRecord> {
        val db = this.readableDatabase
        val cursor = db.query(TABLE_PERSONS, null, null, null, null, null, "registered_at DESC")
        val persons = mutableListOf<PersonRecord>()
        while (cursor.moveToNext()) {
            persons.add(PersonRecord(
                id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                name = if (cursor.isNull(cursor.getColumnIndexOrThrow("name"))) null else cursor.getString(cursor.getColumnIndexOrThrow("name")),
                relationship = if (cursor.isNull(cursor.getColumnIndexOrThrow("relationship"))) null else cursor.getString(cursor.getColumnIndexOrThrow("relationship")),
                registeredAt = cursor.getLong(cursor.getColumnIndexOrThrow("registered_at")),
                photoCount = cursor.getInt(cursor.getColumnIndexOrThrow("photo_count")),
                notes = if (cursor.isNull(cursor.getColumnIndexOrThrow("notes"))) null else cursor.getString(cursor.getColumnIndexOrThrow("notes"))
            ))
        }
        cursor.close()
        return persons
    }

    fun updatePersonName(personId: Long, name: String, relationship: String? = null) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put("name", name)
            if (relationship != null) put("relationship", relationship)
        }
        db.update(TABLE_PERSONS, values, "$COLUMN_ID = ?", arrayOf(personId.toString()))
    }

    fun insertInteraction(personId: Long, timestamp: Long, transcript: String? = null,
                          expression: String? = null, expressionScore: Float? = null,
                          audioEmotion: String? = null, sceneNodeId: Long? = null,
                          audioEventId: Long? = null, lat: Double? = null, lon: Double? = null): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put("person_id", personId)
            put(COLUMN_TIMESTAMP, timestamp)
            put(COLUMN_TRANSCRIPT, transcript)
            put("expression", expression)
            put("expression_score", expressionScore)
            put("audio_emotion", audioEmotion)
            put("scene_node_id", sceneNodeId)
            put("audio_event_id", audioEventId)
            put(COLUMN_LATITUDE, lat)
            put(COLUMN_LONGITUDE, lon)
        }
        return db.insert(TABLE_INTERACTIONS, null, values)
    }

    fun getInteractionsByPerson(personId: Long, limit: Int = 50): List<InteractionRecord> {
        val db = this.readableDatabase
        val cursor = db.query(TABLE_INTERACTIONS, null, "person_id = ?",
            arrayOf(personId.toString()), null, null, "$COLUMN_TIMESTAMP DESC", limit.toString())
        val records = mutableListOf<InteractionRecord>()
        while (cursor.moveToNext()) {
            records.add(InteractionRecord(
                id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                personId = cursor.getLong(cursor.getColumnIndexOrThrow("person_id")),
                timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
                transcript = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_TRANSCRIPT))) null else cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TRANSCRIPT)),
                expression = if (cursor.isNull(cursor.getColumnIndexOrThrow("expression"))) null else cursor.getString(cursor.getColumnIndexOrThrow("expression")),
                expressionScore = if (cursor.isNull(cursor.getColumnIndexOrThrow("expression_score"))) null else cursor.getFloat(cursor.getColumnIndexOrThrow("expression_score")),
                audioEmotion = if (cursor.isNull(cursor.getColumnIndexOrThrow("audio_emotion"))) null else cursor.getString(cursor.getColumnIndexOrThrow("audio_emotion")),
                sceneNodeId = if (cursor.isNull(cursor.getColumnIndexOrThrow("scene_node_id"))) null else cursor.getLong(cursor.getColumnIndexOrThrow("scene_node_id")),
                audioEventId = if (cursor.isNull(cursor.getColumnIndexOrThrow("audio_event_id"))) null else cursor.getLong(cursor.getColumnIndexOrThrow("audio_event_id")),
                latitude = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE))) null else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE)),
                longitude = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))) null else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))
            ))
        }
        cursor.close()
        return records
    }

    // ==================== Person Voice Methods ====================

    fun insertPersonVoice(personId: Long, voiceEmbedding: ByteArray, transcript: String?): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put("person_id", personId)
            put("voice_embedding", voiceEmbedding)
            put("captured_at", System.currentTimeMillis())
            put(COLUMN_TRANSCRIPT, transcript)
        }
        val voiceId = db.insert(TABLE_PERSON_VOICES, null, values)

        // Insert into vec table for KNN search
        if (voiceId > 0 && voiceEmbedding.size == VOICE_EMBEDDING_DIM * 4) {
            try {
                db.execSQL(
                    "INSERT INTO $VEC_TABLE_PERSON_VOICES(voice_id, voice_embedding) VALUES (?, ?)",
                    arrayOf(voiceId, voiceEmbedding)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert voice into vec table: ${e.message}")
            }
        }
        return voiceId
    }

    fun findSimilarVoices(queryEmbedding: ByteArray, topK: Int = 3): List<Pair<PersonVoiceRecord, Float>> {
        val results = mutableListOf<Pair<PersonVoiceRecord, Float>>()
        val db = this.readableDatabase
        try {
            val cursor = db.rawQuery(
                """SELECT v.voice_id, v.distance, pv.person_id, pv.voice_embedding, pv.captured_at, pv.$COLUMN_TRANSCRIPT
                   FROM $VEC_TABLE_PERSON_VOICES v
                   JOIN $TABLE_PERSON_VOICES pv ON pv.$COLUMN_ID = v.voice_id
                   WHERE v.voice_embedding MATCH ? AND k = ?
                   ORDER BY v.distance""",
                arrayOf(queryEmbedding, topK.toString())
            )
            while (cursor.moveToNext()) {
                val distance = cursor.getFloat(cursor.getColumnIndexOrThrow("distance"))
                val record = PersonVoiceRecord(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("voice_id")),
                    personId = cursor.getLong(cursor.getColumnIndexOrThrow("person_id")),
                    voiceEmbedding = cursor.getBlob(cursor.getColumnIndexOrThrow("voice_embedding")),
                    capturedAt = cursor.getLong(cursor.getColumnIndexOrThrow("captured_at")),
                    transcript = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_TRANSCRIPT))) null
                        else cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TRANSCRIPT))
                )
                results.add(record to distance)
            }
            cursor.close()
        } catch (e: Exception) {
            Log.e(TAG, "Voice KNN search failed: ${e.message}")
        }
        return results
    }

    // ==================== Utilities ====================

    private fun cursorToSceneNode(cursor: android.database.Cursor): SceneNode {
        return SceneNode(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
            label = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_LABEL)),
            embedding = cursor.getBlob(cursor.getColumnIndexOrThrow(COLUMN_EMBEDDING)),
            latitude = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE))) null else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE)),
            longitude = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))) null else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))
        )
    }

    private fun cursorToAudioEventRecord(cursor: android.database.Cursor): AudioEventRecord {
        return AudioEventRecord(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
            labels = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_LABELS)),
            scores = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SCORES)),
            embedding = cursor.getBlob(cursor.getColumnIndexOrThrow(COLUMN_EMBEDDING)),
            latitude = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE))) null else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE)),
            longitude = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))) null else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))
        )
    }

    private fun cursorToVoiceLog(cursor: android.database.Cursor): VoiceLog {
        return VoiceLog(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
            transcript = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TRANSCRIPT)),
            audioEmbedding = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_AUDIO_EMBEDDING))) null else cursor.getBlob(cursor.getColumnIndexOrThrow(COLUMN_AUDIO_EMBEDDING)),
            emotion = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_EMOTION))) null else cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_EMOTION)),
            emotionScore = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_EMOTION_SCORE))) null else cursor.getFloat(cursor.getColumnIndexOrThrow(COLUMN_EMOTION_SCORE)),
            latitude = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE))) null else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE)),
            longitude = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))) null else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))
        )
    }

    /**
     * 장소 시그니처 테이블 생성 (크로스 세션 장소 인식용).
     *
     * 6축 다중 모달 시그니처: GPS + 방위 + 층수 + 이동경로 + 시각 임베딩 + 씬 그래프
     */
    private fun createPlaceSignatureTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_PLACE_SIGNATURES (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                gps_latitude REAL NOT NULL,
                gps_longitude REAL NOT NULL,
                gps_accuracy REAL DEFAULT 0,
                heading_degrees REAL NOT NULL,
                vio_y_displacement REAL DEFAULT 0,
                estimated_floor INTEGER DEFAULT 0,
                path_distance_from_gps REAL DEFAULT 0,
                path_summary_json TEXT,
                visual_embedding BLOB,
                scene_labels TEXT DEFAULT '',
                created_at INTEGER NOT NULL,
                last_matched_at INTEGER DEFAULT 0,
                match_count INTEGER DEFAULT 0,
                anchor_ids TEXT DEFAULT ''
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_place_sig_gps ON $TABLE_PLACE_SIGNATURES(gps_latitude, gps_longitude)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_place_sig_floor ON $TABLE_PLACE_SIGNATURES(estimated_floor)")

        // sqlite-vec 벡터 테이블 (시각 임베딩 코사인 검색)
        try {
            db.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS $VEC_TABLE_PLACE_VISUAL USING vec0(
                    place_id INTEGER PRIMARY KEY,
                    visual_embedding FLOAT[$PLACE_VISUAL_DIM] distance_metric=cosine
                )
            """.trimIndent())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create vec_place_visual: ${e.message}", e)
        }

        Log.i(TAG, "Place signature tables created")
    }

    // ═══════════════════════════════════════════════════
    //  Place Signature CRUD (Cross-Session Recognition)
    // ═══════════════════════════════════════════════════

    /**
     * 장소 시그니처 저장.
     *
     * @return 삽입된 row ID, 실패 시 -1
     */
    override fun savePlaceSignature(
        gpsLat: Double, gpsLon: Double, gpsAccuracy: Float,
        headingDegrees: Float,
        vioYDisplacement: Float, estimatedFloor: Int,
        pathDistanceFromGps: Float, pathSummaryJson: String?,
        visualEmbedding: ByteArray?,
        sceneLabels: String,
        anchorIds: String
    ): Long {
        return try {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("gps_latitude", gpsLat)
                put("gps_longitude", gpsLon)
                put("gps_accuracy", gpsAccuracy.toDouble())
                put("heading_degrees", headingDegrees.toDouble())
                put("vio_y_displacement", vioYDisplacement.toDouble())
                put("estimated_floor", estimatedFloor)
                put("path_distance_from_gps", pathDistanceFromGps.toDouble())
                if (pathSummaryJson != null) put("path_summary_json", pathSummaryJson)
                if (visualEmbedding != null) put("visual_embedding", visualEmbedding)
                put("scene_labels", sceneLabels)
                put("created_at", now)
                put("last_matched_at", 0)
                put("match_count", 0)
                put("anchor_ids", anchorIds)
            }
            val id = db.insertOrThrow(TABLE_PLACE_SIGNATURES, null, values)

            // 시각 임베딩 벡터 테이블에도 삽입
            if (id > 0 && visualEmbedding != null && visualEmbedding.size == PLACE_VISUAL_DIM * 4) {
                try {
                    db.execSQL(
                        "INSERT INTO $VEC_TABLE_PLACE_VISUAL(place_id, visual_embedding) VALUES (?, ?)",
                        arrayOf(id, visualEmbedding)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to insert place visual vec: ${e.message}")
                }
            }

            Log.d(TAG, "Place signature saved: id=$id, floor=$estimatedFloor, labels=$sceneLabels")
            id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save place signature: ${e.message}", e)
            -1
        }
    }

    /**
     * GPS 근처 장소 시그니처 로드.
     *
     * @param lat 현재 GPS 위도
     * @param lon 현재 GPS 경도
     * @param radiusM 검색 반경 (미터)
     * @return 근처 시그니처 목록
     */
    override fun loadPlaceSignaturesNear(lat: Double, lon: Double, radiusM: Double): List<PlaceSignatureRecord> {
        val results = mutableListOf<PlaceSignatureRecord>()
        try {
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT * FROM $TABLE_PLACE_SIGNATURES",
                null
            )
            cursor.use {
                while (it.moveToNext()) {
                    val sigLat = it.getDouble(it.getColumnIndexOrThrow("gps_latitude"))
                    val sigLon = it.getDouble(it.getColumnIndexOrThrow("gps_longitude"))
                    val distance = haversineDistance(lat, lon, sigLat, sigLon)
                    if (distance <= radiusM) {
                        results.add(cursorToPlaceSignature(it))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load place signatures: ${e.message}", e)
        }
        return results
    }

    /**
     * 시각 임베딩 KNN 검색으로 유사 장소 찾기.
     *
     * @param queryEmbedding 현재 프레임의 시각 임베딩 (ByteArray, 1280*4 bytes)
     * @param topK 최대 결과 수
     * @return (PlaceSignatureRecord, cosine distance) 쌍 목록
     */
    override fun findSimilarPlaces(queryEmbedding: ByteArray, topK: Int): List<Pair<PlaceSignatureRecord, Float>> {
        try {
            val db = readableDatabase
            val cursor = db.rawQuery(
                """SELECT v.place_id, v.distance, p.*
                   FROM $VEC_TABLE_PLACE_VISUAL v
                   JOIN $TABLE_PLACE_SIGNATURES p ON p.$COLUMN_ID = v.place_id
                   WHERE v.visual_embedding MATCH ?
                   ORDER BY v.distance LIMIT ?""",
                arrayOf(queryEmbedding, topK.toString())
            )

            val results = mutableListOf<Pair<PlaceSignatureRecord, Float>>()
            cursor.use {
                while (it.moveToNext()) {
                    val distance = it.getFloat(1)
                    results.add(cursorToPlaceSignature(it) to distance)
                }
            }
            return results
        } catch (e: Exception) {
            Log.e(TAG, "findSimilarPlaces KNN failed: ${e.message}", e)
            return emptyList()
        }
    }

    /**
     * 매칭 성공 시 시그니처 갱신 (matchCount 증가 + lastMatchedAt 갱신).
     */
    override fun updatePlaceSignatureMatched(id: Long) {
        try {
            val db = writableDatabase
            db.execSQL(
                "UPDATE $TABLE_PLACE_SIGNATURES SET match_count = match_count + 1, last_matched_at = ? WHERE $COLUMN_ID = ?",
                arrayOf(System.currentTimeMillis(), id)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update place signature match: ${e.message}", e)
        }
    }

    /**
     * 시그니처에 앵커 ID 추가.
     */
    fun appendPlaceSignatureAnchorId(signatureId: Long, anchorDbId: Long) {
        try {
            val db = writableDatabase
            val cursor = db.rawQuery(
                "SELECT anchor_ids FROM $TABLE_PLACE_SIGNATURES WHERE $COLUMN_ID = ?",
                arrayOf(signatureId.toString())
            )
            cursor.use {
                if (it.moveToFirst()) {
                    val existing = it.getString(0) ?: ""
                    val ids = existing.split(",").filter { s -> s.isNotBlank() }.toMutableList()
                    val newId = anchorDbId.toString()
                    if (newId !in ids) {
                        ids.add(newId)
                        val values = ContentValues().apply {
                            put("anchor_ids", ids.joinToString(","))
                        }
                        db.update(TABLE_PLACE_SIGNATURES, values, "$COLUMN_ID = ?", arrayOf(signatureId.toString()))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append anchor id: ${e.message}", e)
        }
    }

    /**
     * 오래된 시그니처 정리.
     */
    fun prunePlaceSignatures(maxAgeDays: Int = 90): Int {
        return try {
            val cutoff = System.currentTimeMillis() - maxAgeDays * 24 * 60 * 60 * 1000L
            val db = writableDatabase
            // 확정(match_count >= 3)은 90일, 미확정은 30일
            val deleted = db.delete(TABLE_PLACE_SIGNATURES,
                "(match_count < 3 AND created_at < ?) OR (created_at < ?)",
                arrayOf(
                    (System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000).toString(),
                    cutoff.toString()
                ))
            if (deleted > 0) Log.d(TAG, "Pruned $deleted old place signatures")
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prune place signatures: ${e.message}", e)
            0
        }
    }

    /** 전체 시그니처 수 */
    fun getPlaceSignatureCount(): Int {
        return try {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_PLACE_SIGNATURES", null)
            cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (_: Exception) { 0 }
    }

    private fun cursorToPlaceSignature(cursor: android.database.Cursor): PlaceSignatureRecord {
        return PlaceSignatureRecord(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
            gpsLatitude = cursor.getDouble(cursor.getColumnIndexOrThrow("gps_latitude")),
            gpsLongitude = cursor.getDouble(cursor.getColumnIndexOrThrow("gps_longitude")),
            gpsAccuracy = cursor.getFloat(cursor.getColumnIndexOrThrow("gps_accuracy")),
            headingDegrees = cursor.getFloat(cursor.getColumnIndexOrThrow("heading_degrees")),
            vioYDisplacement = cursor.getFloat(cursor.getColumnIndexOrThrow("vio_y_displacement")),
            estimatedFloor = cursor.getInt(cursor.getColumnIndexOrThrow("estimated_floor")),
            pathDistanceFromGps = cursor.getFloat(cursor.getColumnIndexOrThrow("path_distance_from_gps")),
            pathSummaryJson = if (cursor.isNull(cursor.getColumnIndexOrThrow("path_summary_json"))) null
                else cursor.getString(cursor.getColumnIndexOrThrow("path_summary_json")),
            visualEmbedding = if (cursor.isNull(cursor.getColumnIndexOrThrow("visual_embedding"))) null
                else cursor.getBlob(cursor.getColumnIndexOrThrow("visual_embedding")),
            sceneLabels = cursor.getString(cursor.getColumnIndexOrThrow("scene_labels")) ?: "",
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            lastMatchedAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_matched_at")),
            matchCount = cursor.getInt(cursor.getColumnIndexOrThrow("match_count")),
            anchorIds = cursor.getString(cursor.getColumnIndexOrThrow("anchor_ids")) ?: ""
        )
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return R * c
    }

    // ═══════════════════════════════════════════════════
    //  Spatial Anchor CRUD (Level 1-2 Persistence)
    // ═══════════════════════════════════════════════════

    /**
     * 앵커를 DB에 저장 (Level 1: SESSION_WITH_DB, Level 2: CROSS_SESSION).
     *
     * @return 삽입된 row ID, 실패 시 -1
     */
    override fun saveSpatialAnchor(
        label: String, type: String,
        gpsLat: Double?, gpsLon: Double?,
        localX: Float, localY: Float, localZ: Float,
        depthMeters: Float, depthSource: String,
        confidence: Float, seenCount: Int,
        createdAt: Long, lastSeenAt: Long,
        visualEmbedding: ByteArray?,
        metadata: String?
    ): Long {
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("label", label)
                put("type", type)
                if (gpsLat != null) put("gps_latitude", gpsLat)
                if (gpsLon != null) put("gps_longitude", gpsLon)
                put("local_x", localX.toDouble())
                put("local_y", localY.toDouble())
                put("local_z", localZ.toDouble())
                put("depth_meters", depthMeters.toDouble())
                put("depth_source", depthSource)
                put("confidence", confidence.toDouble())
                put("seen_count", seenCount)
                put("created_at", createdAt)
                put("last_seen_at", lastSeenAt)
                if (visualEmbedding != null) put("visual_embedding", visualEmbedding)
                if (metadata != null) put("metadata", metadata)
            }
            db.insertOrThrow(TABLE_SPATIAL_ANCHORS, null, values)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save spatial anchor: ${e.message}", e)
            -1
        }
    }

    /**
     * GPS 근처 앵커 로드 (반경 내, Level 2 크로스 세션 복원용).
     *
     * @param lat 현재 GPS 위도
     * @param lon 현재 GPS 경도
     * @param radiusM 검색 반경 (미터)
     * @return 근처 앵커 목록
     */
    override fun loadSpatialAnchorsNear(lat: Double, lon: Double, radiusM: Double): List<SpatialAnchorRecord> {
        val results = mutableListOf<SpatialAnchorRecord>()
        try {
            val db = readableDatabase
            // GPS가 null이 아닌 앵커만 대상 (Level 2 저장 시 GPS 포함)
            val cursor = db.rawQuery(
                "SELECT * FROM $TABLE_SPATIAL_ANCHORS WHERE gps_latitude IS NOT NULL AND gps_longitude IS NOT NULL",
                null
            )
            cursor.use {
                while (it.moveToNext()) {
                    val anchorLat = it.getDouble(it.getColumnIndexOrThrow("gps_latitude"))
                    val anchorLon = it.getDouble(it.getColumnIndexOrThrow("gps_longitude"))
                    val distance = haversineDistance(lat, lon, anchorLat, anchorLon)
                    if (distance <= radiusM) {
                        results.add(cursorToSpatialAnchor(it))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load spatial anchors: ${e.message}", e)
        }
        return results
    }

    /**
     * 앵커 관측 횟수/시각 업데이트.
     */
    fun updateSpatialAnchorSeen(id: Long, seenCount: Int, lastSeenAt: Long) {
        try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("seen_count", seenCount)
                put("last_seen_at", lastSeenAt)
            }
            db.update(TABLE_SPATIAL_ANCHORS, values, "$COLUMN_ID = ?", arrayOf(id.toString()))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update spatial anchor: ${e.message}", e)
        }
    }

    /**
     * 오래된 앵커 정리 (maxAgeDays 초과 + seenCount < minSeenCount).
     */
    override fun pruneSpatialAnchors(maxAgeDays: Int, minSeenCount: Int): Int {
        return try {
            val cutoff = System.currentTimeMillis() - maxAgeDays * 24 * 60 * 60 * 1000L
            val db = writableDatabase
            db.delete(TABLE_SPATIAL_ANCHORS,
                "last_seen_at < ? AND seen_count < ?",
                arrayOf(cutoff.toString(), minSeenCount.toString()))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prune spatial anchors: ${e.message}", e)
            0
        }
    }

    /** 전체 앵커 수 조회. */
    override fun getSpatialAnchorCount(): Int {
        return try {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_SPATIAL_ANCHORS", null)
            cursor.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        } catch (e: Exception) { 0 }
    }

    // SpatialAnchorRecord 타입은 :spatial 모듈의 com.xreal.nativear.spatial.SpatialAnchorRecord을 사용

    // ═══════════════════════════════════════════════════
    //  Interaction Templates (Hand Tracking + Interactive AR)
    // ═══════════════════════════════════════════════════

    private fun createInteractionTemplateTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_INTERACTION_TEMPLATES (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                trigger_type TEXT NOT NULL,
                target_filter TEXT DEFAULT '*',
                actions_json TEXT NOT NULL,
                use_count INTEGER DEFAULT 0,
                success_rate REAL DEFAULT 1.0,
                context_tags TEXT DEFAULT '',
                creator_persona TEXT DEFAULT '',
                created_at INTEGER NOT NULL,
                last_used_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_interaction_tpl_trigger ON $TABLE_INTERACTION_TEMPLATES(trigger_type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_interaction_tpl_tags ON $TABLE_INTERACTION_TEMPLATES(context_tags)")
        Log.i(TAG, "Interaction templates table created")
    }

    fun insertInteractionTemplate(
        name: String, triggerType: String, targetFilter: String,
        actionsJson: String, contextTags: String, creatorPersona: String
    ): Long {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("name", name)
            put("trigger_type", triggerType)
            put("target_filter", targetFilter)
            put("actions_json", actionsJson)
            put("use_count", 0)
            put("success_rate", 1.0)
            put("context_tags", contextTags)
            put("creator_persona", creatorPersona)
            put("created_at", now)
            put("last_used_at", now)
        }
        return db.insert(TABLE_INTERACTION_TEMPLATES, null, values)
    }

    fun updateInteractionTemplateUsage(id: Long, newUseCount: Int, newSuccessRate: Float) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("use_count", newUseCount)
            put("success_rate", newSuccessRate)
            put("last_used_at", System.currentTimeMillis())
        }
        db.update(TABLE_INTERACTION_TEMPLATES, values, "$COLUMN_ID = ?", arrayOf(id.toString()))
    }

    fun queryInteractionTemplates(contextTags: List<String>? = null): List<InteractionTemplateRecord> {
        val db = readableDatabase
        val results = mutableListOf<InteractionTemplateRecord>()

        val query = if (contextTags.isNullOrEmpty()) {
            "SELECT * FROM $TABLE_INTERACTION_TEMPLATES ORDER BY use_count DESC, last_used_at DESC LIMIT 50"
        } else {
            val tagConditions = contextTags.joinToString(" OR ") { "context_tags LIKE '%$it%'" }
            "SELECT * FROM $TABLE_INTERACTION_TEMPLATES WHERE $tagConditions ORDER BY use_count DESC LIMIT 50"
        }

        val cursor = db.rawQuery(query, null)
        while (cursor.moveToNext()) {
            results.add(cursorToInteractionTemplate(cursor))
        }
        cursor.close()
        return results
    }

    fun deleteOldInteractionTemplates(maxAgeDays: Int = 30, minUseCount: Int = 2) {
        val db = writableDatabase
        val cutoff = System.currentTimeMillis() - (maxAgeDays * 24 * 60 * 60 * 1000L)
        db.delete(TABLE_INTERACTION_TEMPLATES,
            "last_used_at < ? AND use_count < ?",
            arrayOf(cutoff.toString(), minUseCount.toString()))
    }

    fun upsertInteractionTemplate(
        name: String, triggerType: String, targetFilter: String,
        actionsJson: String, contextTags: String, creatorPersona: String,
        useCount: Int, successRate: Float
    ): Long {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.execSQL("""
            INSERT OR REPLACE INTO $TABLE_INTERACTION_TEMPLATES
            (name, trigger_type, target_filter, actions_json, use_count, success_rate, context_tags, creator_persona, created_at, last_used_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(), arrayOf(name, triggerType, targetFilter, actionsJson, useCount, successRate, contextTags, creatorPersona, now, now))

        // Return the ID of the upserted row
        val cursor = db.rawQuery("SELECT last_insert_rowid()", null)
        val id = if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        cursor.close()
        return id
    }

    data class InteractionTemplateRecord(
        val id: Long,
        val name: String,
        val triggerType: String,
        val targetFilter: String,
        val actionsJson: String,
        val useCount: Int,
        val successRate: Float,
        val contextTags: String,
        val creatorPersona: String,
        val createdAt: Long,
        val lastUsedAt: Long
    )

    private fun cursorToInteractionTemplate(cursor: android.database.Cursor): InteractionTemplateRecord {
        return InteractionTemplateRecord(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
            name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
            triggerType = cursor.getString(cursor.getColumnIndexOrThrow("trigger_type")),
            targetFilter = cursor.getString(cursor.getColumnIndexOrThrow("target_filter")),
            actionsJson = cursor.getString(cursor.getColumnIndexOrThrow("actions_json")),
            useCount = cursor.getInt(cursor.getColumnIndexOrThrow("use_count")),
            successRate = cursor.getFloat(cursor.getColumnIndexOrThrow("success_rate")),
            contextTags = cursor.getString(cursor.getColumnIndexOrThrow("context_tags")) ?: "",
            creatorPersona = cursor.getString(cursor.getColumnIndexOrThrow("creator_persona")) ?: "",
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            lastUsedAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_used_at"))
        )
    }

    private fun cursorToSpatialAnchor(cursor: android.database.Cursor): SpatialAnchorRecord {
        return SpatialAnchorRecord(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
            label = cursor.getString(cursor.getColumnIndexOrThrow("label")),
            type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
            gpsLatitude = if (cursor.isNull(cursor.getColumnIndexOrThrow("gps_latitude"))) null else cursor.getDouble(cursor.getColumnIndexOrThrow("gps_latitude")),
            gpsLongitude = if (cursor.isNull(cursor.getColumnIndexOrThrow("gps_longitude"))) null else cursor.getDouble(cursor.getColumnIndexOrThrow("gps_longitude")),
            localX = cursor.getFloat(cursor.getColumnIndexOrThrow("local_x")),
            localY = cursor.getFloat(cursor.getColumnIndexOrThrow("local_y")),
            localZ = cursor.getFloat(cursor.getColumnIndexOrThrow("local_z")),
            depthMeters = cursor.getFloat(cursor.getColumnIndexOrThrow("depth_meters")),
            depthSource = cursor.getString(cursor.getColumnIndexOrThrow("depth_source")),
            confidence = cursor.getFloat(cursor.getColumnIndexOrThrow("confidence")),
            seenCount = cursor.getInt(cursor.getColumnIndexOrThrow("seen_count")),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            lastSeenAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_at")),
            visualEmbedding = if (cursor.isNull(cursor.getColumnIndexOrThrow("visual_embedding"))) null else cursor.getBlob(cursor.getColumnIndexOrThrow("visual_embedding")),
            metadata = if (cursor.isNull(cursor.getColumnIndexOrThrow("metadata"))) null else cursor.getString(cursor.getColumnIndexOrThrow("metadata"))
        )
    }
}
