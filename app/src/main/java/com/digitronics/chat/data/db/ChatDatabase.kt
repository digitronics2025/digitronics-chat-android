package com.digitronics.chat.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, ThreadCursorEntity::class, OutgoingMessageEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        fun create(context: Context): ChatDatabase =
            Room.databaseBuilder(context, ChatDatabase::class.java, "chat-cache.db")
                // A cache: on a schema change it is cheaper and safer to refetch than to migrate.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
