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

    // 🟢 24-HOUR LIKES COUNT: Safe rate limiting check ke liye last 24h ke likes retrieve karta ha
    @Query("SELECT COUNT(*) FROM automation_logs WHERE actionType IN ('POST_LIKED', 'REEL_LIKED') AND timestamp >= :sinceTimestamp AND isSuccess = 1")
    suspend fun getLikesCountSince(sinceTimestamp: Long): Int


    //total statistics data
    @Query("SELECT COUNT(*) FROM automation_logs WHERE actionType = 'POST_LIKED' OR actionType = 'REEL_LIKED' ")
    suspend fun getTotalLikesCount(): Int

    @Query("SELECT COUNT(*) FROM automation_logs WHERE actionType = 'COMMENT_SENT' ")
    suspend fun getTotalCommentsCount(): Int

    @Query("SELECT COUNT(*) FROM automation_logs WHERE actionType = 'STORY_REACTION'")
    suspend fun getTotalStoriesCount(): Int

    @Query("SELECT COUNT(DISTINCT username) FROM processed_profiles")
    suspend fun getTotalProfilesScanned(): Int

    @Query("SELECT COUNT(*) FROM processed_profiles WHERE actionTaken = 'INTERACTED'")
    suspend fun getTotalMatchesFound(): Int

    @Query("SELECT COUNT(*) FROM processed_profiles WHERE actionTaken = 'PROFILES_FOLLOWED' OR actionTaken = 'INTERACTED'")
    suspend fun getTotalProfilesFollowed(): Int

    @Query("SELECT COUNT(*) FROM processed_profiles WHERE actionTaken LIKE 'SKIPPED%'")
    suspend fun getTotalProfilesSkipped(): Int

    @Query("SELECT COUNT(*) FROM automation_logs WHERE isSuccess = 0")
    suspend fun getTotalErrors(): Int


    //clear / reset all stats
    @Query("DELETE FROM automation_logs")
    suspend fun clearAllLogs()

    @Query("DELETE FROM processed_profiles")
    suspend fun clearAllProcessedProfiles()





}