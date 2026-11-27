package de.cracky.notizen.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import de.cracky.notizen.model.LabelData
import de.cracky.notizen.model.NoteData
import de.cracky.notizen.model.UserData
import de.cracky.notizen.model.UserInfo
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Entity(tableName = "cached_notes", primaryKeys = ["userId", "noteId"])
data class CachedNoteEntity(val userId: Int, val noteId: Int, val payload: String, val cachedAt: Long)

@Entity(tableName = "cached_meta", primaryKeys = ["userId", "key"])
data class CachedMetaEntity(val userId: Int, val key: String, val payload: String)

@Dao
interface CacheDao {
    @Query("SELECT * FROM cached_notes WHERE userId = :userId")
    suspend fun notes(userId: Int): List<CachedNoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putNotes(notes: List<CachedNoteEntity>)

    @Query("DELETE FROM cached_notes WHERE userId = :userId AND noteId = :noteId")
    suspend fun deleteNote(userId: Int, noteId: Int)

    @Query("DELETE FROM cached_notes WHERE userId = :userId")
    suspend fun clearNotes(userId: Int)

    @Query("SELECT * FROM cached_meta WHERE userId = :userId AND `key` = :key LIMIT 1")
    suspend fun meta(userId: Int, key: String): CachedMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMeta(meta: CachedMetaEntity)

    @Query("DELETE FROM cached_meta WHERE userId = :userId")
    suspend fun clearMeta(userId: Int)

    @Query("DELETE FROM cached_notes")
    suspend fun clearAllNotes()

    @Query("DELETE FROM cached_meta")
    suspend fun clearAllMeta()
}

@Database(entities = [CachedNoteEntity::class, CachedMetaEntity::class], version = 2, exportSchema = false)
abstract class CacheDatabase : RoomDatabase() {
    abstract fun dao(): CacheDao

    companion object {
        fun create(context: Context) = Room.databaseBuilder(context, CacheDatabase::class.java, "notizen-local-cache.db")
            .fallbackToDestructiveMigration()
            .build()
    }
}

/**
 * Zweistufige Verschlüsselung für den lokalen Cache:
 * Ein zufälliger 256-Bit-Datenschlüssel (DEK) verschlüsselt die Zeilen in Software (AES-GCM, Mikrosekunden pro Zeile).
 * Der DEK selbst liegt nur "eingewickelt" vom Android-Keystore-Schlüssel (KEK) auf der Platte.
 * Dadurch ist beim Start genau EINE Keystore-Operation nötig statt einer pro Notiz – der Cache ist sofort da.
 */
class CacheCipher(context: Context) {
    private val prefs = context.getSharedPreferences("notizen_cache_key", Context.MODE_PRIVATE)
    private val random = SecureRandom()
    @Volatile private var dek: SecretKey? = null

    fun encrypt(plain: String): String {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = ByteBuffer.allocate(1 + iv.size + encrypted.size).put(VERSION).put(iv).put(encrypted).array()
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > 13 && bytes[0] == VERSION) { "Unbekanntes Cache-Format" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 1, 12))
        return cipher.doFinal(bytes, 13, bytes.size - 13).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey = dek ?: synchronized(this) { dek ?: loadOrCreateDek().also { dek = it } }

    private fun loadOrCreateDek(): SecretKey {
        val kek = getOrCreateKek()
        prefs.getString("dek", null)?.let { wrapped ->
            runCatching {
                val bytes = Base64.decode(wrapped, Base64.NO_WRAP)
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(128, bytes, 0, 12))
                val raw = c.doFinal(bytes, 12, bytes.size - 12)
                return SecretKeySpec(raw, "AES").also { raw.fill(0) }
            }
        }
        val raw = ByteArray(32).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, kek)
        val wrapped = c.iv + c.doFinal(raw)
        prefs.edit().putString("dek", Base64.encodeToString(wrapped, Base64.NO_WRAP)).commit()
        return SecretKeySpec(raw, "AES").also { raw.fill(0) }
    }

    private fun getOrCreateKek(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEK_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEK_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEK_ALIAS = "notizen.cache.kek.v2"
        private const val VERSION: Byte = 2
    }
}

class LocalNoteCache(context: Context) {
    private val db = CacheDatabase.create(context.applicationContext)
    private val cipher = CacheCipher(context.applicationContext)
    private val dao get() = db.dao()

    suspend fun replaceSnapshot(userId: Int, notes: List<NoteData>) {
        val previous = loadNotes(userId).associateBy { it.id }
        val merged = notes.map { server ->
            val cached = previous[server.id]
            // Inhalte zurückgestellter Archivnotizen behalten, solange sie sich nicht geändert haben
            if (server.contentDeferred && cached != null && !cached.contentDeferred && cached.updatedAt == server.updatedAt) {
                server.copy(doc = cached.doc, images = cached.images, pdfs = cached.pdfs, contentDeferred = false)
            } else server
        }
        val now = System.currentTimeMillis()
        val rows = merged.map { CachedNoteEntity(userId, it.id, cipher.encrypt(it.toJson().toString()), now) }
        dao.clearNotes(userId)
        dao.putNotes(rows)
    }

    suspend fun saveNotes(userId: Int, notes: List<NoteData>) {
        if (notes.isEmpty()) return
        val now = System.currentTimeMillis()
        dao.putNotes(notes.map { CachedNoteEntity(userId, it.id, cipher.encrypt(it.toJson().toString()), now) })
    }

    suspend fun deleteNote(userId: Int, noteId: Int) = dao.deleteNote(userId, noteId)

    suspend fun loadNotes(userId: Int): List<NoteData> = dao.notes(userId).mapNotNull { row ->
        runCatching { NoteData.fromJson(JsonParser.parseString(cipher.decrypt(row.payload)).asJsonObject) }.getOrNull()
    }

    suspend fun saveUser(user: UserData) = putMeta(0, "user", user.toJson().toString())
    suspend fun loadUser(): UserData? = getMeta(0, "user")?.let {
        runCatching { UserData.fromJson(JsonParser.parseString(it).asJsonObject) }.getOrNull()
    }

    suspend fun saveLabels(userId: Int, labels: JsonArray) = putMeta(userId, "labels", labels.toString())
    suspend fun loadLabels(userId: Int): List<LabelData> = getMeta(userId, "labels")?.let { raw ->
        runCatching { JsonParser.parseString(raw).asJsonArray.mapNotNull { e -> if (e.isJsonObject) LabelData.fromJson(e.asJsonObject) else null } }.getOrNull()
    }.orEmpty()

    suspend fun saveUsers(userId: Int, users: JsonArray) = putMeta(userId, "users", users.toString())
    suspend fun loadUsers(userId: Int): List<UserInfo> = getMeta(userId, "users")?.let { raw ->
        runCatching { JsonParser.parseString(raw).asJsonArray.mapNotNull { e -> if (e.isJsonObject) UserInfo.fromJson(e.asJsonObject) else null } }.getOrNull()
    }.orEmpty()

    private suspend fun putMeta(userId: Int, key: String, plain: String) =
        dao.putMeta(CachedMetaEntity(userId, key, cipher.encrypt(plain)))

    private suspend fun getMeta(userId: Int, key: String): String? =
        dao.meta(userId, key)?.let { runCatching { cipher.decrypt(it.payload) }.getOrNull() }

    suspend fun clearUser(userId: Int) {
        dao.clearNotes(userId)
        dao.clearMeta(userId)
    }

    suspend fun clearAll() {
        dao.clearAllNotes()
        dao.clearAllMeta()
    }
}
