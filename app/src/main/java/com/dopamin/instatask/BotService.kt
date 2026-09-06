package com.dopamin.instatask

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import android.widget.Toast
import android.os.PowerManager
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * InstaTask Bot Service - Automatisierte Interaktion für Instagram.
 */
class BotService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var botJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val likedProfiles = mutableSetOf<String>()

    private val targetProfiles = listOf(
        "houseworksrec", "loudkult", "sirupmusic", "tomorrowland_music", "kontorrecords"
    )
    
    private val generativeModel = GenerativeModel(
        modelName = "gemini-1.5-pro",
        apiKey = BuildConfig.GEMINI_API_KEY
    )

    private enum class BotState { IDLE, NAVIGATING, BROWSING_FOLLOWERS, ANALYZING_PROFILE, INTERACTING }
    private var currentState = BotState.IDLE

    private var statsProfilesScanned = 0
    private var statsMatchesFound = 0
    private var statsLikesGiven = 0
    private var statsStoriesReacted = 0

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopBot()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_BOT" -> startBot()
            "STOP_BOT" -> stopBot()
        }
        return START_STICKY
    }

    private fun startBot() {
        if (botJob?.isActive == true) return
        
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "InstaTask::BotWakeLock")
        wakeLock?.acquire(10 * 60 * 1000L)

        Toast.makeText(this, "Bot gestartet", Toast.LENGTH_SHORT).show()
        botJob = serviceScope.launch {
            Log.d("InstaTaskBot", "Workflow gestartet...")
            for (profile in targetProfiles) {
                if (!coroutineContext.isActive) break
                try {
                    processTargetProfile(profile)
                    randomDelay(8000, 15000)
                } catch (e: Exception) {
                    Log.e("InstaTaskBot", "Fehler bei $profile: ${e.localizedMessage}")
                }
            }
            Log.d("InstaTaskBot", "Workflow beendet.")
        }
    }

    private fun stopBot() {
        botJob?.cancel()
        botJob = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        currentState = BotState.IDLE
    }

    private suspend fun processTargetProfile(username: String) {
        currentState = BotState.NAVIGATING
        Log.d("InstaTaskBot", "Navigation zu $username")
        
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("instagram://user?username=$username")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
        randomDelay(5000, 8000)

        var followersNode = findFollowerButton()
        if (followersNode == null) {
            humanScroll() 
            randomDelay(2000, 4000)
            followersNode = findFollowerButton()
        }

        if (followersNode != null) {
            clickNode(followersNode)
            randomDelay(3000, 5000)
            browseFollowers()
            performGlobalAction(GLOBAL_ACTION_BACK)
            randomDelay(2000, 3000)
        }
    }

    private fun findFollowerButton(): AccessibilityNodeInfo? {
        val ids = listOf(
            "com.instagram.android:id/row_profile_header_followers_container",
            "com.instagram.android:id/row_profile_header_container_followers",
            "com.instagram.android:id/row_profile_header_textview_followers_count"
        )
        for (id in ids) {
            val nodes = findNodesByViewId(id)
            if (nodes.isNotEmpty()) return nodes[0]
        }
        val keywords = listOf("follower", "Follower", "Abonnenten")
        val root = rootInActiveWindow ?: return null
        val queue = mutableListOf(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)
            val text = node.text?.toString() ?: ""
            if (keywords.any { text.contains(it, true) }) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private suspend fun browseFollowers() {
        currentState = BotState.BROWSING_FOLLOWERS
        val processed = mutableSetOf<String>()
        var interactionsCount = 0
        var scrollAttempts = 0

        while (interactionsCount < 20 && scrollAttempts < 5 && coroutineContext.isActive) {
            if (isReelVisible()) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                randomDelay(2000, 3000)
                continue
            }

            val nodes = findFollowerNodes()
            if (nodes.isEmpty()) {
                humanScroll()
                randomDelay(2000, 3000)
                scrollAttempts++
                continue
            }

            var foundNew = false
            for (node in nodes) {
                if (!coroutineContext.isActive) break
                val name = node.text?.toString() ?: continue
                if (processed.contains(name) || likedProfiles.contains(name)) continue
                
                processed.add(name)
                foundNew = true
                scrollAttempts = 0
                statsProfilesScanned++
                sendStatsUpdate()

                clickNode(node)
                Log.d("InstaTaskBot", ">>> Öffne Profil: $name")
                randomDelay(3000, 5000)

                if (isProfileViewVisible()) {
                    if (isPublicProfile() && hasPosts()) {
                        Log.d("InstaTaskBot", "Öffentliches Profil. Interagiere...")
                        statsMatchesFound++
                        sendStatsUpdate()
                        performInteractions(name)
                        interactionsCount++
                        randomDelay(3000, 5000)
                    } else {
                        Log.d("InstaTaskBot", "Überspringe (Privat oder leer)")
                    }
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    randomDelay(1500, 2500)
                } else if (isReelVisible()) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    randomDelay(1500, 2500)
                }
                if (interactionsCount >= 20) break
            }

            if (!foundNew) {
                humanScroll()
                randomDelay(2000, 3000)
                scrollAttempts++
            }
        }
    }

    private suspend fun performInteractions(username: String) {
        currentState = BotState.INTERACTING
        
        // 1. Post liken - Suche nach Grid-Elementen
        val postIds = listOf(
            "com.instagram.android:id/image_button",
            "com.instagram.android:id/media_set_row_content_container",
            "com.instagram.android:id/row_profile_header_container" 
        )
        
        var postOpened = false
        for (id in postIds) {
            val posts = findNodesByViewId(id)
            if (posts.isNotEmpty()) {
                Log.d("InstaTaskBot", "Öffne ersten Post...")
                clickNode(posts[0])
                randomDelay(3000, 5000)
                
                // Prüfen ob wirklich ein Post offen ist (z.B. Like-Button oder Kommentar-Feld sichtbar)
                if (findLikeButton() != null || findNodeByContentDescription("Kommentieren") != null) {
                    postOpened = true
                    break
                } else {
                    Log.d("InstaTaskBot", "Klick auf Post-Grid schlug fehl (kein Post geöffnet).")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    randomDelay(1000, 2000)
                }
            }
        }

        if (postOpened) {
            if (isReelVisible()) {
                Log.d("InstaTaskBot", "Reel erkannt. Kommentiere...")
                commentOnReel()
                // Zusätzlich liken wenn möglich
                val like = findLikeButton()
                if (like != null) {
                    clickNode(like)
                    Log.d("InstaTaskBot", "Reel geliked!")
                    statsLikesGiven++
                    sendStatsUpdate()
                }
            } else {
                val like = findLikeButton()
                if (like != null) {
                    clickNode(like)
                    Log.d("InstaTaskBot", ">>> ERFOLG: Post geliked! <<<")
                    statsLikesGiven++
                    sendStatsUpdate()
                    randomDelay(1500, 2500)
                } else {
                    Log.d("InstaTaskBot", "Like-Button nicht gefunden.")
                }
            }
            likedProfiles.add(username)
            performGlobalAction(GLOBAL_ACTION_BACK)
            randomDelay(2000, 3000)
        } else {
            Log.d("InstaTaskBot", "Keine Posts zum Liken gefunden.")
        }
        
        // 2. Story Reaktion - Nur wenn Story vorhanden ist
        // Instagram markiert aktive Stories oft mit einer speziellen Content-Description oder ID
        val avatar = findNodesByViewId("com.instagram.android:id/profile_header_avatar_container").firstOrNull() 
                    ?: findNodesByViewId("com.instagram.android:id/row_profile_header_imageview").firstOrNull()
        
        if (avatar != null) {
            // Check if story is likely available (Story ring detection is hard, but we can try-click and check if we left the profile)
            Log.d("InstaTaskBot", "Versuche Story zu öffnen...")
            clickNode(avatar)
            randomDelay(3000, 5000)
            
            // Wenn wir immer noch auf dem Profil sind, gab es keine Story
            if (isProfileViewVisible()) {
                Log.d("InstaTaskBot", "Keine Story vorhanden oder Klick fehlgeschlagen.")
            } else {
                val fire = findNodeByText("🔥") ?: findNodeByContentDescription("🔥")
                if (fire != null) {
                    clickNode(fire)
                    Log.d("InstaTaskBot", "Story Reaktion gesendet!")
                    statsStoriesReacted++
                    sendStatsUpdate()
                    randomDelay(1000, 2000)
                } else {
                    Log.d("InstaTaskBot", "Keine Emoji-Reaktion in Story gefunden.")
                }
                // Zurück zum Profil
                performGlobalAction(GLOBAL_ACTION_BACK)
                randomDelay(2000, 3000)
            }
        }
    }

    private fun findLikeButton(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val queue = mutableListOf(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)
            val desc = node.contentDescription?.toString() ?: ""
            if (desc.contains("Gefällt mir", true) || desc.contains("Like", true)) {
                if (node.isClickable || (node.parent?.isClickable == true)) return node
            }
            // Reels Like Button ID oder Feed Like Button ID
            val ids = listOf(
                "com.instagram.android:id/row_feed_button_like",
                "com.instagram.android:id/like_button",
                "com.instagram.android:id/button_like"
            )
            if (ids.contains(node.viewIdResourceName)) return node
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private suspend fun commentOnReel() {
        val commentBtn = findNodeByContentDescription("Kommentieren") 
                      ?: findNodeByContentDescription("Comment")
                      ?: findNodesByViewId("com.instagram.android:id/comment_button").firstOrNull()
        
        if (commentBtn != null) {
            Log.d("InstaTaskBot", "Klicke auf Kommentar-Button...")
            clickNode(commentBtn)
            randomDelay(3000, 4000)
            
            val inputIds = listOf(
                "com.instagram.android:id/layout_comment_thread_edittext",
                "com.instagram.android:id/comment_edittext",
                "com.instagram.android:id/direct_text_input_store_front"
            )
            
            var input: AccessibilityNodeInfo? = null
            for (id in inputIds) {
                input = findNodesByViewId(id).firstOrNull()
                if (input != null) break
            }

            if (input == null) {
                // Letzter Versuch: Suche nach EditText Klasse
                input = findNodeByClass("android.widget.EditText")
            }

            if (input != null) {
                Log.d("InstaTaskBot", "Kommentarfeld gefunden. Fokussiere...")
                input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                randomDelay(1000, 2000)

                val text = if (Random.nextBoolean()) "🔥🔥🔥" else "lets go!"
                val arguments = android.os.Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                
                Log.d("InstaTaskBot", "Setze Text: $text")
                val success = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                
                if (!success) {
                    Log.w("InstaTaskBot", "ACTION_SET_TEXT fehlgeschlagen. Versuche es direkt...")
                }

                randomDelay(2000, 3000)
                
                val postBtn = findNodeByText("Posten") 
                           ?: findNodeByText("Post") 
                           ?: findNodesByViewId("com.instagram.android:id/layout_comment_thread_post_button").firstOrNull()
                           ?: findNodesByViewId("com.instagram.android:id/comment_post_button").firstOrNull()

                if (postBtn != null) {
                    Log.d("InstaTaskBot", "Klicke auf 'Posten'...")
                    clickNode(postBtn)
                    Log.d("InstaTaskBot", "Reel kommentiert: $text")
                    randomDelay(2000, 3000)
                } else {
                    Log.w("InstaTaskBot", "'Posten'-Button nicht gefunden.")
                }
            } else {
                Log.w("InstaTaskBot", "Kein Kommentar-Eingabefeld gefunden.")
            }
            // Zurück zum Reel-Viewer
            performGlobalAction(GLOBAL_ACTION_BACK)
            randomDelay(2000, 3000)
        }
    }

    private fun findNodeByClass(className: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val queue = mutableListOf(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)
            if (node.className?.toString() == className) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun sendStatsUpdate() {
        val intent = Intent("com.dopamin.instatask.STATS_UPDATE").apply {
            putExtra("PROFILES", statsProfilesScanned)
            putExtra("MATCHES", statsMatchesFound)
            putExtra("LIKES", statsLikesGiven)
            putExtra("STORIES", statsStoriesReacted)
        }
        sendBroadcast(intent)
    }

    private suspend fun randomDelay(min: Long, max: Long) {
        delay(Random.nextLong(min, max))
    }

    private fun humanScroll() {
        val path = Path().apply {
            moveTo(500f, 1500f)
            lineTo(500f, 600f)
        }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 500)).build(), null, null)
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable) {
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
            n = n.parent
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val path = Path().apply { moveTo(rect.centerX().toFloat(), rect.centerY().toFloat()) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 100)).build(), null, null)
    }

    private fun findNodeByText(t: String) = rootInActiveWindow?.findAccessibilityNodeInfosByText(t)?.firstOrNull()
    private fun findNodesByViewId(id: String) = rootInActiveWindow?.findAccessibilityNodeInfosByViewId(id) ?: emptyList<AccessibilityNodeInfo>()
    private fun findNodeByContentDescription(d: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val q = mutableListOf(root)
        while(q.isNotEmpty()){
            val n = q.removeAt(0)
            if(n.contentDescription?.toString()?.contains(d, true) == true) return n
            for(i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    private fun findFollowerNodes() = findNodesByViewId("com.instagram.android:id/follow_list_username")
    private fun isPublicProfile() = findNodeByText("Dieses Konto ist privat") == null && findNodeByText("Private") == null
    private fun isProfileViewVisible() = findNodesByViewId("com.instagram.android:id/profile_header_container").isNotEmpty() || 
                                       findNodesByViewId("com.instagram.android:id/profile_header_bio_text").isNotEmpty()
    private fun isReelVisible() = findNodesByViewId("com.instagram.android:id/reels_video_container").isNotEmpty()
    private fun hasPosts(): Boolean {
        val node = findNodeByText("Beiträge") ?: findNodeByText("Posts")
        return (node?.parent?.getChild(0)?.text?.toString()?.filter { it.isDigit() }?.toIntOrNull() ?: 0) > 0
    }
    private fun getBioText() = findNodesByViewId("com.instagram.android:id/profile_header_bio_text").firstOrNull()?.text?.toString() ?: ""

    private suspend fun takeScreenshotAsync(): Bitmap? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(Display.DEFAULT_DISPLAY, Executors.newSingleThreadExecutor(), object : TakeScreenshotCallback {
                override fun onSuccess(res: ScreenshotResult) {
                    val bm = Bitmap.wrapHardwareBuffer(res.hardwareBuffer, res.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    cont.resume(bm) {}
                }
                override fun onFailure(err: Int) = cont.resume(null) {}
            })
        } else cont.resume(null) {}
    }
}
