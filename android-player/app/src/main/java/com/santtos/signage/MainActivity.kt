package com.santtos.signage

import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private lateinit var player: ExoPlayer
    private lateinit var overlay: TextView
    private var deviceId: String? = null
    private var currentMediaId: String? = null
    private var lastStartedMediaId: String? = null
    private var appliedManifestHash: String? = null
    private val eventMutex = Mutex()
    private lateinit var serverBaseUrl: String

    private val prefs by lazy { getSharedPreferences("signage", MODE_PRIVATE) }
    private val cacheRoot by lazy { File(filesDir, "signage-cache").apply { mkdirs() } }
    private val manifestFile by lazy { File(filesDir, "manifest.json") }
    private val eventQueueFile by lazy { File(filesDir, "event-queue.json") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val fromAdb = intent?.getStringExtra("server")?.trim()
        if (!fromAdb.isNullOrBlank()) prefs.edit().putString("serverBaseUrl", normalizeServer(fromAdb)).apply()
        serverBaseUrl = prefs.getString("serverBaseUrl", Config.DEFAULT_SERVER_BASE_URL) ?: Config.DEFAULT_SERVER_BASE_URL
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        player = ExoPlayer.Builder(this).build()
        val playerView = PlayerView(this).apply { useController = false; player = this@MainActivity.player }
        overlay = TextView(this).apply {
            setTextColor(0xffffffff.toInt()); textSize = 24f; gravity = Gravity.CENTER; setBackgroundColor(0xff0a0d12.toInt())
        }
        val frame = FrameLayout(this)
        frame.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                val newId = item?.mediaId
                lastStartedMediaId?.let { previous ->
                    when (reason) {
                        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> queueEvent(previous, "COMPLETE")
                        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> queueEvent(previous, "INTERRUPTED")
                    }
                }
                currentMediaId = newId
                lastStartedMediaId = newId
                newId?.let { queueEvent(it, "START") }
            }
            override fun onPlayerError(error: PlaybackException) {
                currentMediaId?.let { queueEvent(it, "ERROR") }
                if (player.mediaItemCount > 1) player.seekToNextMediaItem()
                player.prepare(); player.playWhenReady = true
            }
        })
        scope.launch { boot() }
    }

    private fun deviceCode(): String {
        val raw = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        return raw.takeLast(8).uppercase()
    }

    private suspend fun boot() {
        deviceId = prefs.getString("deviceId", null)
        if (manifestFile.exists()) {
            try { applyManifest(JSONObject(manifestFile.readText()), allowDownload = false) } catch (_: Exception) {}
        }
        if (player.mediaItemCount == 0) showStatus("SANTTOS SIGNAGE\n\nCódigo: ${deviceCode()}\nCentral: $serverBaseUrl\nConectando...")

        while (deviceId == null && scope.isActive) {
            try {
                val r = post("api/player/register", JSONObject().put("code", deviceCode()).put("appVersion", Config.APP_VERSION))
                deviceId = r.getString("id")
                prefs.edit().putString("deviceId", deviceId).apply()
            } catch (_: Exception) { delay(5000) }
        }
        try { sync() } catch (_: Exception) {}
        scope.launch { heartbeatLoop() }
        scope.launch { syncLoop() }
        scope.launch { eventFlushLoop() }
        scope.launch { playbackWatchdogLoop() }
    }

    private fun showStatus(text: String) { overlay.visibility = View.VISIBLE; overlay.text = text }

    private suspend fun heartbeatLoop() {
        while (scope.isActive) {
            try {
                val r = post("api/player/$deviceId/heartbeat", JSONObject().put("appVersion", Config.APP_VERSION).put("currentMedia", currentMediaId ?: ""))
                r.optJSONArray("commands")?.let { arr ->
                    for (i in 0 until arr.length()) when (arr.getJSONObject(i).getString("type")) {
                        "SYNC" -> sync()
                        "CLEAR_CACHE" -> clearCacheAndSync()
                        "RESTART_APP" -> withContext(Dispatchers.Main) { recreate() }
                    }
                }
            } catch (_: Exception) {}
            delay(5000)
        }
    }

    private suspend fun syncLoop() { while (scope.isActive) { delay(60_000); try { sync() } catch (_: Exception) {} } }

    private suspend fun playbackWatchdogLoop() {
        var lastPos = -1L; var stuckFor = 0
        while (scope.isActive) {
            delay(5000)
            if (player.isPlaying) {
                val p = player.currentPosition
                if (kotlin.math.abs(p - lastPos) < 500) stuckFor += 5 else stuckFor = 0
                lastPos = p
                if (stuckFor >= 20) {
                    currentMediaId?.let { queueEvent(it, "STALLED") }
                    if (player.mediaItemCount > 1) player.seekToNextMediaItem() else player.seekTo(0)
                    player.prepare(); player.playWhenReady = true; stuckFor = 0
                }
            } else if (player.mediaItemCount > 0 && player.playbackState == Player.STATE_READY) {
                player.playWhenReady = true
            }
        }
    }

    private suspend fun sync() {
        val json = JSONObject(get("api/player/$deviceId/manifest"))
        applyManifest(json, allowDownload = true)
    }

    private suspend fun clearCacheAndSync() {
        withContext(Dispatchers.IO) { cacheRoot.deleteRecursively(); cacheRoot.mkdirs(); manifestFile.delete() }
        appliedManifestHash = null
        sync()
    }

    private suspend fun applyManifest(json: JSONObject, allowDownload: Boolean) {
        val hash = sha256(json.toString())
        if (hash == appliedManifestHash && player.mediaItemCount > 0) return
        val items = json.optJSONArray("items") ?: JSONArray()
        if (items.length() == 0) {
            if (player.mediaItemCount == 0) showStatus("SANTTOS SIGNAGE\n\nPlayer ${deviceCode()}\nAguardando playlist")
            return
        }

        val mediaItems = mutableListOf<MediaItem>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val mid = item.getString("media_id")
            val url = item.getString("url")
            val ext = url.substringAfterLast('.', "mp4").substringBefore('?').take(8)
            val file = File(cacheRoot, "$mid.$ext")
            if (!file.exists()) {
                if (!allowDownload) continue
                downloadAtomic(url, file)
            }
            mediaItems += MediaItem.Builder().setMediaId(mid).setUri(file.toURI().toString()).build()
        }
        if (mediaItems.isEmpty()) return
        if (allowDownload) withContext(Dispatchers.IO) { manifestFile.writeText(json.toString()) }
        withContext(Dispatchers.Main) {
            val oldId = currentMediaId
            player.setMediaItems(mediaItems, true)
            player.repeatMode = Player.REPEAT_MODE_ALL
            player.prepare(); player.playWhenReady = true
            overlay.visibility = View.GONE
            appliedManifestHash = hash
            if (oldId != null) {
                val idx = mediaItems.indexOfFirst { it.mediaId == oldId }
                if (idx >= 0) player.seekTo(idx, 0)
            }
        }
    }

    private fun queueEvent(mediaId: String, event: String) {
        val position = player.currentPosition
        scope.launch(Dispatchers.IO) {
            eventMutex.withLock {
                val arr = readEventQueue()
                arr.put(JSONObject().put("id", UUID.randomUUID().toString()).put("mediaId", mediaId).put("event", event).put("at", System.currentTimeMillis()).put("positionMs", position))
                eventQueueFile.writeText(arr.toString())
            }
        }
    }

    private suspend fun eventFlushLoop() {
        while (scope.isActive) {
            try {
                eventMutex.withLock {
                    val arr = withContext(Dispatchers.IO) { readEventQueue() }
                    if (arr.length() > 0) {
                        val remaining = JSONArray()
                        for (i in 0 until arr.length()) {
                            val e = arr.getJSONObject(i)
                            try { post("api/player/$deviceId/event", e) } catch (_: Exception) { for (j in i until arr.length()) remaining.put(arr.getJSONObject(j)); break }
                        }
                        withContext(Dispatchers.IO) { eventQueueFile.writeText(remaining.toString()) }
                    }
                }
            } catch (_: Exception) {}
            delay(5000)
        }
    }

    private fun readEventQueue(): JSONArray = try { if (eventQueueFile.exists()) JSONArray(eventQueueFile.readText()) else JSONArray() } catch (_: Exception) { JSONArray() }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(serverBaseUrl + path).build()).execute().use { r -> if (!r.isSuccessful) throw Exception("HTTP ${r.code}"); r.body?.string() ?: "{}" }
    }
    private suspend fun post(path: String, json: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val b = json.toString().toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url(serverBaseUrl + path).post(b).build()).execute().use { r -> if (!r.isSuccessful) throw Exception("HTTP ${r.code}"); JSONObject(r.body?.string() ?: "{}") }
    }
    private suspend fun downloadAtomic(url: String, file: File) = withContext(Dispatchers.IO) {
        val tmp = File(file.parentFile, file.name + ".part")
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("download ${r.code}")
            tmp.outputStream().use { out -> r.body!!.byteStream().use { input -> input.copyTo(out) } }
        }
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) throw Exception("cache rename failed")
    }
    private fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun normalizeServer(raw: String): String {
        val s = raw.trim()
        return if (s.endsWith("/")) s else "$s/"
    }

    override fun onDestroy() { scope.cancel(); player.release(); super.onDestroy() }
}
