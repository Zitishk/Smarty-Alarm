package com.example.smartalarm.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface NightRecordDao {
    @Insert
    suspend fun insert(record: NightRecord)

    @Query("SELECT * FROM night_records ORDER BY timestamp DESC LIMIT 30")
    suspend fun recent(): List<NightRecord>
}