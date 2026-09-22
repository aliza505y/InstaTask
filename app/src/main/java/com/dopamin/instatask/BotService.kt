package com.dopamin.instatask

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
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
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.random.Random
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import kotlin.coroutines.coroutineContext

/**
 * InstaTask Bot Service - Clean, Standalone & Optimized Engine
 */
class BotService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var botJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Room Database Instance
    private lateinit var database: AppDatabase

    // Dynamic Lists & Configs
    private var targetProfiles = mutableListOf<String>()
    private var targetHashtags = mutableListOf<String>()
    private var isProfileLikingEnabled = true
    private var isHashtagLikingEnabled = false

    private enum class BotState { IDLE, NAVIGATING, BROWSING_FOLLOWERS, ANALYZING_PROFILE, INTERACTING }
    private var currentState = BotState.IDLE

    // Live Tracker Metrics
    private var currentSourceProfile = "None"
    private var currentTargetProfile = "None"
    private var statsProfilesScanned = 0
    private var statsProfilesFollowed = 0
    private var statsLikesGiven = 0
    private var statsCommentsSent = 0
    private var statsStoriesReacted = 0
    private var statsProfilesSkipped = 0
    private var statsErrorsEncountered = 0

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Kept lightweight to save UI thread performance
    }

    override fun onInterrupt() {
        stopBot()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_BOT" -> {
                loadUserSettings()
                startBot()
            }
            "STOP_BOT" -> stopBot()
            "CLEAR_STATS" -> clearDatabaseStats()
        }
        return START_STICKY
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        startForegroundServiceNotification()
        Log.d("InstaTaskBot", "Service Connected to Foreground")
    }

    private fun loadUserSettings() {
        val prefs = getSharedPreferences("InstaTaskPrefs", Context.MODE_PRIVATE)
        val profilesString = prefs.getString("SOURCE_PROFILES", "houseworksrec,loudkult,tomorrowland_music,kontorrecords,sirupmusic") ?: ""
        val hashtagsString = prefs.getString("TARGET_HASHTAGS", "housemusic,djlife") ?: ""

        targetProfiles = profilesString.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        targetHashtags = hashtagsString.split(",").map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.toMutableList()

        isProfileLikingEnabled = prefs.getBoolean("ENABLE_PROFILE_LIKING", true)
        isHashtagLikingEnabled = prefs.getBoolean("ENABLE_HASHTAG_LIKING", false)
    }

    private fun clearDatabaseStats() {
        serviceScope.launch(Dispatchers.IO) {
            database.botDao().clearAllLogs()
            database.botDao().clearAllProcessedProfiles()

            statsProfilesScanned = 0
            statsProfilesFollowed = 0
            statsLikesGiven = 0
            statsCommentsSent = 0
            statsStoriesReacted = 0
            statsProfilesSkipped = 0
            statsErrorsEncountered = 0

            withContext(Dispatchers.Main) {
                Toast.makeText(this@BotService, "Stats cleared successfully!", Toast.LENGTH_SHORT).show()
            }
            sendStatsUpdate()
        }
    }

    @SuppressLint("ForegroundServiceType")
    private fun startForegroundServiceNotification() {
        val channelId = "bot_foreground_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "InstaTask Running Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("InstaTask Bot is Active")
            .setContentText("Automating workflows safely in background...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(101, notification)
        }
    }

    private fun startBot() {
        if (botJob?.isActive == true) return

        startForegroundServiceNotification()

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "InstaTask::BotCPUWakeLock"
        ).apply {
            acquire(120 * 60 * 1000L) // 2 Hours max lock
        }

        serviceScope.launch(Dispatchers.Main) {
            Toast.makeText(this@BotService, "Bot started", Toast.LENGTH_SHORT).show()
        }

        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            Log.e("InstaTaskBot", "Caught unhandled coroutine error: ${throwable.localizedMessage}")
            statsErrorsEncountered++
            sendStatsUpdate()
        }

        botJob = serviceScope.launch(exceptionHandler) {
            Log.d("InstaTaskBot", "Workflow started...")

            // Workflow Mode 1: Target Profiles
            if (isProfileLikingEnabled && targetProfiles.isNotEmpty()) {
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
            }

            // Workflow Mode 2: Hashtags
            if (isHashtagLikingEnabled && targetHashtags.isNotEmpty()) {
                for (hashtag in targetHashtags) {
                    if (!coroutineContext.isActive) break
                    currentSourceProfile = "#$hashtag"
                    sendStatsUpdate()

                    try {
                        processHashtagWorkflow(hashtag)
                        randomDelay(8000, 15000)
                    } catch (e: Exception) {
                        statsErrorsEncountered++
                        Log.e("InstaTaskBot", "Error processing hashtag #$hashtag: ${e.localizedMessage}")
                        sendStatsUpdate()
                    }
                }
            }

            Log.d("InstaTaskBot", "Workflow finished.")
            stopBot()
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
        stopForeground(STOP_FOREGROUND_REMOVE)
        sendStatsUpdate()
    }

    // --- HASHTAG WORKFLOW ---
    private suspend fun processHashtagWorkflow(tag: String) {
        currentState = BotState.NAVIGATING
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("instagram://tag?name=$tag")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
        randomDelay(5000, 8000)

        val gridNodes = findPostGridItems()
        if (gridNodes.isNotEmpty()) {
            clickNode(gridNodes[0])
            randomDelay(3000, 4500)

            var hashtagInteracted = 0
            while (hashtagInteracted < 10 && coroutineContext.isActive) {
                val likeBtn = findLikeButton()
                if (likeBtn != null) {
                    clickNode(likeBtn)
                    statsLikesGiven++
                    sendStatsUpdate()
                    randomDelay(2000, 3000)
                }
                humanScroll()
                hashtagInteracted++
                randomDelay(3000, 5000)
            }
        }
        safeGoBack()
    }

    // --- PROFILE WORKFLOW ---
    private suspend fun processTargetProfile(username: String) {
        currentState = BotState.NAVIGATING
        Log.d("InstaTaskBot", "Navigating to target source profile: $username")

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("instagram://user?username=$username")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
        randomDelay(5000, 8000)

        if (!isProfileViewVisible()) {
            Log.d("InstaTaskBot", "Target profile view not visible, retrying navigation...")
            startActivity(intent)
            randomDelay(4000, 6000)
        }

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
            logActionToRoom(username, "SOURCE_ERROR", "Follower button not found on target profile", false)
            sendStatsUpdate()
        }
    }

    private suspend fun browseFollowers() {
        currentState = BotState.BROWSING_FOLLOWERS
        var interactionsCount = 0
        var scrollAttempts = 0
        var lastProcessedProfile = ""

        while (interactionsCount < 20 && scrollAttempts < 15 && coroutineContext.isActive) {

            if (!isFollowersListVisible()) {
                Log.d("InstaTaskBot", "⚠️ Lost followers list context! Restoring position...")
                val followersBtn = findFollowerButton()
                if (followersBtn != null) {
                    clickNode(followersBtn)
                    randomDelay(3000, 4000)
                    continue
                } else {
                    safeGoBack()
                    randomDelay(2000, 3000)
                    scrollAttempts++
                    continue
                }
            }

            if (checkAndHandlePopup()) {
                Log.d("InstaTaskBot", "⚠️ Popup detected! Resting safely for 30-40 seconds...")
                randomDelay(35000, 42000)
                safeGoBack()
                continue
            }

            if (isReelVisible()) {
                safeGoBack()
                randomDelay(2000, 3000)
                scrollAttempts++
                continue
            }

            val nodes = findFollowerNodes()
            if (nodes.isEmpty()) {
                humanScroll()
                randomDelay(2500, 3500)
                scrollAttempts++
                continue
            }

            var foundNew = false
            for (node in nodes) {
                if (!coroutineContext.isActive) break

                if (!isFollowersListVisible()) break

                val name = try { node.text?.toString()?.trim() ?: "" } catch (e: Exception) { "" }
                if (name.isEmpty() || name == lastProcessedProfile) continue

                val isAlreadyProcessed = withContext(Dispatchers.IO) {
                    database.botDao().isProfileProcessed(name)
                }

                if (isAlreadyProcessed) continue

                if (Random.nextFloat() < 0.10f) {
                    statsProfilesSkipped++
                    saveProcessedProfileToRoom(name, "SKIPPED_HUMANIZER")
                    sendStatsUpdate()
                    continue
                }

                foundNew = true
                scrollAttempts = 0
                currentTargetProfile = name
                lastProcessedProfile = name
                statsProfilesScanned++
                sendStatsUpdate()

                clickNode(node)
                Log.d("InstaTaskBot", ">>> Opening Source Follower: $name")
                randomDelay(3500, 5000)

                if (isProfileViewVisible()) {
                    if (isPublicProfile() && hasPosts()) {
                        Log.d("InstaTaskBot", "Interacting exclusively with source follower ($name)...")
                        sendStatsUpdate()

                        performInteractions(name)
                        saveProcessedProfileToRoom(name, "INTERACTED")

                        interactionsCount++
                        randomDelay(3000, 4500)
                    } else {
                        val reason = "Private or zero posts"
                        Log.d("InstaTaskBot", "Skipping $name - Reason: $reason")
                        statsProfilesSkipped++
                        saveProcessedProfileToRoom(name, "SKIPPED_FILTER")
                        logActionToRoom(name, "SKIPPED", reason, false)
                        sendStatsUpdate()
                    }

                    var exitAttempts = 0
                    while (!isFollowersListVisible() && exitAttempts < 6 && coroutineContext.isActive) {
                        safeGoBack()
                        randomDelay(1500, 2000)
                        exitAttempts++
                    }

                    if (isReelVisible() || isProfileViewVisible()) {
                        safeGoBack()
                        randomDelay(1500, 2000)
                    }

                    if (!isFollowersListVisible()) {
                        val fBtn = findFollowerButton()
                        if (fBtn != null) {
                            clickNode(fBtn)
                            randomDelay(3000, 4000)
                        }
                    }
                } else if (isReelVisible()) {
                    safeGoBack()
                    randomDelay(2000, 2500)
                }

                break
            }

            if (!foundNew) {
                humanScroll()
                randomDelay(2500, 3500)
                scrollAttempts++
            }
        }
    }

    private suspend fun followCurrentProfile(username: String): Boolean {
        val bioText = getBioText()
        val isMaleOrDJ = isMaleOrDJProfile(username, bioText)

        if (!isMaleOrDJ) {
            Log.d("InstaTaskBot", "Skipping Follow: $username is not matching Male/DJ criteria.")
            return false
        }

        val followTexts = listOf("Follow", "Folgen", "Seguir", "Follow Back")
        val root = rootInActiveWindow ?: return false
        val queue = mutableListOf(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)
            val text = try { node.text?.toString() ?: "" } catch (e: Exception) { "" }
            val desc = try { node.contentDescription?.toString() ?: "" } catch (e: Exception) { "" }

            if (text.equals("Following", true) || text.equals("Requested", true) ||
                desc.equals("Following", true) || desc.equals("Requested", true)) {
                return false
            }

            if ((followTexts.any { text.equals(it, true) } || followTexts.any { desc.equals(it, true) }) && node.isClickable) {
                clickNode(node)
                Log.d("InstaTaskBot", ">>> SUCCESS: Male/DJ Profile Followed ($username)! <<<")
                randomDelay(1500, 2500)
                return true
            }

            for (i in 0 until node.childCount) {
                try { node.getChild(i)?.let { queue.add(it) } } catch (e: Exception) {}
            }
        }
        return false
    }

    private suspend fun performInteractions(username: String) {
        currentState = BotState.INTERACTING

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

        var postOpened = false
        val gridNodes = findPostGridItems()

        if (gridNodes.isNotEmpty()) {
            Log.d("InstaTaskBot", "Opening post/reel from grid...")
            clickNode(gridNodes[0])
            randomDelay(3000, 4500)

            if (isContentOpened()) {
                postOpened = true
            } else {
                safeGoBack()
                randomDelay(1500, 2000)
            }
        }

        if (postOpened) {
            if (isReelVisible()) {
                commentOnReel(username)
                val likeBtn = findLikeButton()
                if (likeBtn != null) {
                    clickNode(likeBtn)
                    statsLikesGiven++
                    logActionToRoom(username, "REEL_LIKED", "Reel liked", true)
                    sendStatsUpdate()
                }
            } else {
                val likeBtn = findLikeButton()
                if (likeBtn != null) {
                    clickNode(likeBtn)
                    statsLikesGiven++
                    logActionToRoom(username, "POST_LIKED", "Post liked successfully", true)
                    sendStatsUpdate()
                    randomDelay(1500, 2500)
                }
            }

            var exitAttempts = 0
            while (!isProfileViewVisible() && exitAttempts < 3 && coroutineContext.isActive) {
                safeGoBack()
                randomDelay(1500, 2000)
                exitAttempts++
            }
        }

        if (isProfileViewVisible()) {
            val avatar = findNodesByViewId("com.instagram.android:id/profile_header_avatar_container").firstOrNull()
                ?: findNodesByViewId("com.instagram.android:id/row_profile_header_imageview").firstOrNull()

            if (avatar != null && isStoryRingPresent(avatar)) {
                clickNode(avatar)
                randomDelay(2500, 3500)

                if (isStoryViewActive()) {
                    var reacted = false
                    val replyBox = findNodeByText("Send message")
                        ?: findNodeByText("Nachricht senden")
                        ?: findNodeByText("Antworten")
                        ?: findNodesByViewId("com.instagram.android:id/story_text_view_field").firstOrNull()
                        ?: findNodesByViewId("com.instagram.android:id/direct_quick_reply_reel_composer_edittext").firstOrNull()

                    if (replyBox != null) {
                        clickNode(replyBox)
                        randomDelay(1500, 2000)

                        val emojiReaction = findNodeByText("😂")
                            ?: findNodeByContentDescription("😂")
                            ?: findNodeByText("😍")
                            ?: findNodeByContentDescription("😍")
                            ?: findNodeByText("🔥")
                            ?: findNodeByContentDescription("🔥")

                        if (emojiReaction != null) {
                            clickNode(emojiReaction)
                            statsStoriesReacted++
                            logActionToRoom(username, "STORY_REACTION", "Reacted to story with emoji", true)
                            sendStatsUpdate()
                            reacted = true
                            randomDelay(1500, 2000)
                        }
                    }

                    randomDelay(1000, 2000)
                    safeGoBack()
                    randomDelay(2000, 2500)

                    var safetyAttempts = 0
                    while (!isProfileViewVisible() && safetyAttempts < 2 && coroutineContext.isActive) {
                        safeGoBack()
                        randomDelay(1500, 2000)
                        safetyAttempts++
                    }
                } else {
                    safeGoBack()
                }
            }
        }

        if (isProfileViewVisible()) {
            val didFollow = followCurrentProfile(username)
            if (didFollow) {
                statsProfilesFollowed++
                saveProcessedProfileToRoom(username, "PROFILES_FOLLOWED")
                logActionToRoom(username, "PROFILES_FOLLOWED", "Successfully followed user", true)
                sendStatsUpdate()
                randomDelay(1500, 2000)
            }
        }

        if (!isProfileViewVisible()) {
            safeGoBack()
            randomDelay(1500, 2000)
        }
    }

    private fun isStoryRingPresent(avatarNode: AccessibilityNodeInfo): Boolean {
        return try {
            val desc = avatarNode.contentDescription?.toString()?.lowercase() ?: ""
            if (desc.contains("story", true) || desc.contains("active", true)) return true

            val root = rootInActiveWindow ?: return false
            val queue = mutableListOf(avatarNode)
            while (queue.isNotEmpty()) {
                val node = queue.removeAt(0)
                if (node.className?.toString()?.contains("ImageView", true) == true) {
                    val nodeDesc = node.contentDescription?.toString()?.lowercase() ?: ""
                    if (nodeDesc.contains("story", true)) return true
                }
                for (i in 0 until node.childCount) {
                    try { node.getChild(i)?.let { queue.add(it) } } catch (e: Exception) {}
                }
            }
            avatarNode.isClickable
        } catch (e: Exception) {
            false
        }
    }

    private fun isStoryViewActive(): Boolean {
        return findNodesByViewId("com.instagram.android:id/story_viewer_container").isNotEmpty() ||
                findNodesByViewId("com.instagram.android:id/reel_viewer_root_view").isNotEmpty() ||
                findNodeByText("Reply") != null ||
                findNodeByText("Send message") != null
    }

    private fun isFollowersListVisible(): Boolean {
        val root = rootInActiveWindow ?: return false
        val keywords = listOf("followers", "follower", "Followers", "Follower", "Abonnenten")
        val queue = mutableListOf(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)
            val text = try { node.text?.toString() ?: "" } catch (e: Exception) { "" }
            val desc = try { node.contentDescription?.toString() ?: "" } catch (e: Exception) { "" }

            if (keywords.any { text.contains(it, true) || desc.contains(it, true) }) {
                return true
            }

            for (i in 0 until node.childCount) {
                try { node.getChild(i)?.let { queue.add(it) } } catch (e: Exception) {}
            }
        }
        return false
    }

    private fun isMaleOrDJProfile(username: String, bio: String): Boolean {
        val combinedText = "$username $bio".lowercase()

        val femaleAndBusinessKeywords = listOf(
            "female", "girl", "woman", "mom", "she/her", "queen", "model",
            "makeup", "beauty", "fashionista", "lady", "wife", "sister",
            "salon", "lashes", "nails", "boutique", "hijab", "mua", "makeupartist"
        )
        if (femaleAndBusinessKeywords.any { combinedText.contains(it) }) return false

        val djOrMusicKeywords = listOf(
            "dj", "producer", "music", "remix", "beatmaker", "sound", "artist",
            "house", "techno", "edm", "label", "track", "records", "audio", "djane"
        )

        return djOrMusicKeywords.any { combinedText.contains(it) }
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
                val desc = try { node.contentDescription?.toString() ?: "" } catch (e: Exception) { "" }
                if (!desc.contains("profile", true) && !desc.contains("avatar", true)) {
                    list.add(node)
                }
            }
            for (i in 0 until node.childCount) {
                try { node.getChild(i)?.let { queue.add(it) } } catch (e: Exception) {}
            }
        }
        return list
    }

    private fun isContentOpened(): Boolean {
        return findLikeButton() != null ||
                findNodeByContentDescription("Comment") != null ||
                isReelVisible()
    }

    private fun findLikeButton(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val queue = mutableListOf(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)
            val desc = try { node.contentDescription?.toString() ?: "" } catch (e: Exception) { "" }

            if ((desc.equals("Like", true) || desc.startsWith("Like", true)) && !desc.contains("Liked", true)) {
                return if (node.isClickable) node else node.parent
            }

            val ids = listOf(
                "com.instagram.android:id/row_feed_button_like",
                "com.instagram.android:id/like_button",
                "com.instagram.android:id/button_like"
            )
            if (ids.contains(node.viewIdResourceName)) return node

            for (i in 0 until node.childCount) {
                try { node.getChild(i)?.let { queue.add(it) } } catch (e: Exception) {}
            }
        }
        return null
    }

    private suspend fun commentOnReel(username: String) {
        val commentBtn = findNodeByContentDescription("Comment")
            ?: findNodesByViewId("com.instagram.android:id/comment_button").firstOrNull()

        if (commentBtn != null) {
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

            if (input == null) input = findNodeByClass("android.widget.EditText")

            if (input != null) {
                clickNode(input)
                randomDelay(1000, 1500)

                input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                randomDelay(800, 1200)

                val commentText = reelComments.random()
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, commentText)
                }

                input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                randomDelay(2000, 3000)

                val postBtn = findNodeByText("Post")
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

            safeGoBack()
            randomDelay(1500, 2500)
        }
    }

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
        serviceScope.launch(Dispatchers.IO) {
            val totalLikes = database.botDao().getTotalLikesCount()
            val totalComments = database.botDao().getTotalCommentsCount()
            val totalStories = database.botDao().getTotalStoriesCount()
            val totalScanned = database.botDao().getTotalProfilesScanned()
            val totalFollowed = database.botDao().getTotalProfilesFollowed()
            val totalSkipped = database.botDao().getTotalProfilesSkipped()
            val totalErrors = database.botDao().getTotalErrors()

            val intent = Intent("com.dopamin.instatask.STATS_UPDATE").apply {
                putExtra("CURRENT_SOURCE", currentSourceProfile)
                putExtra("CURRENT_TARGET", currentTargetProfile)
                putExtra("PROFILES_SCANNED", totalScanned)
                putExtra("PROFILES_FOLLOWED", totalFollowed)
                putExtra("LIKES_GIVEN", totalLikes)
                putExtra("COMMENTS_SENT", totalComments)
                putExtra("STORIES_REACTED", totalStories)
                putExtra("PROFILES_SKIPPED", totalSkipped)
                putExtra("ERRORS", totalErrors)
            }
            sendBroadcast(intent)
        }
    }

    private fun findNodeByClass(className: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val queue = mutableListOf(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)
            if (node.className?.toString() == className) return node
            for (i in 0 until node.childCount) {
                try { node.getChild(i)?.let { queue.add(it) } } catch (e: Exception) {}
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
        try {
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
        } catch (e: Exception) {
            Log.e("InstaTaskBot", "Safely caught click exception: ${e.localizedMessage}")
        }
    }

    private fun checkAndHandlePopup(): Boolean {
        val blockKeywords = listOf("try again later", "action blocked", "restriction", "feedback required", "error")
        val root = rootInActiveWindow ?: return false
        val queue = mutableListOf(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)
            val text = try { node.text?.toString()?.lowercase() ?: "" } catch (e: Exception) { "" }

            if (blockKeywords.any { text.contains(it) }) {
                Log.w("InstaTaskBot", "⚠️ Restriction detected: $text. Pausing bot.")
                return true
            }
            for (i in 0 until node.childCount) {
                try { node.getChild(i)?.let { queue.add(it) } } catch (e: Exception) {}
            }
        }
        return false
    }

    private fun findNodeByText(t: String): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow?.findAccessibilityNodeInfosByText(t)?.firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun findNodesByViewId(id: String): List<AccessibilityNodeInfo> {
        return try {
            rootInActiveWindow?.findAccessibilityNodeInfosByViewId(id) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun findNodeByContentDescription(d: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val q = mutableListOf(root)
        while (q.isNotEmpty()) {
            val n = q.removeAt(0)
            val desc = try { n.contentDescription?.toString() ?: "" } catch (e: Exception) { "" }
            if (desc.contains(d, true)) return n
            for (i in 0 until n.childCount) try { n.getChild(i)?.let { q.add(it) } } catch (e: Exception) {}
        }
        return null
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
            val text = try { node.text?.toString() ?: "" } catch (e: Exception) { "" }
            if (keywords.any { text.contains(it, true) }) return node
            for (i in 0 until node.childCount) {
                try { node.getChild(i)?.let { queue.add(it) } } catch (e: Exception) {}
            }
        }
        return null
    }

    private fun findFollowerNodes(): List<AccessibilityNodeInfo> {
        val potentialIds = listOf(
            "com.instagram.android:id/follow_list_username",
            "com.instagram.android:id/row_user_username",
            "com.instagram.android:id/follow_user_row_username",
            "com.instagram.android:id/row_user_primary_name"
        )

        for (id in potentialIds) {
            val nodes = findNodesByViewId(id)
            if (nodes.isNotEmpty()) return nodes
        }

        val root = rootInActiveWindow ?: return emptyList()
        val list = mutableListOf<AccessibilityNodeInfo>()
        val queue = mutableListOf(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)
            if (node.className == "android.widget.TextView") {
                val text = try { node.text?.toString()?.trim() ?: "" } catch (e: Exception) { "" }
                if (text.isNotEmpty() && !text.contains(" ") && text.length < 30 &&
                    !text.equals("Follow", true) && !text.equals("Following", true) &&
                    !text.equals("Followers", true)) {
                    list.add(node)
                }
            }
            for (i in 0 until node.childCount) {
                try { node.getChild(i)?.let { queue.add(it) } } catch (e: Exception) {}
            }
        }
        return list
    }

    private fun isPublicProfile() = findNodeByText("This account is private") == null && findNodeByText("Private") == null
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