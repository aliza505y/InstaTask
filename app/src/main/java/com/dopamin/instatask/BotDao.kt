package com.dopamin.instatask

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BotDao {

    // Duplicate Protection Check: Profile pehle se DB mein hai ya nahi
    @Query("SELECT EXISTS(SELECT 1 FROM processed_profiles WHERE username = :username LIMIT 1)")
    suspend fun isProfileProcessed(username: String): Boolean

    // Processed profile insert karna
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProcessedProfile(profile: ProcessedProfile)

    // Log insert karna
    @Insert
    suspend fun insertLog(log: AutomationLog)

    // Total processed count fetch karna (MainActivity ke liye)
    @Query("SELECT COUNT(*) FROM processed_profiles")
    suspend fun getProcessedCount(): Int
}