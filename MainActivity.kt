package com.fflivemic.app

import android.Manifest
import android.accounts.Account
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val captureRequest = 4401
    private val signInRequest = 4403
    private lateinit var status: TextView
    private lateinit var chatStatus: TextView
    private lateinit var chatMessages: TextView
    private lateinit var serverInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var qualitySpinner: Spinner
    private lateinit var appChatToggle: CheckBox
    private lateinit var streamChatToggle: CheckBox
    private var pendingConfig: YouTubeRtmpConfig? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val chatClient by lazy { YouTubeLiveChatClient(applicationContext) }
    @Volatile private var chatRunning = false
    @Volatile private var chatToken: String? = null
    @Volatile private var liveChatId: String? = null
    @Volatile private var nextPageToken: String? = null
    private var chatPollDelay = 5000L
    private val messagesBuffer = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF101010.toInt()
        window.navigationBarColor = 0xFF101010.toInt()
        val prefs = getSharedPreferences("ff_live_mic", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(22, 30, 22, 28)
            setBackgroundColor(0xFF101010.toInt())
        }
        root.addView(TextView(this).apply {
            text = "🔥 FF LIVE MIC"; textSize = 30f; setTextColor(-1)
        })
        root.addView(TextView(this).apply {
            text = "YOUTUBE LIVE • GAME AUDIO • TEAM VOICE"; textSize = 12f
            setTextColor(0xFFFF8A50.toInt()); setPadding(0, 8, 0, 18)
        })
        status = TextView(this).apply {
            text = "तैयार — YouTube अकाउंट कनेक्ट करें"; textSize = 14f
            setTextColor(-1); setPadding(0, 8, 0, 14)
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "CONNECT YOUTUBE ACCOUNT"
            setOnClickListener { startYouTubeSignIn() }
        })
        root.addView(Button(this).apply {
            text = "DISCONNECT YOUTUBE"
            setOnClickListener { disconnectYouTube() }
        })
        chatStatus = TextView(this).apply {
            text = "YouTube Chat: कनेक्ट नहीं है"; textSize = 13f
            setTextColor(0xFFFFCC80.toInt()); setPadding(0, 10, 0, 8)
        }
        root.addView(chatStatus)
        appChatToggle = CheckBox(this).apply {
            text = "APP CHAT — ON/OFF (ऐप में कमेंट दिखाएँ)"
            setTextColor(-1); isChecked = prefs.getBoolean("app_chat", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("app_chat", checked).apply()
                chatMessages.visibility = if (checked) TextView.VISIBLE else TextView.GONE
            }
        }
        root.addView(appChatToggle)
        streamChatToggle = CheckBox(this).apply {
            text = "STREAM CHAT — ON/OFF (स्ट्रीम चैट विकल्प)"
            setTextColor(-1); isChecked = prefs.getBoolean("stream_chat", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("stream_chat", checked).apply()
                chatStatus.text = if (checked)
                    "Stream Chat चुना गया है। वीडियो पर वास्तविक overlay अगले capture-render चरण में जोड़ा जाएगा।"
                else "Stream Chat overlay बंद है।"
            }
        }
        root.addView(streamChatToggle)
        chatMessages = TextView(this).apply {
            text = "लाइव कमेंट यहाँ दिखाई देंगे।"; textSize = 13f
            setTextColor(-1); setPadding(12, 12, 12, 12)
            setBackgroundColor(0xFF202020.toInt())
            visibility = if (appChatToggle.isChecked) TextView.VISIBLE else TextView.GONE
        }
        root.addView(chatMessages, LinearLayout.LayoutParams(-1, 220))

        root.addView(label("YouTube Server URL"))
        serverInput = EditText(this).apply {
            setSingleLine(true); setText("rtmp://a.rtmp.youtube.com/live2")
            hint = "rtmp://a.rtmp.youtube.com/live2"; setTextColor(-1)
            setHintTextColor(0xFFAAAAAA.toInt())
        }
        root.addView(serverInput, fieldParams())
        root.addView(label("YouTube Stream Key"))
        keyInput = EditText(this).apply {
            setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "YouTube Studio से stream key"; setTextColor(-1)
            setHintTextColor(0xFFAAAAAA.toInt())
        }
        root.addView(keyInput, fieldParams())
        root.addView(label("Video quality"))
        qualitySpinner = spinner(listOf("Auto (recommended)", "720p", "1080p"))
        root.addView(qualitySpinner, fieldParams())
        root.addView(Button(this).apply {
            text = "START YOUTUBE LIVE"; setOnClickListener { validateAndRequestCapture() }
        })
        root.addView(Button(this).apply {
            text = "STOP LIVE STREAM"
            setOnClickListener {
                startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                status.text = "स्ट्रीम बंद करने का अनुरोध भेजा गया"
            }
        })
        root.addView(TextView(this).apply {
            text = "कैसे इस्तेमाल करें:\n1. YouTube अकाउंट Connect करें।\n2. लाइव शुरू होने पर Live Chat ऐप में दिखेगा।\n3. YouTube Studio का Server URL और Stream Key भरें।\n4. 720p/1080p चुनकर Start दबाएँ।\n\nऑडियो: Internal game audio Android 10+ और गेम की capture policy पर निर्भर है। कुछ फोन में गेम mic चालू होने पर Android दूसरे ऐप का mic रोक सकता है।\n\nनोट: APP CHAT अभी ऐप में कमेंट दिखाता है। STREAM CHAT का स्वतंत्र चयन सेव होता है; वीडियो में वास्तविक overlay अभी इस build में लागू नहीं है।"
            textSize = 12f; setTextColor(0xFFFFCC80.toInt()); setPadding(0, 18, 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(root) })

        GoogleSignIn.getLastSignedInAccount(this)?.let { account ->
            status.text = "YouTube अकाउंट: ${account.email ?: account.displayName ?: "Connected"}"
            obtainTokenAndStartChat(account)
        }
    }

    private fun startYouTubeSignIn() {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(YouTubeLiveChatClient.SCOPE))
            .build()
        startActivityForResult(GoogleSignIn.getClient(this, options).signInIntent, signInRequest)
    }

    private fun disconnectYouTube() {
        stopChat()
        GoogleSignIn.getClient(this, GoogleSignInOptions.DEFAULT_SIGN_IN).signOut()
        status.text = "YouTube अकाउंट disconnect कर दिया गया।"
        chatStatus.text = "YouTube Chat: कनेक्ट नहीं है"
    }

    private fun obtainTokenAndStartChat(account: GoogleSignInAccount) {
        val accountRef = account.account
        if (accountRef == null) { status.text = "Google अकाउंट जानकारी नहीं मिली"; return }
        status.text = "YouTube अनुमति जाँची जा रही है…"
        worker.execute {
            try {
                val token = chatClient.accessToken(accountRef)
                chatToken = token
                val id = chatClient.findActiveLiveChatId(token)
                liveChatId = id
                nextPageToken = null
                chatRunning = true
                mainHandler.post {
                    status.text = "YouTube अकाउंट Connected: ${account.email.orEmpty()}"
                    chatStatus.text = "Live Chat connected — कमेंट लोड हो रहे हैं"
                }
                pollChat()
            } catch (e: UserRecoverableAuthException) {
                mainHandler.post { status.text = "YouTube अनुमति पूरी करें और फिर Connect दबाएँ।"; runCatching { startActivityForResult(e.intent, signInRequest + 1) } }
            } catch (e: Exception) {
                mainHandler.post { status.text = "YouTube Chat: ${e.message ?: "कनेक्शन विफल"}" }
            }
        }
    }

    private fun pollChat() {
        if (!chatRunning) return
        worker.execute {
            try {
                val token = chatToken ?: return@execute
                val id = liveChatId ?: return@execute
                val page = chatClient.getMessages(token, id, nextPageToken)
                nextPageToken = page.nextPageToken
                chatPollDelay = page.pollingMillis
                if (getSharedPreferences("ff_live_mic", MODE_PRIVATE).getBoolean("app_chat", true) && page.messages.isNotEmpty()) {
                    synchronized(messagesBuffer) {
                        page.messages.forEach { messagesBuffer.addLast(it) }
                        while (messagesBuffer.size > 80) messagesBuffer.removeFirst()
                    }
                    val display = synchronized(messagesBuffer) { messagesBuffer.joinToString("\n") }
                    mainHandler.post { if (appChatToggle.isChecked) chatMessages.text = display }
                }
            } catch (e: Exception) {
                mainHandler.post { chatStatus.text = "Chat रुक गया: ${e.message ?: "API error"}" }
                chatRunning = false
                return@execute
            }
            mainHandler.postDelayed({ pollChat() }, chatPollDelay)
        }
    }

    private fun stopChat() { chatRunning = false; chatToken = null; liveChatId = null }

    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
    }
    private fun label(value: String) = TextView(this).apply {
        text = value; textSize = 13f; setTextColor(0xFFFFCC80.toInt()); gravity = Gravity.START
    }
    private fun fieldParams() = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 }

    private fun validateAndRequestCapture() {
        try {
            pendingConfig = YouTubeRtmpConfig(serverInput.text.toString(), keyInput.text.toString()).also { it.validatedPublishUrl() }
        } catch (e: IllegalArgumentException) { status.text = e.message ?: "RTMP details जाँचें"; return }
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.POST_NOTIFICATIONS
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 4402) else requestScreenCapture()
    }
    private fun requestScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
    }
    @Deprecated("Permission callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 4402) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) requestScreenCapture()
            else status.text = "माइक अनुमति अस्वीकार — streaming शुरू नहीं हो सकती"
        }
    }
    @Deprecated("Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == signInRequest && resultCode == RESULT_OK && data != null) {
            try {
                val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
                obtainTokenAndStartChat(account)
            } catch (e: ApiException) { status.text = "Google Login विफल: ${e.statusCode}" }
            return
        }
        if (requestCode == signInRequest + 1) {
            GoogleSignIn.getLastSignedInAccount(this)?.let { obtainTokenAndStartChat(it) }
            return
        }
        if (requestCode == captureRequest) {
            if (resultCode == RESULT_OK && data != null) {
                val config = pendingConfig ?: run { status.text = "RTMP configuration गायब"; return }
                val quality = when (qualitySpinner.selectedItemPosition) { 0 -> CaptureService.QUALITY_AUTO; 1 -> CaptureService.QUALITY_HD; else -> CaptureService.QUALITY_ULTRA }
                val service = Intent(this, CaptureService::class.java).apply {
                    action = CaptureService.ACTION_START
                    putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode); putExtra(CaptureService.EXTRA_DATA, data)
                    putExtra(CaptureService.EXTRA_SERVER, config.serverUrl); putExtra(CaptureService.EXTRA_KEY, config.streamKey)
                    putExtra(CaptureService.EXTRA_QUALITY, quality)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service) else startService(service)
                status.text = "स्ट्रीम शुरू हो रही है — YouTube Studio Preview जाँचें"
                keyInput.text.clear(); pendingConfig = null
            } else status.text = "स्क्रीन अनुमति रद्द — Streaming शुरू नहीं हुई"
        }
    }
    override fun onDestroy() { stopChat(); worker.shutdownNow(); super.onDestroy() }
}
