package com.sherpa.transcript.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 0.6.6: Room-Datenbank für Transkripte (SQLite, Datei "sherpa.db").
 * Version 1 – die einmalige JSON→SQLite-Migration läuft im Repository
 * (migrateJsonIfNeeded), nicht als Room-Schema-Migration.
 */
@Database(
    entities = [TranscriptEntity::class, SegmentEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transcriptDao(): TranscriptDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sherpa.db",
                ).build().also { instance = it }
            }
    }
}
