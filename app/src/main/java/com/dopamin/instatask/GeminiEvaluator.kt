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
            modelName = "gemini-1.5-flash",
            apiKey = apiKey
        )
    }

    /**
     * Analyzes the extracted text from an Instagram profile bio.
     * Decides whether the account belongs to a producer, DJ, artist, or musician.
     */
    fun evaluateProfileText(profileText: String, callback: (Boolean) -> Unit) {
        val prompt = """
            You are a highly precise filter for a Music Production & Mixing Agency.
            Your task is to decide whether the following Instagram profile belongs to a music producer, DJ, artist, singer-songwriter, or home-studio musician.
            
            Criteria for LIKE (True):
            - Terms in bio/text such as: Producer, DJ, Beats, Music, DAW, FL Studio, Ableton, Logic, Mixing, Mastering, Track, Release, Songwriter, Studio, Sound, House, EDM, Techno, Artist.
            - References to music releases, SoundCloud links, Spotify links, or demos.
            
            Criteria for NEXT (False):
            - Purely personal profiles without any music connection (e.g., only "Travel", "Fitness", "Private Account").
            - Businesses, shops, meme pages, major brands, or inactive accounts.
            
            Profile Text:
            "$profileText"
            
            Respond EXCLUSIVELY with a single word:
            'LIKE' if it is a musician/producer/DJ.
            'NEXT' if it is not a musician/producer/DJ.
        """.trimIndent()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = generativeModel.generateContent(prompt)
                val decision = response.text?.trim()?.uppercase() ?: "NEXT"
                Log.d(TAG, "Gemini response for profile: $decision")

                val shouldEngage = decision.contains("LIKE")
                callback(shouldEngage)
            } catch (e: Exception) {
                Log.e(TAG, "Error in Gemini API request: ${e.message}", e)
                callback(false)
            }
        }
    }
}