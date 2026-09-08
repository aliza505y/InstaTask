package com.dopamin.instatask

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "processed_profiles")
data class ProcessedProfile(
    @PrimaryKey val username: String,
    val sourceProfile: String,
    val actionTaken: String, // e.g., "LIKED", "COMMENTED", "SKIPPED_PRIVATE"
    val timestamp: Long
)