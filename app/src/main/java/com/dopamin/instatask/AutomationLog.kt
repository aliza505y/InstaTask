package com.dopamin.instatask

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "automation_logs")
data class AutomationLog(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val sourceProfile: String,
    val targetUsername: String,
    val actionType: String, // e.g., "POST_LIKED", "REEL_COMMENTED", "SKIPPED"
    val details: String,
    val isSuccess: Boolean,
    val timestamp:Long,
)