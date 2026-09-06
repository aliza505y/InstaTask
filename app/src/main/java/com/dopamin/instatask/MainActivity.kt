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
                
                tvStats.text = "Statistik:\n- Profile gescannt: $profiles\n- Matches gefunden: $matches\n- Likes verteilt: $likes\n- Stories reagiert: $stories"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        // UI-Elemente initialisieren
        btnStartBot = findViewById(R.id.btnStartBot)
        btnStopBot = findViewById(R.id.btnStopBot)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        tvStatus = findViewById(R.id.tvStatus)
        tvStats = findViewById(R.id.tvStats)

        // Listener für Button "Accessibility Service aktivieren"
        btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        // Listener für "Bot Starten"
        btnStartBot.setOnClickListener {
            if (isAccessibilityServiceEnabled(this, BotService::class.java)) {
                val intent = Intent(this, BotService::class.java).apply {
                    action = "START_BOT"
                }
                startService(intent)
                tvStatus.text = "Status: Bot läuft..."
                Toast.makeText(this, "Bot gestartet", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Bitte zuerst den Accessibility Service aktivieren!",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // Listener für "Bot Stoppen"
        btnStopBot.setOnClickListener {
            val intent = Intent(this, BotService::class.java).apply {
                action = "STOP_BOT"
            }
            startService(intent)
            tvStatus.text = "Status: Gestoppt"
            Toast.makeText(this, "Bot gestoppt", Toast.LENGTH_SHORT).show()
        }

        // AUTO-START: Wenn Service bereits aktiv, starte Bot sofort
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
        tvStatus.text = "Status: Bot läuft (Auto-Start)..."
        Toast.makeText(this, "Bot automatisch gestartet", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            statsReceiver,
            android.content.IntentFilter("com.dopamin.instatask.STATS_UPDATE"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Prüfen, ob der Service aktiviert ist und Status-Text anpassen
        if (isAccessibilityServiceEnabled(this, BotService::class.java)) {
            tvStatus.text = "Status: Bereit (Service aktiv)"
        } else {
            tvStatus.text = "Status: Service deaktiviert in Einstellungen"
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statsReceiver)
    }

    // Hilfsfunktion zum Prüfen, ob der Accessibility Service eingeschaltet ist
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