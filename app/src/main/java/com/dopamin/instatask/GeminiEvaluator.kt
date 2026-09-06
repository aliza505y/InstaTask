package com.dopamin.instatask

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GeminiEvaluator(private val apiKey: String) {

    private val TAG = "InstaTaskBot"

    private val generativeModel by lazy {
        GenerativeModel(
            modelName = "gemini-3.6-flash",
            apiKey = apiKey
        )
    }

    /**
     * Analysiert den ausgelesenen Text eines Instagram-Profils.
     * Entscheidet, ob es sich um einen Producer, DJ, Artist oder Musiker handelt.
     */
    fun evaluateProfileText(profileText: String, callback: (Boolean) -> Unit) {
        val prompt = """
            Du bist ein hochpräziser Filter für eine Music Production & Mixing Agency.
            Deine Aufgabe ist es zu entscheiden, ob das folgende Instagram-Profil zu einem Musik-Produzenten, DJ, Artist, Singer-Songwriter oder Home-Studio-Musiker gehört.
            
            Kriterien für LIKE (True):
            - Begriffe in Bio/Text wie: Producer, DJ, Beats, Music, DAW, FL Studio, Ableton, Logic, Mixing, Mastering, Track, Release, Songwriter, Studio, Sound, House, EDM, Technomusic, Artist.
            - Hinweise auf Veröffentlichungen, Soundcloud-Links, Spotify-Links, Demos.
            
            Kriterien für NEXT (False):
            - Reines Privatprofil ohne Musik-Bezug (z.B. nur "Travel", "Fitness", "Private Account").
            - Unternehmen, Shops, Memes, große Marken oder Inaktive Accounts.
            
            Profil-Text:
            "$profileText"
            
            Antworte AUSSCHLIESSLICH mit einem einzigen Wort:
            'LIKE' wenn es ein Musiker/Producer/DJ ist.
            'NEXT' wenn es kein Musiker/Producer/DJ ist.
        """.trimIndent()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = generativeModel.generateContent(prompt)
                val decision = response.text?.trim()?.uppercase() ?: "NEXT"
                Log.d(TAG, "Gemini Antwort für Profil: $decision")

                val shouldEngage = decision.contains("LIKE")
                callback(shouldEngage)
            } catch (e: Exception) {
                Log.e(TAG, "Fehler bei Gemini API Anfrage: ${e.message}", e)
                callback(false)
            }
        }
    }
}