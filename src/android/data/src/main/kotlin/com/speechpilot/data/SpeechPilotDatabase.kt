package com.speechpilot.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for SpeechPilot.
 *
 * Stores session summaries locally on-device. No data leaves the device.
 *
 * Access via [getInstance] to obtain the process-wide singleton.
 *
 * ## Migrations
 * - v1 → v2: adds nullable `audioFileUri` column to `session_records`.
 */
@Database(entities = [SessionRecord::class], version = 2, exportSchema = false)
abstract class SpeechPilotDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile private var INSTANCE: SpeechPilotDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE session_records ADD COLUMN audioFileUri TEXT DEFAULT NULL"
                )
            }
        }

        fun getInstance(context: Context): SpeechPilotDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    SpeechPilotDatabase::class.java,
                    "speech_pilot.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
