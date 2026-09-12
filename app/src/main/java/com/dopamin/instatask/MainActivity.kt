package com.dopamin.instatask

import android.R.attr.action
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var btnStartBot: Button
    private lateinit var btnStopBot: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnClearStats : Button
    private lateinit var tvStatus: TextView
    private lateinit var tvCurrentActivity: TextView
    private lateinit var tvStats: TextView

    private lateinit var database: AppDatabase

    private val statsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.dopamin.instatask.STATS_UPDATE") {
                val currentSource = intent.getStringExtra("CURRENT_SOURCE") ?: "None"
                val currentTarget = intent.getStringExtra("CURRENT_TARGET") ?: "None"

                val scanned = intent.getIntExtra("PROFILES_SCANNED", 0)
                val matches = intent.getIntExtra("PROFILES_FOLLOWED", 0)
                val likes = intent.getIntExtra("LIKES_GIVEN", 0)
                val comments = intent.getIntExtra("COMMENTS_SENT", 0)
                val stories = intent.getIntExtra("STORIES_REACTED", 0)
                val skipped = intent.getIntExtra("PROFILES_SKIPPED", 0)
                val errors = intent.getIntExtra("ERRORS", 0)

                // Updating UI Live Activity & Single TextView Stats
                tvCurrentActivity.text = "Current Source: $currentSource\nCurrent Target: $currentTarget"

                updateStatsTextView(scanned, matches, likes, comments, stories, skipped, errors)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        database = AppDatabase.getDatabase(this)

        btnStartBot = findViewById(R.id.btnStartBot)
        btnStopBot = findViewById(R.id.btnStopBot)
        btnClearStats = findViewById(R.id.btnClearStats)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        tvStatus = findViewById(R.id.tvStatus)
        tvCurrentActivity = findViewById(R.id.tvCurrentActivity)
        tvStats = findViewById(R.id.tvStats)


        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnClearStats.setOnClickListener {
            val intent = Intent(this, BotService::class.java).apply {
                action = "CLEAR_STATS"
            }
            startService(intent)
        }

        btnStartBot.setOnClickListener {
            if (isAccessibilityServiceEnabled(this, BotService::class.java)) {
                startBotService("START_BOT")
                tvStatus.text = "Status: Bot running..."
                Toast.makeText(this, "Bot started", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please enable Accessibility Service first!", Toast.LENGTH_LONG).show()
            }
        }

        btnStopBot.setOnClickListener {
            startBotService("STOP_BOT")
            tvStatus.text = "Status: Stopped"
            Toast.makeText(this, "Bot stopped", Toast.LENGTH_SHORT).show()
        }

        // App launch par Room DB se historical count read karke screen par dikhana
        loadStatsFromDatabase()
    }

    private fun startBotService(actionString: String) {
        val intent = Intent(this, BotService::class.java).apply {
            action = actionString
        }
        startService(intent)
    }

    private fun loadStatsFromDatabase() {
        lifecycleScope.launch(Dispatchers.IO) {
            val totalProcessed = database.botDao().getProcessedCount()
            val totalLikes = database.botDao().getTotalLikesCount()
            val totalComments = database.botDao().getTotalCommentsCount()
            val totalStories = database.botDao().getTotalStoriesCount()
            val totalScanned = database.botDao().getTotalProfilesScanned()
            val totalFollowed = database.botDao().getTotalProfilesFollowed()
            val totalSkipped = database.botDao().getTotalProfilesSkipped()
            val totalErrors = database.botDao().getTotalErrors()
            withContext(Dispatchers.Main) {
                // Initial load from Room Database
                updateStatsTextView(
                    scanned = totalScanned,
                    followed = totalFollowed,
                    likes = totalLikes,
                    comments = totalComments,
                    stories = totalStories,
                    skipped = totalSkipped,
                    errors = totalErrors
                )
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateStatsTextView(
        scanned: Int, followed: Int, likes: Int,
        comments: Int, stories: Int, skipped: Int, errors: Int
    ) {
        tvStats.text = """
            Statistics:
            - Profiles Scanned: $scanned
            - Profiles Followed: $followed
            - Likes Distributed: $likes
            - Comments Sent: $comments
            - Stories Reacted: $stories
            - Profiles Skipped: $skipped
            - Errors Encountered: $errors
        """.trimIndent()
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            statsReceiver,
            IntentFilter("com.dopamin.instatask.STATS_UPDATE"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        if (isAccessibilityServiceEnabled(this, BotService::class.java)) {
            tvStatus.text = "Status: Ready (Service active)"
        } else {
            tvStatus.text = "Status: Service disabled in Settings"
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statsReceiver)
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
        val expectedComponentName = android.content.ComponentName(context, service).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)

        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedComponentName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}