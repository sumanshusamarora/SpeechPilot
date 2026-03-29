package com.speechpilot.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for SpeechPilot.
 *
 * Stores session summaries locally on-device. No data leaves the device.
 *
 * Access via [getInstance] to obtain the process-wide singleton.
 */
@Database(entities = [SessionRecord::class], version = 1, exportSchema = false)
abstract class SpeechPilotDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile private var INSTANCE: SpeechPilotDatabase? = null

        fun getInstance(context: Context): SpeechPilotDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    SpeechPilotDatabase::class.java,
                    "speech_pilot.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
