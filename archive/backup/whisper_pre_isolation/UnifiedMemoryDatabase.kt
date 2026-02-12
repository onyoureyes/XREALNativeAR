package com.xreal.nativear

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class UnifiedMemoryDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    data class MemoryNode(
        val id: Long = 0,
        val timestamp: Long,
        val role: String, // USER, GEMINI, CAMERA, WHISPER, SYSTEM_SUMMARY
        val content: String,
        val level: Int = 0,
        val parentId: Long? = null,
        val embedding: ByteArray? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val metadata: String? = null // JSON string
    )

    companion object {
        private const val DATABASE_NAME = "unified_memory_v3.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_MEMORY = "memory_nodes"

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
                "$COLUMN_METADATA TEXT)")
        db.execSQL(createTable)
        
        // Index for faster searching by level and parent
        db.execSQL("CREATE INDEX idx_level_parent ON $TABLE_MEMORY ($COLUMN_LEVEL, $COLUMN_PARENT_ID)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Upgrade logic if needed
    }

    fun insertNode(node: MemoryNode): Long {
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
        }
        val id = db.insert(TABLE_MEMORY, null, values)
        db.close()
        return id
    }

    fun updateNodeContent(id: Long, content: String) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_CONTENT, content)
        }
        db.update(TABLE_MEMORY, values, "$COLUMN_ID = ?", arrayOf(id.toString()))
        db.close()
    }

    fun getUnsummarizedNodes(level: Int, limit: Int = 50): List<MemoryNode> {
        val nodes = mutableListOf<MemoryNode>()
        val db = this.readableDatabase
        val cursor = db.query(
            TABLE_MEMORY, null,
            "$COLUMN_LEVEL = ? AND $COLUMN_PARENT_ID IS NULL",
            arrayOf(level.toString()),
            null, null, "$COLUMN_TIMESTAMP ASC", limit.toString()
        )

        while (cursor.moveToNext()) {
            nodes.add(MemoryNode(
                cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
                cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ROLE)),
                cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CONTENT)),
                cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_LEVEL)),
                if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_PARENT_ID))) null else cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_PARENT_ID)),
                cursor.getBlob(cursor.getColumnIndexOrThrow(COLUMN_EMBEDDING)),
                cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE)),
                cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE)),
                cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_METADATA))
            ))
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
            db.close()
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
        // Simplified Haversine or just a box for implementation speed
        // Let's use a bounding box first
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

    private fun cursorToNode(cursor: android.database.Cursor): MemoryNode {
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
            cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_METADATA))
        )
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
}
