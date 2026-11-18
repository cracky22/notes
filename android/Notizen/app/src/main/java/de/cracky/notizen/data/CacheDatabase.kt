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
import com.google.gson.JsonParser
import de.cracky.notizen.model.NoteData
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Entity(tableName = "cached_notes", primaryKeys = ["userId", "noteId"])
data class CachedNoteEntity(
    val userId: Int,
    val noteId: Int,
    val payload: String,
    val cachedAt: Long
)

@Dao
interface CachedNoteDao {
    @Query("SELECT * FROM cached_notes WHERE userId = :userId ORDER BY cachedAt DESC")
    suspend fun getAll(userId: Int): List<CachedNoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(notes: List<CachedNoteEntity>)

    @Query("DELETE FROM cached_notes WHERE userId = :userId")
    suspend fun clearUser(userId: Int)

    @Query("DELETE FROM cached_notes")
    suspend fun clearAll()
}

@Database(entities = [CachedNoteEntity::class], version = 1, exportSchema = false)
abstract class CacheDatabase : RoomDatabase() {
    abstract fun notes(): CachedNoteDao

    companion object {
        fun create(context: Context) = Room.databaseBuilder(
            context, CacheDatabase::class.java, "notizen-local-cache.db"
        ).build()
    }
}

/** Per-row AES-GCM encryption; the key never leaves the Android Keystore. */
class CacheCipher {
    private val alias = "notizen.cache.aes.v1"

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = ByteBuffer.allocate(4 + iv.size + encrypted.size)
            .putInt(iv.size).put(iv).put(encrypted).array()
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val buffer = ByteBuffer.wrap(bytes)
        val ivLength = buffer.int
        require(ivLength in 12..16 && ivLength < buffer.remaining()) { "Invalid encrypted cache row" }
        val iv = ByteArray(ivLength).also { buffer.get(it) }
        val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}

class LocalNoteCache(context: Context) {
    private val db = CacheDatabase.create(context.applicationContext)
    private val cipher = CacheCipher()
    private val dao get() = db.notes()

    suspend fun replaceSnapshot(userId: Int, notes: List<NoteData>) {
        val previous = load(userId).associateBy { it.id }
        val merged = notes.map { serverNote ->
            val cached = previous[serverNote.id]
            if (serverNote.contentDeferred && cached != null && !cached.contentDeferred && cached.updatedAt == serverNote.updatedAt) {
                serverNote.copy(title = cached.title, body = cached.body, items = cached.items,
                    isList = cached.isList, contentDeferred = false)
            } else serverNote
        }
        dao.clearUser(userId)
        dao.putAll(merged.map { note ->
            CachedNoteEntity(userId, note.id, cipher.encrypt(note.toJson().toString()), System.currentTimeMillis())
        })
    }

    suspend fun saveOne(userId: Int, note: NoteData) {
        dao.putAll(listOf(CachedNoteEntity(userId, note.id, cipher.encrypt(note.toJson().toString()), System.currentTimeMillis())))
    }

    suspend fun load(userId: Int): List<NoteData> = dao.getAll(userId).mapNotNull { row ->
        runCatching { NoteData.fromJson(JsonParser.parseString(cipher.decrypt(row.payload)).asJsonObject) }.getOrNull()
    }

    suspend fun clearUser(userId: Int) = dao.clearUser(userId)
    suspend fun clearAll() = dao.clearAll()
}
