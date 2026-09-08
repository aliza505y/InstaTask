package com.dopamin.instatask

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.random.Random
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * InstaTask Bot Service - Robust Instagram Automation Engine
 * Fixed Navigation Back, Story Re-click loop, Reel comment, and Reel loop bugs.
 */
class BotService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var botJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Room Database Instance
    private lateinit var database: AppDatabase

    private val targetProfiles = listOf(
        "houseworksrec", "loudkult", "sirupmusic", "tomorrowland_music", "kontorrecords"
    )

    private val generativeModel = GenerativeModel(
        modelName = "gemini-1.5-pro",
        apiKey = BuildConfig.GEMINI_API_KEY
    )

    private enum class BotState { IDLE, NAVIGATING, BROWSING_FOLLOWERS, ANALYZING_PROFILE, INTERACTING }
    private var currentState = BotState.IDLE

    // Live Tracker Metrics
    private var currentSourceProfile = "None"
    private var currentTargetProfile = "None"
    private var statsProfilesScanned = 0
    private var statsMatchesFound = 0
    private var statsLikesGiven = 0
    private var statsCommentsSent = 0
    private var statsStoriesReacted = 0
    private var statsProfilesSkipped = 0
    private var statsErrorsEncountered = 0

    // Random comments array for Reels
    private val reelComments = listOf(
        "Banger track! 🎶🔥",
        "Absolute vibe! 🙌🎧",
        "Adding this to my playlist 🎵🔥",
        "Super clean production! 🔊🔥"
    )

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
    }

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

        Toast.makeText(this, "Bot started", Toast.LENGTH_SHORT).show()
        botJob = serviceScope.launch {
            Log.d("InstaTaskBot", "Workflow started...")
            for (profile in targetProfiles) {
                if (!coroutineContext.isActive) break
                currentSourceProfile = profile
                sendStatsUpdate()

                try {
                    processTargetProfile(profile)
                    randomDelay(8000, 15000)
                } catch (e: Exception) {
                    statsErrorsEncountered++
                    Log.e("InstaTaskBot", "Error processing $profile: ${e.localizedMessage}")
                    logActionToRoom(profile, "ERROR", e.localizedMessage ?: "Unknown Exception", false)
                    sendStatsUpdate()
                }
            }
            Log.d("InstaTaskBot", "Workflow finished.")
        }
    }

    private fun stopBot() {
        botJob?.cancel()
        botJob = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        currentState = BotState.IDLE
        currentSourceProfile = "None"
        currentTargetProfile = "None"
        sendStatsUpdate()
    }

    private suspend fun processTargetProfile(username: String) {
        currentState = BotState.NAVIGATING
        Log.d("InstaTaskBot", "Navigating to $username")

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
            safeGoBack()
            randomDelay(2000, 3000)
        } else {
            statsErrorsEncountered++
            logActionToRoom(username, "SOURCE_ERROR", "Follower button not found", false)
            sendStatsUpdate()
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
        val keywords = listOf("followers", "follower", "Followers", "Follower", "Abonnenten")
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
        var interactionsCount = 0
        var scrollAttempts = 0

        while (interactionsCount < 20 && scrollAttempts < 15 && coroutineContext.isActive) {
            if (isReelVisible()) {
                safeGoBack()
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

            val allVisibleAlreadyProcessed = withContext(Dispatchers.IO) {
                nodes.all { node ->
                    val name = node.text?.toString() ?: ""
                    name.isNotEmpty() && database.botDao().isProfileProcessed(name)
                }
            }

            if (allVisibleAlreadyProcessed) {
                Log.d("InstaTaskBot", "Screen profiles already processed. Fast-scrolling...")
                humanScroll()
                randomDelay(1000, 1500)
                scrollAttempts++
                continue
            }

            var foundNew = false
            for (node in nodes) {
                if (!coroutineContext.isActive) break
                val name = node.text?.toString() ?: continue

                val isAlreadyProcessed = withContext(Dispatchers.IO) {
                    database.botDao().isProfileProcessed(name)
                }

                if (isAlreadyProcessed) {
                    Log.d("InstaTaskBot", "Skipping $name - Already saved in Room DB")
                    continue
                }

                if (Random.nextFloat() < 0.10f) {
                    Log.d("InstaTaskBot", "Humanizer: Skipping $name")
                    statsProfilesSkipped++
                    saveProcessedProfileToRoom(name, "SKIPPED_HUMANIZER")
                    sendStatsUpdate()
                    continue
                }

                foundNew = true
                scrollAttempts = 0
                currentTargetProfile = name
                statsProfilesScanned++
                sendStatsUpdate()

                clickNode(node)
                Log.d("InstaTaskBot", ">>> Opening Profile: $name")
                randomDelay(3000, 5000)

                if (isProfileViewVisible()) {
                    val bioText = getBioText()
                    val isMaleOrDJ = isMaleOrDJProfile(name, bioText)

                    if (isPublicProfile() && hasPosts() && isMaleOrDJ) {
                        Log.d("InstaTaskBot", "Matching Profile ($name). Interacting...")
                        statsMatchesFound++
                        sendStatsUpdate()

                        performInteractions(name)
                        saveProcessedProfileToRoom(name, "INTERACTED")

                        interactionsCount++
                        randomDelay(3000, 5000)
                    } else {
                        val reason = if (!isMaleOrDJ) "Non-male / Non-DJ profile" else "Private or zero posts"
                        Log.d("InstaTaskBot", "Skipping $name - Reason: $reason")
                        statsProfilesSkipped++
                        saveProcessedProfileToRoom(name, "SKIPPED_FILTER")
                        logActionToRoom(name, "SKIPPED", reason, false)
                        sendStatsUpdate()
                    }

                    // Profile se wapis followers list par aana
                    safeGoBack()
                    randomDelay(1500, 2500)
                } else if (isReelVisible()) {
                    safeGoBack()
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

        // 1. 24-HOUR LIKES LIMIT CHECK
        val twentyFourHoursAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
        val recentLikesCount = withContext(Dispatchers.IO) {
            database.botDao().getLikesCountSince(twentyFourHoursAgo)
        }

        if (recentLikesCount >= 150) {
            Log.d("InstaTaskBot", "24-Hour Limit hit ($recentLikesCount/150). Pausing bot.")
            logActionToRoom(username, "LIMIT_REACHED", "Hit 150 likes limit in 24h", false)
            stopBot()
            return
        }

        // 2. POST / REEL INTERACTION
        var postOpened = false
        val gridNodes = findPostGridItems()

        if (gridNodes.isNotEmpty()) {
            Log.d("InstaTaskBot", "Opening post/reel from grid...")
            clickNode(gridNodes[0])
            randomDelay(3000, 4500)

            if (isContentOpened()) {
                postOpened = true
            } else {
                Log.d("InstaTaskBot", "Grid click missed. Pressing back...")
                safeGoBack()
                randomDelay(1500, 2000)
            }
        }

        if (postOpened) {
            if (isReelVisible()) {
                Log.d("InstaTaskBot", "Reel screen active. Commenting and Liking...")
                commentOnReel(username)
                val likeBtn = findLikeButton()
                if (likeBtn != null) {
                    clickNode(likeBtn)
                    statsLikesGiven++
                    logActionToRoom(username, "REEL_LIKED", "Reel liked", true)
                    sendStatsUpdate()
                }
            } else {
                Log.d("InstaTaskBot", "Standard Post active. Liking...")
                val likeBtn = findLikeButton()
                if (likeBtn != null) {
                    clickNode(likeBtn)
                    Log.d("InstaTaskBot", ">>> SUCCESS: Post Liked! <<<")
                    statsLikesGiven++
                    logActionToRoom(username, "POST_LIKED", "Post liked successfully", true)
                    sendStatsUpdate()
                    randomDelay(1500, 2500)
                }
            }

            Log.d("InstaTaskBot", "Exiting Post/Reel view...")
            safeGoBack()
            randomDelay(2000, 3000)
        }

        // 3. STORY REACTION EXECUTION (FIXED LOOP ISSUE)
        if (isProfileViewVisible()) {
            val avatar = findNodesByViewId("com.instagram.android:id/profile_header_avatar_container").firstOrNull()
                ?: findNodesByViewId("com.instagram.android:id/row_profile_header_imageview").firstOrNull()

            if (avatar != null) {
                Log.d("InstaTaskBot", "Checking user story...")
                clickNode(avatar)
                randomDelay(2500, 3500)

                // Check if story actually opened (Profile visible na ho)
                if (!isProfileViewVisible()) {
                    val fire = findNodeByText("🔥") ?: findNodeByContentDescription("🔥")
                    if (fire != null) {
                        clickNode(fire)
                        Log.d("InstaTaskBot", "Story reaction sent!")
                        statsStoriesReacted++
                        logActionToRoom(username, "STORY_REACTION", "Sent fire emoji", true)
                        sendStatsUpdate()
                        randomDelay(1500, 2000)
                    }

                    // Story close karne ke liye safe exit
                    safeGoBack()
                    randomDelay(2000, 2500)
                } else {
                    Log.d("InstaTaskBot", "No story active.")
                }
            }
        }

        // Fallback: Agar profile screen par wapis na pohncha ho
        if (!isProfileViewVisible()) {
            safeGoBack()
            randomDelay(1500, 2000)
        }
    }

    private fun isMaleOrDJProfile(username: String, bio: String): Boolean {
        val djKeywords = listOf(
            "dj", "producer", "music", "remix", "beatmaker", "sound", "artist",
            "house", "techno", "edm", "label", "track", "records", "audio"
        )
        val femaleKeywords = listOf(
            "female", "girl", "woman", "mom", "she/her", "queen", "model",
            "makeup", "beauty", "fashionista", "lady", "wife", "sister"
        )

        val combinedText = "$username $bio".lowercase()

        if (femaleKeywords.any { combinedText.contains(it) }) return false
        if (djKeywords.any { combinedText.contains(it) }) return true
        return true
    }

    private fun findPostGridItems(): List<AccessibilityNodeInfo> {
        val ids = listOf(
            "com.instagram.android:id/image_button",
            "com.instagram.android:id/media_set_row_content_container",
            "com.instagram.android:id/row_profile_header_container",
            "com.instagram.android:id/view_coauthor_single_image"
        )
        for (id in ids) {
            val nodes = findNodesByViewId(id)
            if (nodes.isNotEmpty()) return nodes
        }

        val root = rootInActiveWindow ?: return emptyList()
        val list = mutableListOf<AccessibilityNodeInfo>()
        val queue = mutableListOf(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)
            if (node.className == "android.widget.ImageView" && node.isClickable) {
                val desc = node.contentDescription?.toString() ?: ""
                if (!desc.contains("profile", true) && !desc.contains("avatar", true)) {
                    list.add(node)
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return list
    }

    private fun isContentOpened(): Boolean {
        return findLikeButton() != null ||
                findNodeByContentDescription("Comment") != null ||
                findNodeByContentDescription("Kommentieren") != null ||
                isReelVisible()
    }

    private fun findLikeButton(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val queue = mutableListOf(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)
            val desc = node.contentDescription?.toString() ?: ""

            if ((desc.equals("Like", true) || desc.equals("Gefällt mir", true) || desc.startsWith("Like", true)) &&
                !desc.contains("Liked", true)) {
                return if (node.isClickable) node else node.parent
            }

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

    // 🟢 REEL COMMENT AUTOMATION (FIXED COMMENTING & FOCUS ISSUE)
    private suspend fun commentOnReel(username: String) {
        val commentBtn = findNodeByContentDescription("Comment")
            ?: findNodeByContentDescription("Kommentieren")
            ?: findNodesByViewId("com.instagram.android:id/comment_button").firstOrNull()

        if (commentBtn != null) {
            Log.d("InstaTaskBot", "Opening Reel comment section...")
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
                input = findNodeByClass("android.widget.EditText")
            }

            if (input != null) {
                // Input area ko tap karna zaroori hai taake keyboard Focus enable ho sakay
                clickNode(input)
                randomDelay(1000, 1500)

                input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                randomDelay(800, 1200)

                // Pick a dynamic comment from the list
                val commentText = reelComments.random()
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, commentText)
                }

                input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                randomDelay(2000, 3000)

                val postBtn = findNodeByText("Post")
                    ?: findNodeByText("Posten")
                    ?: findNodesByViewId("com.instagram.android:id/layout_comment_thread_post_button").firstOrNull()
                    ?: findNodesByViewId("com.instagram.android:id/comment_post_button").firstOrNull()

                if (postBtn != null) {
                    clickNode(postBtn)
                    statsCommentsSent++
                    logActionToRoom(username, "COMMENT_SENT", "Commented: $commentText", true)
                    sendStatsUpdate()
                    randomDelay(2000, 3000)
                }
            }

            // Comment sheet close karne ke liye back
            safeGoBack()
            randomDelay(1500, 2500)
        }
    }

    // 🟢 SAFE BACK ACTION (Stuck Navigation Fix)
    private suspend fun safeGoBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        randomDelay(800, 1200)
    }

    private suspend fun saveProcessedProfileToRoom(username: String, action: String) {
        withContext(Dispatchers.IO) {
            database.botDao().insertProcessedProfile(
                ProcessedProfile(
                    username = username,
                    sourceProfile = currentSourceProfile,
                    actionTaken = action,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    private suspend fun logActionToRoom(target: String, actionType: String, details: String, isSuccess: Boolean) {
        withContext(Dispatchers.IO) {
            database.botDao().insertLog(
                AutomationLog(
                    id = 0,
                    sourceProfile = currentSourceProfile,
                    targetUsername = target,
                    actionType = actionType,
                    details = details,
                    isSuccess = isSuccess,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    private fun sendStatsUpdate() {
        val intent = Intent("com.dopamin.instatask.STATS_UPDATE").apply {
            putExtra("CURRENT_SOURCE", currentSourceProfile)
            putExtra("CURRENT_TARGET", currentTargetProfile)
            putExtra("PROFILES_SCANNED", statsProfilesScanned)
            putExtra("MATCHES_FOUND", statsMatchesFound)
            putExtra("LIKES_GIVEN", statsLikesGiven)
            putExtra("COMMENTS_SENT", statsCommentsSent)
            putExtra("STORIES_REACTED", statsStoriesReacted)
            putExtra("PROFILES_SKIPPED", statsProfilesSkipped)
            putExtra("ERRORS", statsErrorsEncountered)
        }
        sendBroadcast(intent)
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
        while (q.isNotEmpty()) {
            val n = q.removeAt(0)
            if (n.contentDescription?.toString()?.contains(d, true) == true) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    private fun findFollowerNodes() = findNodesByViewId("com.instagram.android:id/follow_list_username")
    private fun isPublicProfile() = findNodeByText("This account is private") == null && findNodeByText("Dieses Konto ist privat") == null && findNodeByText("Private") == null
    private fun isProfileViewVisible() = findNodesByViewId("com.instagram.android:id/profile_header_container").isNotEmpty() ||
            findNodesByViewId("com.instagram.android:id/profile_header_bio_text").isNotEmpty()
    private fun isReelVisible() = findNodesByViewId("com.instagram.android:id/reels_video_container").isNotEmpty()
    private fun hasPosts(): Boolean {
        val node = findNodeByText("Posts") ?: findNodeByText("Beiträge")
        return (node?.parent?.getChild(0)?.text?.toString()?.filter { it.isDigit() }?.toIntOrNull() ?: 0) > 0
    }
    private fun getBioText() = findNodesByViewId("com.instagram.android:id/profile_header_bio_text").firstOrNull()?.text?.toString() ?: ""

    private suspend fun takeScreenshotAsync(): Bitmap? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(Display.DEFAULT_DISPLAY, Executors.newSingleThreadExecutor(), object : TakeScreenshotCallback {
                override fun onSuccess(res: ScreenshotResult) {
                    val bm = Bitmap.wrapHardwareBuffer(res.hardwareBuffer, res.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    cont.resume(bm)
                }
                override fun onFailure(err: Int) {
                    cont.resume(null)
                }
            })
        } else cont.resume(null)
    }
}