package io.github.jetmil.aimoodpet.vision

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * SQLite база известных лиц. fingerprint — 64-D L2-нормализованный
 * float32 BLOB (256 байт). Поиск — линейный (для 5-50 лиц norm).
 *
 * При первой встрече (similarity со всеми < threshold) — добавляется
 * как anonymous "лицо_N", потом имя обновляется когда юзер скажет.
 */
class FaceMemory(context: Context) {
    private val helper = object : SQLiteOpenHelper(context, DB_NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE faces (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    embedding BLOB NOT NULL,
                    encounters INTEGER NOT NULL DEFAULT 1,
                    first_seen_ms INTEGER NOT NULL,
                    last_seen_ms INTEGER NOT NULL
                )"""
            )
            db.execSQL("CREATE INDEX ix_faces_last ON faces(last_seen_ms DESC)")
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}
    }

    data class FaceRow(val id: Long, val name: String, val embedding: FloatArray, val encounters: Int, val lastSeenMs: Long)

    fun all(): List<FaceRow> {
        val out = ArrayList<FaceRow>()
        helper.readableDatabase.rawQuery(
            "SELECT id, name, embedding, encounters, last_seen_ms FROM faces ORDER BY last_seen_ms DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out.add(FaceRow(
                    id = c.getLong(0),
                    name = c.getString(1),
                    embedding = FaceFingerprint.decode(c.getBlob(2)),
                    encounters = c.getInt(3),
                    lastSeenMs = c.getLong(4),
                ))
            }
        }
        return out
    }

    /**
     * Ищет лицо по сходству. Возвращает (FaceRow, similarity) если best > threshold.
     */
    fun findBest(emb: FloatArray, threshold: Float = 0.86f): Pair<FaceRow, Float>? {
        val rows = all()
        if (rows.isEmpty()) return null
        var bestSim = -1f
        var best: FaceRow? = null
        for (r in rows) {
            val s = FaceFingerprint.similarity(emb, r.embedding)
            if (s > bestSim) { bestSim = s; best = r }
        }
        val b = best ?: return null
        return if (bestSim >= threshold) b to bestSim else null
    }

    fun touch(id: Long): Int {
        val now = System.currentTimeMillis()
        val sql = "UPDATE faces SET encounters = encounters + 1, last_seen_ms = ? WHERE id = ?"
        val db = helper.writableDatabase
        db.execSQL(sql, arrayOf(now, id))
        // вернуть новое значение encounters
        db.rawQuery("SELECT encounters FROM faces WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) return c.getInt(0)
        }
        return -1
    }

    fun add(name: String, emb: FloatArray): Long {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("name", name)
            put("embedding", FaceFingerprint.encode(emb))
            put("encounters", 1)
            put("first_seen_ms", now)
            put("last_seen_ms", now)
        }
        val id = helper.writableDatabase.insert("faces", null, cv)
        Log.i(TAG, "added face '$name' id=$id (now ${all().size} faces)")
        return id
    }

    fun rename(id: Long, name: String) {
        val cv = ContentValues().apply { put("name", name) }
        helper.writableDatabase.update("faces", cv, "id=?", arrayOf(id.toString()))
    }

    fun close() { helper.close() }

    companion object {
        private const val DB_NAME = "tama_faces.db"
        private const val TAG = "FaceMemory"
    }
}
