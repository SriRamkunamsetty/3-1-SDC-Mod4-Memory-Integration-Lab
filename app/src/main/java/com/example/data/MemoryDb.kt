package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "memories")
data class Memory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val content: String,
    val category: String, // e.g. "Personal", "Preference", "Fact", "Work", "General"
    val timestamp: Long = System.currentTimeMillis(),
    val isManual: Boolean = false,
    val confidence: Double = 1.0
)

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val role: String, // "user" or "assistant"
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val pipelineLogs: String = "" // JSON or formatted text describing the pipeline steps executed
)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY timestamp DESC")
    fun getAllMemoriesFlow(): Flow<List<Memory>>

    @Query("SELECT * FROM memories")
    suspend fun getAllMemories(): List<Memory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: Memory)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemoryById(id: Int)

    @Query("DELETE FROM memories")
    suspend fun clearAllMemories()
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun getAllMessagesFlow(): Flow<List<ChatMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessage)

    @Query("DELETE FROM chat_messages")
    suspend fun clearChatHistory()
}

@Database(entities = [Memory::class, ChatMessage::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "memory_assistant_db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class MemoryRepository(private val db: AppDatabase) {
    val allMemoriesFlow: Flow<List<Memory>> = db.memoryDao().getAllMemoriesFlow()
    val allMessagesFlow: Flow<List<ChatMessage>> = db.chatDao().getAllMessagesFlow()

    suspend fun getAllMemories(): List<Memory> = db.memoryDao().getAllMemories()

    suspend fun insertMemory(memory: Memory) {
        db.memoryDao().insertMemory(memory)
    }

    suspend fun deleteMemoryById(id: Int) {
        db.memoryDao().deleteMemoryById(id)
    }

    suspend fun clearAllMemories() {
        db.memoryDao().clearAllMemories()
    }

    suspend fun insertMessage(message: ChatMessage) {
        db.chatDao().insertMessage(message)
    }

    suspend fun clearChatHistory() {
        db.chatDao().clearChatHistory()
    }
}
