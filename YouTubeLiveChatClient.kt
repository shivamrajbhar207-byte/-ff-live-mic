package com.fflivemic.app

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** YouTube Data API v3 client. Invoke network methods from a worker thread. */
class YouTubeLiveChatClient(private val context: Context) {
    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
    }

    fun accessToken(account: Account): String =
        GoogleAuthUtil.getToken(context, account, "oauth2:$SCOPE")

    fun findActiveLiveChatId(token: String): String {
        val json = get(
            "https://www.googleapis.com/youtube/v3/liveBroadcasts" +
                "?part=snippet%2Cstatus&broadcastStatus=active&mine=true", token
        )
        val items = json.optJSONArray("items")
            ?: throw IllegalStateException("आपके चैनल पर कोई Active Live Broadcast नहीं मिला")
        for (i in 0 until items.length()) {
            val id = items.optJSONObject(i)?.optJSONObject("snippet")?.optString("liveChatId").orEmpty()
            if (id.isNotBlank() && id != "null") return id
        }
        throw IllegalStateException("Active Live Chat नहीं मिला। पहले YouTube पर लाइव शुरू करें।")
    }

    data class ChatPage(val messages: List<String>, val nextPageToken: String?, val pollingMillis: Long)

    fun getMessages(token: String, liveChatId: String, pageToken: String?): ChatPage {
        val query = buildString {
            append("https://www.googleapis.com/youtube/v3/liveChat/messages")
            append("?liveChatId=").append(java.net.URLEncoder.encode(liveChatId, "UTF-8"))
            append("&part=snippet%2CauthorDetails&maxResults=200")
            if (!pageToken.isNullOrBlank()) append("&pageToken=").append(java.net.URLEncoder.encode(pageToken, "UTF-8"))
        }
        val json = get(query, token)
        val items = json.optJSONArray("items")
        val messages = mutableListOf<String>()
        if (items != null) for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val author = item.optJSONObject("authorDetails")?.optString("displayName").orEmpty()
            val message = item.optJSONObject("snippet")?.optString("displayMessage").orEmpty()
            if (message.isNotBlank()) messages += "${author.ifBlank { "Viewer" }}: $message"
        }
        return ChatPage(messages, json.optString("nextPageToken").takeIf { it.isNotBlank() && it != "null" },
            json.optLong("pollingIntervalMillis", 5000L).coerceAtLeast(1000L))
    }

    private fun get(url: String, token: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15000; readTimeout = 20000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                val message = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
                throw IllegalStateException(if (message.isNotBlank()) "YouTube API: $message" else "YouTube API error ($code)")
            }
            return JSONObject(body)
        } finally { connection.disconnect() }
    }
}
