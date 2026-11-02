package com.fflivemic.app

/**
 * YouTube Live RTMP configuration.
 * Keep the stream key in memory only; never log it or commit it to source control.
 *
 * Typical YouTube ingest server: rtmp://a.rtmp.youtube.com/live2
 * Some accounts may be offered an RTMPS endpoint in YouTube Studio; use the exact
 * server URL shown by YouTube Studio instead of assuming a fixed endpoint.
 */
data class YouTubeRtmpConfig(
    val serverUrl: String,
    val streamKey: String
) {
    fun validatedPublishUrl(): String {
        val server = serverUrl.trim().trimEnd('/')
        val key = streamKey.trim()
        require(server.startsWith("rtmp://") || server.startsWith("rtmps://")) {
            "YouTube server URL must start with rtmp:// or rtmps://"
        }
        require(key.isNotBlank()) { "YouTube stream key is required" }
        require(!key.contains('/') && !key.contains('\n') && !key.contains('\r')) {
            "Stream key must be a single key, not a URL"
        }
        return "$server/$key"
    }

    fun redactedDescription(): String = "YouTube RTMP server configured; stream key hidden"
}
