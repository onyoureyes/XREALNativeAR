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
        val latitude: Double? = null,
        val longitude: Double? = null
    )

    companion object {
        private const val DATABASE_NAME = "scene_graph_v2.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE_SCENE = "scene_nodes"
        private const val TABLE_VOICE = "voice_logs"
        
        private const val COLUMN_ID = "id"
        private const val COLUMN_TIMESTAMP = "timestamp"
        private const val COLUMN_LABEL = "label"
        private const val COLUMN_EMBEDDING = "embedding"
        private const val COLUMN_TRANSCRIPT = "transcript"
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
                    "$COLUMN_LATITUDE DOUBLE, " +
                    "$COLUMN_LONGITUDE DOUBLE)")
            db.execSQL(createVoiceTable)
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

    fun insertVoiceLog(timestamp: Long, transcript: String, lat: Double? = null, lon: Double? = null): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_TIMESTAMP, timestamp)
            put(COLUMN_TRANSCRIPT, transcript)
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
}
