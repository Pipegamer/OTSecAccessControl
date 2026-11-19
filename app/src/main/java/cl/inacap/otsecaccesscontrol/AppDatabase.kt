package cl.inacap.otsecaccesscontrol

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class UserEntity(
    val id: Int = 0,
    val username: String,
    val passwordHash: String,
    val salt: String,
    val role: String, // "ADMIN", "OPERADOR", "AUDITOR"
    val failedAttempts: Int = 0,
    val isLocked: Boolean = false
)

data class AccessLogEntity(
    val id: Int = 0,
    val timestamp: String,
    val tag: String,      // "[SYS]", "[ESTADO]", "[RFID]", "[ALERTA]", "[SEC]"
    val message: String,
    val synced: Boolean = false // Booleano de caché offline (false = pendiente por Modo Avión / sin red)
)

class AppDatabaseHelper(context: Context) : SQLiteOpenHelper(context, "otsec_access.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT UNIQUE NOT NULL,
                passwordHash TEXT NOT NULL,
                salt TEXT NOT NULL,
                role TEXT NOT NULL,
                failedAttempts INTEGER DEFAULT 0,
                isLocked INTEGER DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE access_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp TEXT NOT NULL,
                tag TEXT NOT NULL,
                message TEXT NOT NULL,
                synced INTEGER DEFAULT 0
            )
        """.trimIndent())

        // Usuarios iniciales con contraseñas seguras hasheadas en reposo (SHA-256 + Salt)
        insertInitialUser(db, "admin", "Admin2026", "ADMIN")
        insertInitialUser(db, "operador.campo", "Operador2026", "OPERADOR")
        insertInitialUser(db, "auditor.ot", "Auditor2026", "AUDITOR")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS users")
        db.execSQL("DROP TABLE IF EXISTS access_logs")
        onCreate(db)
    }

    private fun insertInitialUser(db: SQLiteDatabase, user: String, pass: String, role: String) {
        val salt = SecurityUtils.generateSalt()
        val hash = SecurityUtils.hashPassword(pass, salt)
        val values = ContentValues().apply {
            put("username", user)
            put("passwordHash", hash)
            put("salt", salt)
            put("role", role)
            put("failedAttempts", 0)
            put("isLocked", 0)
        }
        db.insert("users", null, values)
    }

    fun getUserByUsername(username: String): UserEntity? {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM users WHERE username = ?", arrayOf(username))
        var user: UserEntity? = null
        if (cursor.moveToFirst()) {
            user = UserEntity(
                id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                username = cursor.getString(cursor.getColumnIndexOrThrow("username")),
                passwordHash = cursor.getString(cursor.getColumnIndexOrThrow("passwordHash")),
                salt = cursor.getString(cursor.getColumnIndexOrThrow("salt")),
                role = cursor.getString(cursor.getColumnIndexOrThrow("role")),
                failedAttempts = cursor.getInt(cursor.getColumnIndexOrThrow("failedAttempts")),
                isLocked = cursor.getInt(cursor.getColumnIndexOrThrow("isLocked")) == 1
            )
        }
        cursor.close()
        return user
    }

    fun updateUserAttempts(username: String, attempts: Int, isLocked: Boolean) {
        val values = ContentValues().apply {
            put("failedAttempts", attempts)
            put("isLocked", if (isLocked) 1 else 0)
        }
        writableDatabase.update("users", values, "username = ?", arrayOf(username))
    }

    fun createUser(username: String, plainPassword: String, role: String): Boolean {
        if (getUserByUsername(username) != null) return false
        val salt = SecurityUtils.generateSalt()
        val hash = SecurityUtils.hashPassword(plainPassword, salt)
        val values = ContentValues().apply {
            put("username", username)
            put("passwordHash", hash)
            put("salt", salt)
            put("role", role)
            put("failedAttempts", 0)
            put("isLocked", 0)
        }
        return writableDatabase.insert("users", null, values) != -1L
    }

    fun insertLog(timestamp: String, tag: String, message: String, synced: Boolean = true): Long {
        val values = ContentValues().apply {
            put("timestamp", timestamp)
            put("tag", tag)
            put("message", message)
            put("synced", if (synced) 1 else 0)
        }
        return writableDatabase.insert("access_logs", null, values)
    }

    // Obtiene todos los eventos que ocurrieron sin conexión (synced = 0 / false)
    fun getUnsyncedLogs(): List<AccessLogEntity> {
        val list = mutableListOf<AccessLogEntity>()
        val cursor = readableDatabase.rawQuery("SELECT * FROM access_logs WHERE synced = 0 ORDER BY id ASC", null)
        while (cursor.moveToNext()) {
            list.add(
                AccessLogEntity(
                    id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                    timestamp = cursor.getString(cursor.getColumnIndexOrThrow("timestamp")),
                    tag = cursor.getString(cursor.getColumnIndexOrThrow("tag")),
                    message = cursor.getString(cursor.getColumnIndexOrThrow("message")),
                    synced = false
                )
            )
        }
        cursor.close()
        return list
    }

    // Actualiza el booleano a true (1) cuando se entrega la notificación tras reconectar
    fun markAllLogsAsSynced() {
        val values = ContentValues().apply {
            put("synced", 1)
        }
        writableDatabase.update("access_logs", values, "synced = 0", null)
    }

    fun getLogs(filter: String = ""): List<AccessLogEntity> {
        val list = mutableListOf<AccessLogEntity>()
        val cursor = if (filter.isBlank()) {
            readableDatabase.rawQuery("SELECT * FROM access_logs ORDER BY id ASC", null)
        } else {
            readableDatabase.rawQuery(
                "SELECT * FROM access_logs WHERE message LIKE ? OR tag LIKE ? ORDER BY id ASC",
                arrayOf("%$filter%", "%$filter%")
            )
        }
        while (cursor.moveToNext()) {
            list.add(
                AccessLogEntity(
                    id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                    timestamp = cursor.getString(cursor.getColumnIndexOrThrow("timestamp")),
                    tag = cursor.getString(cursor.getColumnIndexOrThrow("tag")),
                    message = cursor.getString(cursor.getColumnIndexOrThrow("message")),
                    synced = cursor.getInt(cursor.getColumnIndexOrThrow("synced")) == 1
                )
            )
        }
        cursor.close()
        return list
    }
}