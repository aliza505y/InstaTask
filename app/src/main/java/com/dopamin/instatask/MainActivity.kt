package com.dopamin.instatask

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var btnStartBot: Button
    private lateinit var btnStopBot: Button
    private lateinit var btnAccessibility: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvStats: TextView

    private val statsReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.dopamin.instatask.STATS_UPDATE") {
                val profiles = intent.getIntExtra("PROFILES", 0)
                val matches = intent.getIntExtra("MATCHES", 0)
                val likes = intent.getIntExtra("LIKES", 0)
                val stories = intent.getIntExtra("STORIES", 0)

                tvStats.text = "Statistics:\n- Profiles Scanned: $profiles\n- Matches Found: $matches\n- Likes Distributed: $likes\n- Stories Reacted: $stories"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        // Initialize UI Elements
        btnStartBot = findViewById(R.id.btnStartBot)
        btnStopBot = findViewById(R.id.btnStopBot)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        tvStatus = findViewById(R.id.tvStatus)
        tvStats = findViewById(R.id.tvStats)

        // Listener for "Enable Accessibility Service" button
        btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        // Listener for "Start Bot" button
        btnStartBot.setOnClickListener {
            if (isAccessibilityServiceEnabled(this, BotService::class.java)) {
                val intent = Intent(this, BotService::class.java).apply {
                    action = "START_BOT"
                }
                startService(intent)
                tvStatus.text = "Status: Bot running..."
                Toast.makeText(this, "Bot started", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Please enable the Accessibility Service first!",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // Listener for "Stop Bot" button
        btnStopBot.setOnClickListener {
            val intent = Intent(this, BotService::class.java).apply {
                action = "STOP_BOT"
            }
            startService(intent)
            tvStatus.text = "Status: Stopped"
            Toast.makeText(this, "Bot stopped", Toast.LENGTH_SHORT).show()
        }

        // AUTO-START: If service is already active, start bot immediately
        if (isAccessibilityServiceEnabled(this, BotService::class.java)) {
            tvStatus.postDelayed({
                startBotWorkflow()
            }, 1000)
        }
    }

    private fun startBotWorkflow() {
        val intent = Intent(this, BotService::class.java).apply {
            action = "START_BOT"
        }
        startService(intent)
        tvStatus.text = "Status: Bot running (Auto-Start)..."
        Toast.makeText(this, "Bot automatically started", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            statsReceiver,
            android.content.IntentFilter("com.dopamin.instatask.STATS_UPDATE"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Check if the accessibility service is enabled and update status text
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

    // Helper function to check if Accessibility Service is enabled
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