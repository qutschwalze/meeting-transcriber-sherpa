package com.sherpa.transcript.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 0.6.6: Room-Datenbank für Transkripte (SQLite, Datei "sherpa.db").
 * Version 1 – die einmalige JSON→SQLite-Migration läuft im Repository
 * (migrateJsonIfNeeded), nicht als Room-Schema-Migration.
 * Version 2 (0.7.4): segments.speakerName (Profil-Namen für den Export).
 */
@Database(
    entities = [TranscriptEntity::class, SegmentEntity::class],
    version = 2,
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
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }

        /** 0.7.4: segments um die Spalte speakerName erweitern (ALTER, zerstörungsfrei). */
        private val MIGRATION_1_2 = androidx.room.migration.Migration(1, 2) { db ->
            db.execSQL("ALTER TABLE segments ADD COLUMN speakerName TEXT")
        }
    }
}
