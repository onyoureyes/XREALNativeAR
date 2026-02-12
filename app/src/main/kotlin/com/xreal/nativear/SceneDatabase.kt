package com.xreal.nativear

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SceneDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

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

    companion object {
        private const val DATABASE_NAME = "scene_graph_v2.db"
        private const val DATABASE_VERSION = 4
        private const val TABLE_SCENE = "scene_nodes"
        private const val TABLE_VOICE = "voice_logs"
        
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
            db.execSQL("ALTER TABLE $TABLE_VOICE ADD COLUMN $COLUMN_AUDIO_EMBEDDING BLOB")
        }
    }

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
        db.close()
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
        db.close()
        return id
    }

    fun getCount(): Int {
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_SCENE", null)
        var count = 0
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0)
        }
        cursor.close()
        db.close()
        return count
    }

    fun getAllNodes(): List<SceneNode> {
        val db = this.readableDatabase
        val cursor = db.query(TABLE_SCENE, null, null, null, null, null, "$COLUMN_TIMESTAMP DESC")
        val nodes = mutableListOf<SceneNode>()
        
        while (cursor.moveToNext()) {
            nodes.add(SceneNode(
                id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
                label = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_LABEL)),
                embedding = cursor.getBlob(cursor.getColumnIndexOrThrow(COLUMN_EMBEDDING)),
                latitude = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE))) null else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE)),
                longitude = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))) null else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))
            ))
        }
        cursor.close()
        db.close()
        return nodes
    }

    fun getAllVoiceLogs(): List<VoiceLog> {
        val db = this.readableDatabase
        val cursor = db.query(TABLE_VOICE, null, null, null, null, null, "$COLUMN_TIMESTAMP DESC")
        val logs = mutableListOf<VoiceLog>()
        
        while (cursor.moveToNext()) {
            logs.add(VoiceLog(
                id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
                transcript = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TRANSCRIPT)),
                audioEmbedding = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_AUDIO_EMBEDDING))) null else cursor.getBlob(cursor.getColumnIndexOrThrow(COLUMN_AUDIO_EMBEDDING)),
                emotion = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_EMOTION))) null else cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_EMOTION)),
                emotionScore = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_EMOTION_SCORE))) null else cursor.getFloat(cursor.getColumnIndexOrThrow(COLUMN_EMOTION_SCORE)),
                latitude = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE))) null else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE)),
                longitude = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))) null else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))
            ))
        }
        cursor.close()
        db.close()
        return logs
    }

    fun getNodesBetween(startTime: Long, endTime: Long): List<SceneNode> {
        val db = this.readableDatabase
        val cursor = db.query(
            TABLE_SCENE,
            null,
            "$COLUMN_TIMESTAMP >= ? AND $COLUMN_TIMESTAMP <= ?",
            arrayOf(startTime.toString(), endTime.toString()),
            null, null,
            "$COLUMN_TIMESTAMP DESC"
        )
        val nodes = mutableListOf<SceneNode>()
        
        while (cursor.moveToNext()) {
            nodes.add(SceneNode(
                id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
                label = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_LABEL)),
                embedding = cursor.getBlob(cursor.getColumnIndexOrThrow(COLUMN_EMBEDDING)),
                latitude = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE))) null else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE)),
                longitude = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))) null else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))
            ))
        }
        cursor.close()
        db.close()
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

    fun findSimilarScenes(queryEmbedding: ByteArray, topK: Int = 5): List<Pair<SceneNode, Float>> {
        val allNodes = getAllNodes()
        val queryVector = byteArrayToFloatArray(queryEmbedding)
        
        val similarities = allNodes.map { node ->
            val nodeVector = byteArrayToFloatArray(node.embedding)
            val similarity = cosineSimilarity(queryVector, nodeVector)
            node to similarity
        }.sortedByDescending { it.second }
        
        return similarities.take(topK)
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        
        val denominator = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denominator > 0f) dotProduct / denominator else 0f
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return R * c
    }

    private fun byteArrayToFloatArray(bytes: ByteArray): FloatArray {
        val floats = FloatArray(bytes.size / 4)
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in floats.indices) {
            floats[i] = buffer.getFloat()
        }
        return floats
    }

    fun findSimilarVoiceLogs(queryAudioEmbedding: ByteArray, topK: Int = 5): List<Pair<VoiceLog, Float>> {
        val allLogs = getAllVoiceLogs().filter { it.audioEmbedding != null }
        val queryVector = byteArrayToFloatArray(queryAudioEmbedding)
        
        val similarities = allLogs.map { log ->
            val logVector = byteArrayToFloatArray(log.audioEmbedding!!)
            val similarity = cosineSimilarity(queryVector, logVector)
            log to similarity
        }.sortedByDescending { it.second }
        
        return similarities.take(topK)
    }
    
    /**
     * Get voice logs filtered by emotion.
     * @param emotion: Emotion to filter by (angry, happy, sad, excited, neutral)
     * @param minScore: Minimum emotion confidence score (0.0 - 1.0)
     * @return List of voice logs matching the emotion criteria
     */
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
            logs.add(VoiceLog(
                id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
                transcript = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TRANSCRIPT)),
                audioEmbedding = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_AUDIO_EMBEDDING))) null else cursor.getBlob(cursor.getColumnIndexOrThrow(COLUMN_AUDIO_EMBEDDING)),
                emotion = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_EMOTION)),
                emotionScore = cursor.getFloat(cursor.getColumnIndexOrThrow(COLUMN_EMOTION_SCORE)),
                latitude = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE))) null else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE)),
                longitude = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))) null else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))
            ))
        }
        cursor.close()
        return logs
    }
}
