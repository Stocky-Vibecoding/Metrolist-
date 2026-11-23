package com.metrolist.android.custom

import android.content.Context
import android.net.Uri
import android.util.Log
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isLive
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.playback.PlaybackFailureCategory
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.youtube.playback.PlaybackNetworkState
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.metrolist.shared.youtube.playback.StreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class ResolverLogEntry(
    val timestampMs: Long,
    val trackTitle: String,
    val qualityBadge: String,
    val detail: String,
)

data class CustomCacheStats(
    val waveCacheBytes: Long = 0L,
    val waveCacheCount: Int = 0,
    val likedDownloadsBytes: Long = 0L,
    val likedDownloadsCount: Int = 0,
    val maxWaveCacheBytes: Long = CustomMediaCacheManager.MAX_WAVE_CACHE_BYTES,
)

data class TrackBinding(
    val source: String = "",
    val yandexId: String? = null,
    val vkAudioKey: String? = null,
    val youtubeId: String? = null,
    val deezerId: String? = null,
    val hdCoverUrl: String? = null,
    val durationSec: Long? = null,
    val isExplicit: Boolean = false,
    val updatedAtMs: Long = System.currentTimeMillis(),
)

data class PendingScrobble(
    val artist: String,
    val title: String,
    val album: String? = null,
    val timestampSec: Long,
    val durationSec: Long? = null,
)

private data class PreResolvedEntry(
    val resource: PlaybackResource,
    val sourcePref: TrackSourcePreference,
    val expiresAtMs: Long,
)

/** Дисковый кэш привязок `songId / artist::title → источник / ID / HD-обложка`. */
object TrackBindingCache {
    private const val TAG = "TrackBindingCache"
    private const val MAX_ENTRIES = 2_000
    private const val FILE_NAME = "custom_track_bindings_v1.json"

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val byVideoId = ConcurrentHashMap<String, TrackBinding>()
    private val byTrackKey = ConcurrentHashMap<String, TrackBinding>()
    private val diskLock = Any()

    @Volatile
    private var storageFile: File? = null

    @Volatile
    private var diskLoaded: Boolean = false

    @Volatile
    private var saveJob: Job? = null

    fun init(appContext: Context) {
        if (storageFile != null) return
        val file = File(appContext.filesDir, FILE_NAME)
        storageFile = file
        ioScope.launch {
            loadFromDisk(file)
        }
    }

    fun get(
        videoId: String?,
        artist: String = "",
        title: String = "",
    ): TrackBinding? {
        if (!videoId.isNullOrBlank()) {
            byVideoId[videoId]?.let { return it }
        }
        val tKey = CustomConfigStore.buildTrackKey(artist, title)
        if (tKey.isNotBlank()) {
            byTrackKey[tKey]?.let { return it }
        }
        return null
    }

    fun getCoverByVideoId(videoId: String?): String? =
        videoId?.takeIf { it.isNotBlank() }?.let { byVideoId[it]?.hdCoverUrl }

    fun put(
        videoId: String?,
        artist: String,
        title: String,
        update: (TrackBinding) -> TrackBinding,
    ) {
        val current = get(videoId, artist, title) ?: TrackBinding()
        val updated = update(current).copy(updatedAtMs = System.currentTimeMillis())
        if (!videoId.isNullOrBlank()) {
            byVideoId[videoId] = updated
        }
        val tKey = CustomConfigStore.buildTrackKey(artist, title)
        if (tKey.isNotBlank()) {
            byTrackKey[tKey] = updated
        }
        schedulePersist()
    }

    fun clearVkBinding(
        videoId: String?,
        artist: String,
        title: String,
    ) {
        val current = get(videoId, artist, title) ?: return
        if (current.vkAudioKey == null && current.source != "vk") return
        val cleaned =
            current.copy(
                vkAudioKey = null,
                source = if (current.source == "vk") "" else current.source,
                updatedAtMs = System.currentTimeMillis(),
            )
        if (!videoId.isNullOrBlank()) byVideoId[videoId] = cleaned
        val tKey = CustomConfigStore.buildTrackKey(artist, title)
        if (tKey.isNotBlank()) byTrackKey[tKey] = cleaned
        schedulePersist()
    }

    private fun schedulePersist() {
        val file = storageFile ?: return
        saveJob?.cancel()
        saveJob =
            ioScope.launch {
                delay(1_500L)
                if (diskLoaded) {
                    saveToDisk(file)
                }
            }
    }

    private fun loadFromDisk(file: File) {
        synchronized(diskLock) {
            if (file.exists()) {
                runCatching {
                    val root = JSONObject(file.readText(Charsets.UTF_8))
                    root.optJSONObject("byId")?.let { obj ->
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            obj.optJSONObject(k)?.let { parseBinding(it) }?.let { byVideoId.putIfAbsent(k, it) }
                        }
                    }
                    root.optJSONObject("byKey")?.let { obj ->
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            obj.optJSONObject(k)?.let { parseBinding(it) }?.let { byTrackKey.putIfAbsent(k, it) }
                        }
                    }
                }.onFailure { err ->
                    Log.w(TAG, "Failed to load track bindings: ${err.message}")
                }
            }
            diskLoaded = true
        }
    }

    private fun saveToDisk(file: File) {
        synchronized(diskLock) {
            runCatching {
                val root = JSONObject()
                val idObj = JSONObject()
                byVideoId.entries
                    .sortedByDescending { it.value.updatedAtMs }
                    .take(MAX_ENTRIES)
                    .forEach { (k, v) -> idObj.put(k, serializeBinding(v)) }
                val keyObj = JSONObject()
                byTrackKey.entries
                    .sortedByDescending { it.value.updatedAtMs }
                    .take(MAX_ENTRIES)
                    .forEach { (k, v) -> keyObj.put(k, serializeBinding(v)) }
                root.put("byId", idObj)
                root.put("byKey", keyObj)
                val tmp = File(file.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(root.toString(), Charsets.UTF_8)
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            }.onFailure { err ->
                Log.w(TAG, "Failed to persist track bindings: ${err.message}")
            }
        }
    }

    private fun serializeBinding(b: TrackBinding): JSONObject =
        JSONObject().apply {
            if (b.source.isNotBlank()) put("s", b.source)
            if (!b.yandexId.isNullOrBlank()) put("ym", b.yandexId)
            if (!b.vkAudioKey.isNullOrBlank()) put("vk", b.vkAudioKey)
            if (!b.youtubeId.isNullOrBlank()) put("yt", b.youtubeId)
            if (!b.deezerId.isNullOrBlank()) put("dz", b.deezerId)
            if (!b.hdCoverUrl.isNullOrBlank()) put("cov", b.hdCoverUrl)
            if (b.durationSec != null && b.durationSec > 0L) put("dur", b.durationSec)
            if (b.isExplicit) put("exp", true)
            put("ts", b.updatedAtMs)
        }

    private fun parseBinding(o: JSONObject): TrackBinding =
        TrackBinding(
            source = o.optString("s"),
            yandexId = o.optString("ym").takeIf { it.isNotBlank() },
            vkAudioKey = o.optString("vk").takeIf { it.isNotBlank() },
            youtubeId = o.optString("yt").takeIf { it.isNotBlank() },
            deezerId = o.optString("dz").takeIf { it.isNotBlank() },
            hdCoverUrl = o.optString("cov").takeIf { it.isNotBlank() },
            durationSec = o.optLong("dur", -1L).takeIf { it > 0L },
            isExplicit = o.optBoolean("exp", false),
            updatedAtMs = o.optLong("ts", 0L),
        )
}

/** Локальный офлайн-буфер скробблов Last.fm (отправляются при возврате сети). */
object LastFmOfflineBuffer {
    private const val TAG = "LastFmOfflineBuffer"
    private const val FILE_NAME = "custom_lastfm_offline_queue.json"
    private const val MAX_ITEMS = 250

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistChannel = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private val queue = ArrayList<PendingScrobble>()

    @Volatile
    private var storageFile: File? = null

    fun init(appContext: Context) {
        if (storageFile != null) return
        val file = File(appContext.filesDir, FILE_NAME)
        storageFile = file
        ioScope.launch {
            loadFromDisk(file)
            for (signal in persistChannel) {
                writeSnapshotToDisk(file)
            }
        }
    }

    fun pendingCount(): Int = synchronized(lock) { queue.size }

    fun enqueue(scrobble: PendingScrobble) {
        if (scrobble.artist.isBlank() || scrobble.title.isBlank()) return
        synchronized(lock) {
            val duplicate =
                queue.any {
                    it.artist.equals(scrobble.artist, ignoreCase = true) &&
                        it.title.equals(scrobble.title, ignoreCase = true) &&
                        abs(it.timestampSec - scrobble.timestampSec) < 30L
                }
            if (duplicate) return
            queue.add(scrobble)
            while (queue.size > MAX_ITEMS) {
                queue.removeAt(0)
            }
        }
        persistChannel.trySend(Unit)
    }

    suspend fun flushPending(sender: suspend (PendingScrobble) -> Boolean): Int {
        val snapshot = synchronized(lock) { queue.toList() }
        if (snapshot.isEmpty()) return 0
        val sent = mutableListOf<PendingScrobble>()
        for (item in snapshot) {
            val ok =
                try {
                    sender(item)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
            if (ok) {
                sent.add(item)
            } else {
                break
            }
        }
        if (sent.isNotEmpty()) {
            synchronized(lock) {
                queue.removeAll(sent.toSet())
            }
            persistChannel.trySend(Unit)
        }
        return sent.size
    }

    private fun writeSnapshotToDisk(file: File) {
        val snapshot = synchronized(lock) { queue.toList() }
        runCatching {
            val arr = JSONArray()
            snapshot.forEach { s ->
                arr.put(
                    JSONObject()
                        .put("a", s.artist)
                        .put("t", s.title)
                        .apply {
                            if (!s.album.isNullOrBlank()) put("alb", s.album)
                            if (s.durationSec != null && s.durationSec > 0L) put("d", s.durationSec)
                        }.put("ts", s.timestampSec),
                )
            }
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }.onFailure { err ->
            Log.w(TAG, "Failed to save offline scrobble queue: ${err.message}")
        }
    }

    private fun loadFromDisk(file: File) {
        if (!file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            synchronized(lock) {
                for (i in 0 until minOf(arr.length(), MAX_ITEMS)) {
                    val o = arr.optJSONObject(i) ?: continue
                    val artist = o.optString("a").trim()
                    val title = o.optString("t").trim()
                    val ts = o.optLong("ts", 0L)
                    if (artist.isNotBlank() && title.isNotBlank() && ts > 0L) {
                        val duplicate =
                            queue.any {
                                it.artist.equals(artist, ignoreCase = true) &&
                                    it.title.equals(title, ignoreCase = true) &&
                                    abs(it.timestampSec - ts) < 30L
                            }
                        if (!duplicate) {
                            queue.add(
                                PendingScrobble(
                                    artist = artist,
                                    title = title,
                                    album = o.optString("alb").takeIf { it.isNotBlank() },
                                    timestampSec = ts,
                                    durationSec = o.optLong("d", -1L).takeIf { it > 0L },
                                ),
                            )
                        }
                    }
                }
            }
        }.onFailure { err ->
            Log.w(TAG, "Failed to load offline scrobble queue: ${err.message}")
        }
    }
}

object CustomMediaCacheManager {
    /** Строгий FIFO-лимит 4 ГБ строго внутри приватной памяти приложения. */
    const val MAX_WAVE_CACHE_BYTES = 4L * 1024L * 1024L * 1024L
    private const val TAG = "CustomMediaCache"
    private const val UNSTABLE_WINDOW_MS = 10 * 60 * 1_000L
    private const val NORMAL_PRELOAD_COUNT = 3
    private const val UNSTABLE_PRELOAD_COUNT = 6

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeDownloads = ConcurrentHashMap.newKeySet<String>()
    private val protectedSafeKeys = ConcurrentHashMap.newKeySet<String>()
    private val networkErrorTimestamps = ArrayDeque<Long>()
    private val networkLock = Any()

    @Volatile
    private var activePlayingSafeKey: String? = null

    @Volatile
    private var preloadQueueJob: Job? = null

    @Volatile
    var systemDownloadLikeHook: ((SongItem) -> Unit)? = null

    @Volatile
    var systemRemoveLikeHook: ((String) -> Unit)? = null

    @Volatile
    private var waveCacheDir: File? = null

    @Volatile
    private var likedDownloadsDir: File? = null

    private val _stats = MutableStateFlow(CustomCacheStats())
    val stats: StateFlow<CustomCacheStats> = _stats.asStateFlow()

    fun init(appContext: Context) {
        TrackBindingCache.init(appContext)
        LastFmOfflineBuffer.init(appContext)
        if (waveCacheDir != null && likedDownloadsDir != null) return
        val wDir = File(appContext.cacheDir, "custom_wave_cache").apply { mkdirs() }
        val lDir = File(appContext.filesDir, "custom_liked_downloads").apply { mkdirs() }
        waveCacheDir = wDir
        likedDownloadsDir = lDir
        refreshStatsAsync()
    }

    fun recordNetworkFailure() {
        val now = System.currentTimeMillis()
        synchronized(networkLock) {
            networkErrorTimestamps.addLast(now)
            pruneNetworkErrorsLocked(now)
        }
    }

    fun recordNetworkSuccess() {
        val now = System.currentTimeMillis()
        synchronized(networkLock) {
            pruneNetworkErrorsLocked(now)
            if (networkErrorTimestamps.isNotEmpty()) {
                networkErrorTimestamps.removeFirst()
            }
        }
    }

    fun isNetworkUnstable(): Boolean {
        val now = System.currentTimeMillis()
        synchronized(networkLock) {
            pruneNetworkErrorsLocked(now)
            return networkErrorTimestamps.size >= 2
        }
    }

    fun recommendedPreloadCount(): Int =
        if (isNetworkUnstable()) UNSTABLE_PRELOAD_COUNT else NORMAL_PRELOAD_COUNT

    private fun pruneNetworkErrorsLocked(nowMs: Long) {
        val cutoff = nowMs - UNSTABLE_WINDOW_MS
        while (networkErrorTimestamps.isNotEmpty() && networkErrorTimestamps.first() < cutoff) {
            networkErrorTimestamps.removeFirst()
        }
    }

    fun setProtectedTrackIds(videoIds: Collection<String>) {
        val next = videoIds.mapNotNull { id -> id.takeIf { it.isNotBlank() }?.let(::safeKey) }.toSet()
        protectedSafeKeys.clear()
        protectedSafeKeys.addAll(next)
        activePlayingSafeKey?.let { protectedSafeKeys.add(it) }
    }

    fun hasLocalFile(videoId: String): Boolean {
        if (videoId.isBlank()) return false
        val safe = safeKey(videoId)
        val liked = likedDownloadsDir?.let { File(it, "$safe.audio") }
        if (liked != null && liked.exists() && liked.length() > 16_384L) return true
        val cached = waveCacheDir?.let { File(it, "$safe.audio") }
        return cached != null && cached.exists() && cached.length() > 16_384L
    }

    fun getLocalPlaybackResource(
        videoId: String,
        song: SongItem,
    ): Pair<PlaybackResource, String>? {
        if (videoId.isBlank()) return null
        val safe = safeKey(videoId)
        activePlayingSafeKey = safe
        protectedSafeKeys.add(safe)

        likedDownloadsDir?.let { dir ->
            readEntry(dir, safe, videoId, song)?.let { res ->
                return res to "скачано"
            }
        }
        waveCacheDir?.let { dir ->
            readEntry(dir, safe, videoId, song)?.let { res ->
                return res to "кэш 4 ГБ"
            }
        }
        return null
    }

    fun cacheWaveTrack(
        song: SongItem,
        resource: PlaybackResource?,
        vibeTag: String? = null,
    ) {
        val dir = waveCacheDir ?: return
        if (song.id.isBlank() || song.isLocal || song.isLive || song.isPodcast) return
        val safe = safeKey(song.id)
        activePlayingSafeKey = safe
        protectedSafeKeys.add(safe)
        if (File(dir, "$safe.audio").exists()) return
        if (likedDownloadsDir?.let { File(it, "$safe.audio").exists() } == true) return
        if (!activeDownloads.add("wave::$safe")) return

        ioScope.launch {
            try {
                val targetResource =
                    resource?.takeIf { isDirectlyCacheable(it) }
                        ?: HybridStreamResolver.resolveDirectForCache(song)?.takeIf { isDirectlyCacheable(it) }
                        ?: return@launch
                if (downloadToDir(dir, safe, song, targetResource, vibeTag = vibeTag)) {
                    trimDirToLimit(dir, MAX_WAVE_CACHE_BYTES)
                    refreshStats()
                }
            } finally {
                activeDownloads.remove("wave::$safe")
            }
        }
    }

    /**
     * Фоновая предзагрузка текущего + 2–3 следующих треков (или 5–6 при нестабильной сети)
     * для любой очереди, чтобы музыка не обрывалась при пропадании интернета.
     */
    fun preloadUpcomingTracks(
        songs: List<SongItem>,
        vibeTag: String? = null,
    ) {
        if (!HybridStreamResolver.isCachingEnabled()) return
        val dir = waveCacheDir ?: return
        val limit = recommendedPreloadCount()
        val targets =
            songs
                .asSequence()
                .filter { it.id.isNotBlank() && !it.isLocal && !it.isLive && !it.isPodcast }
                .distinctBy { it.id }
                .take(limit)
                .toList()
        if (targets.isEmpty()) return

        setProtectedTrackIds(targets.map { it.id })

        preloadQueueJob?.cancel()
        preloadQueueJob =
            ioScope.launch {
                for (song in targets) {
                    currentCoroutineContext().ensureActive()
                    val safe = safeKey(song.id)
                    if (hasLocalFile(song.id)) continue
                    if (!activeDownloads.add("wave::$safe")) continue
                    try {
                        val resource =
                            HybridStreamResolver
                                .resolveDirectForCache(song)
                                ?.takeIf { isDirectlyCacheable(it) }
                        if (resource != null && downloadToDir(dir, safe, song, resource, vibeTag = vibeTag)) {
                            trimDirToLimit(dir, MAX_WAVE_CACHE_BYTES)
                            refreshStats()
                        }
                    } finally {
                        activeDownloads.remove("wave::$safe")
                    }
                    delay(250L)
                }
            }
    }

    /**
     * Защищённый кэш лайков строго по факту прослушивания/лайка —
     * хранится отдельно и никогда не перезаписывается 4 ГБ FIFO.
     */
    fun downloadLikedTrack(
        song: SongItem,
        resource: PlaybackResource? = null,
    ) {
        if (song.id.isBlank() || song.isLocal || song.isLive || song.isPodcast) return
        cacheLikedTrackDirectly(song, resource)
        systemDownloadLikeHook?.invoke(song)
    }

    fun cacheLikedTrackDirectly(
        song: SongItem,
        resource: PlaybackResource? = null,
        vibeTag: String? = null,
    ) {
        val dir = likedDownloadsDir ?: return
        if (song.id.isBlank() || song.isLocal || song.isLive || song.isPodcast) return
        val safe = safeKey(song.id)
        val dstAudio = File(dir, "$safe.audio")
        if (dstAudio.exists() && dstAudio.length() > 16_384L) return
        if (!activeDownloads.add("like::$safe")) return

        ioScope.launch {
            try {
                val waveDir = waveCacheDir
                if (waveDir != null) {
                    val cachedAudio = File(waveDir, "$safe.audio")
                    val cachedMeta = File(waveDir, "$safe.json")
                    if (cachedAudio.exists() && cachedAudio.length() > 16_384L) {
                        runCatching {
                            val tmpDst = File(dir, "$safe.tmp")
                            cachedAudio.copyTo(tmpDst, overwrite = true)
                            if (!tmpDst.renameTo(dstAudio)) {
                                tmpDst.copyTo(dstAudio, overwrite = true)
                                tmpDst.delete()
                            }
                            if (cachedMeta.exists()) cachedMeta.copyTo(File(dir, "$safe.json"), overwrite = true)
                        }.onSuccess {
                            refreshStats()
                            return@launch
                        }
                    }
                }

                val targetResource =
                    resource?.takeIf { isDirectlyCacheable(it) }
                        ?: HybridStreamResolver.resolveDirectForCache(song)?.takeIf { isDirectlyCacheable(it) }
                        ?: return@launch
                if (downloadToDir(dir, safe, song, targetResource, vibeTag = vibeTag)) {
                    refreshStats()
                }
            } finally {
                activeDownloads.remove("like::$safe")
            }
        }
    }

    /**
     * Подбирает локально закэшированные треки для умной офлайн-волны с учётом тегов вайба и артистов.
     */
    fun getOfflineCachedSongs(
        preferredVibe: String? = null,
        preferredArtists: Set<String> = emptySet(),
        excludeIds: Set<String> = emptySet(),
        limit: Int = 20,
    ): List<SongItem> {
        val dirs = listOfNotNull(likedDownloadsDir, waveCacheDir)
        if (dirs.isEmpty()) return emptyList()

        val normVibe = preferredVibe?.trim()?.lowercase().orEmpty()
        val normArtists = preferredArtists.mapTo(HashSet()) { CustomConfigStore.buildArtistKey(it) }
        val seenIds = HashSet<String>()
        val scored = mutableListOf<Pair<SongItem, Int>>()

        for ((dirIdx, dir) in dirs.withIndex()) {
            val audioFiles = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".audio") && it.length() > 16_384L }.orEmpty()
            for (audio in audioFiles) {
                val safe = audio.name.removeSuffix(".audio")
                val metaFile = File(dir, "$safe.json")
                val meta =
                    runCatching {
                        if (metaFile.exists()) JSONObject(metaFile.readText(Charsets.UTF_8)) else null
                    }.getOrNull() ?: continue

                val videoId = meta.optString("videoId").trim().ifBlank { safe }
                if (videoId.isBlank() || videoId in excludeIds || !seenIds.add(videoId)) continue
                val title = meta.optString("title").trim()
                if (title.isBlank()) continue
                val artistStr = meta.optString("artist").trim()
                val durationSec = meta.optLong("durationSec", -1L).takeIf { it > 0L }
                val coverUrl =
                    meta.optString("coverUrl").trim().takeIf { it.isNotBlank() }
                        ?: TrackBindingCache.getCoverByVideoId(videoId)
                        ?: CustomMascotAssets.fallbackCoverUri
                val vibe = meta.optString("vibeTag").trim().lowercase()
                val explicit = meta.optBoolean("isExplicit", false)

                val parsedArtists =
                    CustomConfigStore
                        .splitArtistNames(artistStr)
                        .map { ArtistTiny(id = null, name = it) }
                        .ifEmpty {
                            artistStr
                                .takeIf { it.isNotBlank() }
                                ?.let { listOf(ArtistTiny(id = null, name = it)) }
                                ?: listOf(ArtistTiny(id = null, name = "Неизвестный"))
                        }

                val song =
                    SongItem(
                        id = videoId,
                        title = title,
                        thumbnail = coverUrl,
                        artists = parsedArtists,
                        album = null,
                        duration = durationSec,
                        isExplicit = explicit,
                    )

                var score = if (dirIdx == 0) 10 else 0
                if (normVibe.isNotBlank() && vibe.isNotBlank() && (vibe == normVibe || vibe.contains(normVibe))) {
                    score += 35
                }
                val primaryArtist = CustomConfigStore.buildArtistKey(artistStr)
                if (primaryArtist.isNotBlank() && primaryArtist in normArtists) {
                    score += 25
                }
                score += (0..12).random()
                scored.add(song to score)
            }
        }

        return scored
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    fun removeLikedDownload(videoId: String) {
        if (videoId.isBlank()) return
        systemRemoveLikeHook?.invoke(videoId)
        val dir = likedDownloadsDir ?: return
        val safe = safeKey(videoId)
        ioScope.launch {
            File(dir, "$safe.audio").delete()
            File(dir, "$safe.json").delete()
            refreshStats()
        }
    }

    fun invalidateTrack(videoId: String) {
        if (videoId.isBlank()) return
        val safe = safeKey(videoId)
        waveCacheDir?.let { dir ->
            File(dir, "$safe.audio").delete()
            File(dir, "$safe.json").delete()
        }
        likedDownloadsDir?.let { dir ->
            File(dir, "$safe.audio").delete()
            File(dir, "$safe.json").delete()
        }
        refreshStatsAsync()
    }

    fun clearWaveCache() {
        val dir = waveCacheDir ?: return
        ioScope.launch {
            dir.listFiles()?.forEach { it.delete() }
            refreshStats()
        }
    }

    fun clearLikedDownloads() {
        val dir = likedDownloadsDir ?: return
        ioScope.launch {
            dir.listFiles()?.forEach { it.delete() }
            refreshStats()
        }
    }

    fun formatSize(bytes: Long): String {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format("%.2f ГБ", mb / 1024.0)
        } else {
            String.format("%.1f МБ", mb)
        }
    }

    private fun isDirectlyCacheable(resource: PlaybackResource): Boolean {
        val u = resource.url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false
        if (resource.isSabr) return false
        if (u.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) return false
        if (resource.mimeType?.contains("mpegURL", ignoreCase = true) == true) return false
        return true
    }

    private fun readEntry(
        dir: File,
        safe: String,
        videoId: String,
        song: SongItem,
    ): PlaybackResource? {
        val audioFile = File(dir, "$safe.audio")
        if (!audioFile.exists() || audioFile.length() <= 16_384L) return null
        // НЕ обновляем lastModified при чтении — сохраняем строгий FIFO

        val metaObj =
            runCatching {
                val mf = File(dir, "$safe.json")
                if (mf.exists()) JSONObject(mf.readText(Charsets.UTF_8)) else null
            }.getOrNull()

        val clientName = metaObj?.optString("clientName")?.takeIf { it.isNotBlank() } ?: "yandex"
        val mimeType = metaObj?.optString("mimeType")?.takeIf { it.isNotBlank() } ?: "audio/mpeg"
        val bitrate = metaObj?.optInt("bitrate", -1)?.takeIf { it > 0 }
        val durationSec = metaObj?.optLong("durationSec", -1L)?.takeIf { it > 0L } ?: song.duration

        return PlaybackResource(
            videoId = videoId,
            url = Uri.fromFile(audioFile).toString(),
            headers = emptyMap(),
            loudnessDb = null,
            expiresAt = null,
            mimeType = mimeType,
            bitrate = bitrate,
            durationSeconds = durationSec,
            clientName = clientName,
            profileId = "custom_cache_$clientName",
            requireBoundedRange = false,
            useRangeChunks = false,
        )
    }

    private suspend fun downloadToDir(
        dir: File,
        safe: String,
        song: SongItem,
        resource: PlaybackResource,
        vibeTag: String? = null,
    ): Boolean {
        val tmpFile = File(dir, "$safe.tmp")
        val finalFile = File(dir, "$safe.audio")
        val metaTmpFile = File(dir, "$safe.json.tmp")
        val metaFile = File(dir, "$safe.json")
        return try {
            val conn = (URL(resource.url).openConnection() as HttpURLConnection)
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 12_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                resource.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (conn.responseCode !in 200..299) {
                    recordNetworkFailure()
                    return false
                }
                val contentType = conn.contentType?.lowercase().orEmpty()
                if (contentType.startsWith("text/") || contentType.contains("json") || contentType.contains("xml")) {
                    Log.w(TAG, "Refusing to cache non-audio response ($contentType) for ${song.title}")
                    return false
                }
                val ctx = currentCoroutineContext()
                conn.inputStream.use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buf = ByteArray(16_384)
                        var read = input.read(buf)
                        while (read >= 0) {
                            ctx.ensureActive()
                            output.write(buf, 0, read)
                            read = input.read(buf)
                        }
                        output.flush()
                    }
                }
            } finally {
                conn.disconnect()
            }

            if (!tmpFile.exists() || tmpFile.length() <= 16_384L) {
                tmpFile.delete()
                return false
            }

            if (finalFile.exists()) finalFile.delete()
            if (!tmpFile.renameTo(finalFile)) {
                tmpFile.copyTo(finalFile, overwrite = true)
                tmpFile.delete()
            }
            val artistStr = (song.canonicalArtists ?: song.artists).joinToString(", ") { it.name }.trim()
            val coverUrl =
                ResolverDiagnostics.hdCoverFor(song.id)
                    ?: song.thumbnail?.takeUnless { it == CustomMascotAssets.fallbackCoverUri }
            val nowMs = System.currentTimeMillis()
            val meta =
                JSONObject()
                    .put("videoId", song.id)
                    .put("clientName", resource.clientName)
                    .put("mimeType", resource.mimeType ?: "audio/mpeg")
                    .put("bitrate", resource.bitrate ?: -1)
                    .put("durationSec", resource.durationSeconds ?: song.duration ?: -1L)
                    .put("title", song.title)
                    .put("artist", artistStr)
                    .put("isExplicit", song.isExplicit || song.explicitness == true)
                    .put("cachedAtMs", nowMs)
                    .apply {
                        if (!coverUrl.isNullOrBlank()) put("coverUrl", coverUrl)
                        if (!vibeTag.isNullOrBlank()) put("vibeTag", vibeTag)
                    }
            metaTmpFile.writeText(meta.toString(), Charsets.UTF_8)
            if (!metaTmpFile.renameTo(metaFile)) {
                metaTmpFile.copyTo(metaFile, overwrite = true)
                metaTmpFile.delete()
            }
            recordNetworkSuccess()
            Log.d(TAG, "Saved audio to ${dir.name}: ${song.title} (${finalFile.length() / 1024} KB)")
            true
        } catch (cancelled: CancellationException) {
            tmpFile.delete()
            metaTmpFile.delete()
            throw cancelled
        } catch (err: Exception) {
            tmpFile.delete()
            metaTmpFile.delete()
            recordNetworkFailure()
            Log.w(TAG, "Cache download failed for ${song.title}: ${err.message}")
            false
        }
    }

    /**
     * Строгий FIFO: при превышении 4 ГБ удаляет строго самые первые (старые по времени записи) файлы,
     * защищая текущий и предзагруженные наперёд треки.
     */
    private fun trimDirToLimit(
        dir: File,
        maxBytes: Long,
    ) {
        val rawFiles = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".audio") } ?: return
        var total = rawFiles.sumOf { it.length() }
        if (total <= maxBytes) return

        val sortedOldestFirst =
            rawFiles
                .map { file ->
                    val safe = file.name.removeSuffix(".audio")
                    val metaFile = File(dir, "$safe.json")
                    val cachedAt =
                        runCatching {
                            if (metaFile.exists()) JSONObject(metaFile.readText(Charsets.UTF_8)).optLong("cachedAtMs", 0L) else 0L
                        }.getOrDefault(0L)
                    val effectiveTime = if (cachedAt > 0L) cachedAt else file.lastModified()
                    Triple(file, safe, effectiveTime)
                }.sortedBy { it.third }

        // Первый проход: удаляем только незащищённые старые файлы
        for ((file, safe, _) in sortedOldestFirst) {
            if (total <= maxBytes) break
            if (safe in protectedSafeKeys || safe == activePlayingSafeKey) continue
            val size = file.length()
            file.delete()
            File(dir, "$safe.json").delete()
            total -= size
        }

        // Страховочный проход: если всё равно выше лимита — чистим старые, кроме текущего играющего
        if (total > maxBytes) {
            for ((file, safe, _) in sortedOldestFirst) {
                if (total <= maxBytes) break
                if (safe == activePlayingSafeKey || !file.exists()) continue
                val size = file.length()
                file.delete()
                File(dir, "$safe.json").delete()
                total -= size
            }
        }
    }

    private fun refreshStatsAsync() {
        ioScope.launch { refreshStats() }
    }

    private fun refreshStats() {
        val waveFiles = waveCacheDir?.listFiles()?.filter { it.isFile && it.name.endsWith(".audio") }.orEmpty()
        val likedFiles = likedDownloadsDir?.listFiles()?.filter { it.isFile && it.name.endsWith(".audio") }.orEmpty()
        _stats.value =
            CustomCacheStats(
                waveCacheBytes = waveFiles.sumOf { it.length() },
                waveCacheCount = waveFiles.size,
                likedDownloadsBytes = likedFiles.sumOf { it.length() },
                likedDownloadsCount = likedFiles.size,
                maxWaveCacheBytes = MAX_WAVE_CACHE_BYTES,
            )
    }

    private fun safeKey(videoId: String): String =
        videoId.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(96)
}

object LocalCensorshipAdvisor {
    private val learnedArtists = ConcurrentHashMap.newKeySet<String>()

    private val knownRuCensoredArtists =
        setOf(
            "паша техник",
            "pasha tehnik",
            "metox",
            "метокс",
            "kunteynir",
            "контейнер",
            "oxxxymiron",
            "оксимирон",
            "morgenshtern",
            "моргенштерн",
            "скриптонит",
            "kizaru",
            "кизару",
            "big baby tape",
            "friendly thug",
            "friendly thug 52 ngg",
            "alblak",
            "alblak 52",
            "платина",
            "og buda",
            "163onmyneck",
            "bushido zho",
            "mayot",
            "soda luv",
            "yanix",
            "pharaoh",
            "boulevard depo",
            "gspd",
            "cmh",
            "lida",
            "инстасамка",
            "instasamka",
            "каспийский груз",
            "гуф",
            "guf",
            "ноггано",
            "ак 47",
            "рыночные отношения",
            "полумягкие",
            "слава кпсс",
            "замай",
            "pyrokinesis",
            "atl",
            "horus",
            "хаски",
            "кровосток",
            "ленинград",
            "сектор газа",
            "красная плесень",
            "2rbina 2rista",
            "ic3peak",
            "noize mc",
            "порнофильмы",
            "face",
            "gone fludd",
            "thrill pill",
            "lovv66",
            "scally milano",
            "uglystephan",
            "heronwater",
            "saluki",
            "obladaet",
            "масло черного тмина",
            "блэк бакарди",
            "пошлая молли",
            "кишлак",
            "три дня дождя",
            "дк",
            "dk",
            "aarne",
            "villian",
            "madk1d",
            "blago white",
            "mag luzi",
            "цинк уродов",
            "a v g",
            "avg",
        )

    fun hasCyrillic(text: String): Boolean =
        text.any { ch -> ch in 'а'..'я' || ch in 'А'..'Я' || ch == 'ё' || ch == 'Ё' }

    private fun matchesArtistSet(
        rawArtist: String,
        set: Set<String>,
    ): Boolean {
        val parts = CustomConfigStore.splitArtistNames(rawArtist)
        val candidates = if (parts.isNotEmpty()) parts else listOf(rawArtist)
        for (part in candidates) {
            val norm = TrackMatcher.normalize(part)
            if (norm.isBlank()) continue
            if (norm in set) return true
            // Подстроку проверяем только для длинных имён (>= 6 символов) по границе слова,
            // чтобы "atl" не матчил The Beatles, а "face" — Babyface/Scarface
            if (norm.length >= 6) {
                for (known in set) {
                    if (known.length >= 6 && (norm == known || norm.startsWith("$known ") || norm.endsWith(" $known"))) {
                        return true
                    }
                }
            }
        }
        return false
    }

    fun isRussianTrack(
        artist: String,
        title: String,
    ): Boolean {
        if (hasCyrillic(artist) || hasCyrillic(title)) return true
        if (artist.isBlank()) return false
        return matchesArtistSet(artist, knownRuCensoredArtists) ||
            matchesArtistSet(artist, learnedArtists)
    }

    fun rememberCensoredArtist(artist: String) {
        CustomConfigStore.splitArtistNames(artist).forEach { raw ->
            val norm = TrackMatcher.normalize(raw)
            if (norm.length >= 2) learnedArtists.add(norm)
        }
    }

    fun isLikelyCensoredOnYandex(
        artist: String,
        title: String,
    ): Boolean {
        if (artist.isNotBlank()) {
            if (matchesArtistSet(artist, learnedArtists)) return true
            if (matchesArtistSet(artist, knownRuCensoredArtists)) return true
        }
        val normTitle = TrackMatcher.normalize(title)
        return normTitle.contains("uncensored") || normTitle.contains("без цензуры")
    }
}

object ResolverDiagnostics {
    private val _logs = MutableStateFlow<List<ResolverLogEntry>>(emptyList())
    val logs: StateFlow<List<ResolverLogEntry>> = _logs.asStateFlow()

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val qualityByVideo = ConcurrentHashMap<String, String>()
    private val coverByVideo = ConcurrentHashMap<String, String>()

    fun record(
        videoId: String,
        artist: String,
        title: String,
        qualityBadge: String,
        detail: String,
        hdCoverUrl: String? = null,
    ) {
        qualityByVideo[videoId] = qualityBadge
        if (!hdCoverUrl.isNullOrBlank()) {
            coverByVideo[videoId] = hdCoverUrl
        }
        val label = if (artist.isNotBlank()) "$artist — $title" else title
        val entry =
            ResolverLogEntry(
                timestampMs = System.currentTimeMillis(),
                trackTitle = label,
                qualityBadge = qualityBadge,
                detail = detail,
            )
        _logs.value = (listOf(entry) + _logs.value).take(40)
        _revision.value += 1
    }

    fun hdCoverFor(videoId: String?): String? {
        if (videoId.isNullOrBlank()) return null
        return coverByVideo[videoId] ?: TrackBindingCache.getCoverByVideoId(videoId)
    }

    fun qualityLabelFor(
        videoId: String?,
        clientName: String?,
        bitrate: Int?,
        mimeType: String?,
    ): String? {
        if (!videoId.isNullOrBlank()) {
            qualityByVideo[videoId]?.let { cached ->
                if (clientName == "yandex" && cached.startsWith("Я.Музыка")) return cached
                if (clientName == "vk" && cached.startsWith("VK")) return cached
                if (clientName != "yandex" && clientName != "vk" && cached.startsWith("YTM")) return cached
            }
        }
        return formatQualityBadge(clientName = clientName, bitrate = bitrate, mimeType = mimeType)
    }

    fun formatQualityBadge(
        clientName: String?,
        bitrate: Int?,
        mimeType: String?,
        isHls: Boolean = false,
        variantTag: String? = null,
    ): String? {
        if (clientName.isNullOrBlank()) return null
        val kbps = bitrate?.takeIf { it > 0 }?.let { if (it > 1_000) it / 1_000 else it }
        return when (clientName) {
            "yandex" -> if (kbps != null) "Я.Музыка • $kbps" else "Я.Музыка • 320"
            "vk" -> {
                val base = if (isHls || mimeType?.contains("mpegURL", ignoreCase = true) == true) "VK • HLS" else "VK • MP3"
                if (!variantTag.isNullOrBlank()) "$base $variantTag" else base
            }
            else -> {
                val codec =
                    when {
                        mimeType?.contains("opus", ignoreCase = true) == true -> "OPUS"
                        mimeType?.contains("webm", ignoreCase = true) == true -> "OPUS"
                        mimeType?.contains("mp4", ignoreCase = true) == true -> "AAC"
                        else -> null
                    }
                if (codec != null) "YTM • $codec" else "YTM"
            }
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
        _revision.value += 1
    }
}

class HybridStreamResolver(
    private val delegate: StreamResolver,
    private val configStore: CustomConfigStore,
    private val deezerClient: DeezerReferenceClient,
    private val yandexProvider: YandexMusicProvider,
    private val vkProvider: VkMusicProvider,
) : StreamResolver {
    /** `videoId -> (provider -> failedAtMs)` с автоматическим истечением через 5 минут. */
    private val failedProvidersByVideo = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    private val preResolvedCache = ConcurrentHashMap<String, PreResolvedEntry>()
    private val preResolvingIds = ConcurrentHashMap.newKeySet<String>()
    private val vkVariantIndexByVideo = ConcurrentHashMap<String, Int>()
    private val lastResolvedVkAudioKeyByVideo = ConcurrentHashMap<String, String>()
    private val bypassAudioCacheIds = ConcurrentHashMap.newKeySet<String>()
    private val bypassAudioCacheKeys = ConcurrentHashMap.newKeySet<String>()
    private val externalToYouTubeId = ConcurrentHashMap<String, String>()

    @Volatile
    private var lastHints: ContentHints? = null

    @Volatile
    private var lastAudioQuality: AudioQuality? = null

    init {
        activeInstance = this
    }

    private fun activeFailedProviders(videoId: String): Set<String> {
        val map = failedProvidersByVideo[videoId] ?: return emptySet()
        val now = System.currentTimeMillis()
        val expired = map.filterValues { now - it > FAILED_PROVIDER_TTL_MS }.keys
        expired.forEach { map.remove(it) }
        return map.keys
    }

    private fun markProviderFailed(
        videoId: String,
        provider: String,
    ) {
        if (failedProvidersByVideo.size > 300) failedProvidersByVideo.clear()
        failedProvidersByVideo
            .getOrPut(videoId) { ConcurrentHashMap() }[provider] = System.currentTimeMillis()
    }

    override suspend fun resolve(
        videoId: String,
        hints: ContentHints,
        audioQuality: AudioQuality,
        sourceSong: SongItem?,
    ): PlaybackResource {
        lastHints = hints
        lastAudioQuality = audioQuality
        return resolveWithCascades(
            videoId = videoId,
            hints = hints,
            audioQuality = audioQuality,
            sourceSong = sourceSong,
            refresh = false,
            excludedProfiles = emptySet(),
            forSystemDownload = false,
        ) { ytId, ytSong ->
            delegate.resolve(ytId, hints, audioQuality, ytSong)
        }
    }

    override suspend fun refresh(
        videoId: String,
        hints: ContentHints,
        audioQuality: AudioQuality,
        sourceSong: SongItem?,
    ): PlaybackResource {
        lastHints = hints
        lastAudioQuality = audioQuality
        preResolvedCache.remove(videoId)
        return resolveWithCascades(
            videoId = videoId,
            hints = hints,
            audioQuality = audioQuality,
            sourceSong = sourceSong,
            refresh = true,
            excludedProfiles = emptySet(),
            forSystemDownload = false,
        ) { ytId, ytSong ->
            delegate.refresh(ytId, hints, audioQuality, ytSong)
        }
    }

    override suspend fun resolveRemote(
        videoId: String,
        hints: ContentHints,
        audioQuality: AudioQuality,
        sourceSong: SongItem?,
        refresh: Boolean,
        excludedProfiles: Set<String>,
    ): PlaybackResource {
        lastHints = hints
        lastAudioQuality = audioQuality
        if (refresh) preResolvedCache.remove(videoId)
        val isDownload = videoId in activeSystemDownloads
        return resolveWithCascades(
            videoId = videoId,
            hints = hints,
            audioQuality = audioQuality,
            sourceSong = sourceSong,
            refresh = refresh,
            excludedProfiles = excludedProfiles,
            forSystemDownload = isDownload,
        ) { ytId, ytSong ->
            delegate.resolveRemote(ytId, hints, audioQuality, ytSong, refresh, excludedProfiles)
        }
    }

    /**
     * Главный маршрутизатор каскадов:
     * - Зарубежные: Deezer (мета/обложка) → YTM → VK → Яндекс
     * - Русские без цензуры: Яндекс → VK → YTM
     * - Русские Explicit / цензура: VK → Яндекс → YTM
     */
    private suspend fun resolveWithCascades(
        videoId: String,
        hints: ContentHints,
        audioQuality: AudioQuality,
        sourceSong: SongItem?,
        refresh: Boolean,
        excludedProfiles: Set<String>,
        forSystemDownload: Boolean,
        delegateCall: suspend (ytVideoId: String, ytSong: SongItem?) -> PlaybackResource,
    ): PlaybackResource {
        val song = sourceSong
        val artist = song?.let { (it.canonicalArtists ?: it.artists).joinToString(", ") { a -> a.name }.trim() }.orEmpty()
        val title = song?.let { (it.canonicalTitle ?: it.title).trim() }.orEmpty()

        // 1. Локальный кэш 4 ГБ / защищённый кэш лайков (мгновенно и работает в офлайне)
        if (song != null && !forSystemDownload && "custom_cache" !in excludedProfiles) {
            CustomMediaCacheManager.getLocalPlaybackResource(videoId, song)?.let { (localRes, cacheTag) ->
                val badge =
                    ResolverDiagnostics.formatQualityBadge(
                        clientName = localRes.clientName,
                        bitrate = localRes.bitrate,
                        mimeType = localRes.mimeType,
                    ) ?: localRes.clientName
                ResolverDiagnostics.record(
                    videoId = videoId,
                    artist = artist,
                    title = title,
                    qualityBadge = badge,
                    detail = cacheTag,
                )
                return localRes
            }
        }

        val cfg = configStore.current
        val manualPref =
            if (song != null) {
                configStore.getTrackSourceOverride(videoId = videoId, artist = artist, title = title)
            } else {
                TrackSourcePreference.AUTO
            }

        val isForeignAuto =
            song != null &&
                manualPref == TrackSourcePreference.AUTO &&
                cfg.hybridResolverEnabled &&
                !videoId.startsWith("ym_") &&
                !videoId.startsWith("vk_") &&
                !LocalCensorshipAdvisor.isRussianTrack(artist, title) &&
                !configStore.isArtistLearnedCensored(artist)

        // Зарубежный каскад: Deezer (обложка/эталон) → YTM → VK → Яндекс
        if (isForeignAuto && song != null && !song.isLocal && !song.isUploaded && !song.isPodcast && !song.isLive) {
            val deezerRef =
                try {
                    deezerClient.lookup(
                        artist = artist,
                        title = title,
                        fallbackDurationSec = song.duration,
                        fallbackExplicit = song.isExplicit || song.explicitness == true,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }

            if (deezerRef != null && (!deezerRef.coverXlUrl.isNullOrBlank() || !deezerRef.deezerId.isNullOrBlank())) {
                TrackBindingCache.put(videoId, artist, title) { prev ->
                    prev.copy(
                        deezerId = deezerRef.deezerId ?: prev.deezerId,
                        hdCoverUrl = deezerRef.coverXlUrl ?: prev.hdCoverUrl,
                        durationSec = deezerRef.durationSec ?: prev.durationSec,
                        isExplicit = deezerRef.isExplicit || prev.isExplicit,
                    )
                }
            }

            val ytmFailed = "ytm" in activeFailedProviders(videoId)
            var ytmError: Throwable? = null
            if (!ytmFailed) {
                try {
                    val ytmRes =
                        resolveViaDelegate(
                            videoId = videoId,
                            sourceSong = song,
                            hdCoverOverride = deezerRef?.coverXlUrl,
                            reasonOverride = "зарубежный • YTM",
                            call = delegateCall,
                        )
                    CustomMediaCacheManager.recordNetworkSuccess()
                    return ytmRes
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (err: Exception) {
                    ytmError = err
                    Log.w(TAG, "Foreign YTM failed for $artist - $title, falling back to VK -> Yandex: ${err.message}")
                    CustomMediaCacheManager.recordNetworkFailure()
                }
            }

            // Фолбэк для зарубежного трека: VK → Яндекс
            tryResolveCustom(
                videoId = videoId,
                sourceSong = song,
                excludedProfiles = excludedProfiles,
                allowPreResolvedCache = !refresh && !forSystemDownload,
                allowLocalFileCache = false,
                forSystemDownload = forSystemDownload,
                forcedOrder = listOf("vk", "yandex"),
                forcedReason = "зарубежный фолбэк VK/Яндекс",
                prefetchedReference = deezerRef,
            )?.let { return it }

            if (ytmError != null) throw ytmError
        }

        // Русские треки (Яндекс → VK / цензура VK → Яндекс) и ручной выбор источника
        tryResolveCustom(
            videoId = videoId,
            sourceSong = song,
            excludedProfiles = excludedProfiles,
            allowPreResolvedCache = !refresh && !forSystemDownload,
            allowLocalFileCache = false,
            forSystemDownload = forSystemDownload,
        )?.let { return it }

        // Крайний резерв каскада — YTM
        return resolveViaDelegate(
            videoId = videoId,
            sourceSong = song,
            hdCoverOverride = TrackBindingCache.getCoverByVideoId(videoId),
            reasonOverride = null,
            call = delegateCall,
        )
    }

    private suspend fun resolveViaDelegate(
        videoId: String,
        sourceSong: SongItem?,
        hdCoverOverride: String? = null,
        reasonOverride: String? = null,
        call: suspend (ytVideoId: String, ytSong: SongItem?) -> PlaybackResource,
    ): PlaybackResource {
        if (!isExternalCatalogId(videoId)) {
            val fallback = call(videoId, sourceSong)
            recordYtmFallback(videoId, sourceSong, fallback, hdCoverOverride, reasonOverride)
            return fallback
        }

        val ytId =
            resolveYouTubeFallbackId(videoId, sourceSong)
                ?: throw IllegalStateException("Трек не найден в Я.Музыке, VK и YouTube")
        val fallback = call(ytId, sourceSong?.copy(id = ytId))
        val aligned = if (fallback.videoId == videoId) fallback else fallback.copy(videoId = videoId)
        recordYtmFallback(videoId, sourceSong, aligned, hdCoverOverride, reasonOverride)
        return aligned
    }

    private fun isExternalCatalogId(videoId: String): Boolean =
        videoId.startsWith("ym_") || videoId.startsWith("vk_") || videoId.startsWith("dz_")

    private suspend fun resolveYouTubeFallbackId(
        externalId: String,
        song: SongItem?,
    ): String? {
        externalToYouTubeId[externalId]?.let { return it }
        val artist = song?.let { (it.canonicalArtists ?: it.artists).joinToString(", ") { a -> a.name }.trim() }.orEmpty()
        val title = song?.let { (it.canonicalTitle ?: it.title).trim() }.orEmpty()

        TrackBindingCache.get(externalId, artist, title)?.youtubeId?.let { savedYt ->
            externalToYouTubeId[externalId] = savedYt
            return savedYt
        }

        if (song == null) return null
        val query = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (query.isBlank()) return null

        val foundId =
            try {
                val page =
                    GlobalYouTubeRepository.instance.searchFiltered(
                        query = query,
                        filter = SearchFilter.SONG,
                        allowOfflineFallback = false,
                    )
                val candidates =
                    page.items
                        .filterIsInstance<SongItem>()
                        .filter { !it.isUnavailable && it.id.isNotBlank() }
                        .filter { !TrackMatcher.hasForbiddenStopWord(title, it.title) }
                candidates
                    .mapNotNull { cand ->
                        val candArtist = cand.artists.joinToString(", ") { it.name }.trim()
                        val score =
                            TrackMatcher.matchScore(
                                targetArtist = artist,
                                targetTitle = title,
                                targetDurationSec = song.duration,
                                candidateArtist = candArtist,
                                candidateTitle = cand.title,
                                candidateDurationSec = cand.duration,
                            )
                        if (score >= 52) cand.id to score else null
                    }.maxByOrNull { it.second }
                    ?.first
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

        if (!foundId.isNullOrBlank()) {
            if (externalToYouTubeId.size > 400) externalToYouTubeId.clear()
            externalToYouTubeId[externalId] = foundId
            TrackBindingCache.put(externalId, artist, title) { prev -> prev.copy(youtubeId = foundId) }
        }
        return foundId
    }

    private fun invalidateTrackInternal(
        videoId: String,
        artist: String = "",
        title: String = "",
    ) {
        failedProvidersByVideo.remove(videoId)
        preResolvedCache.remove(videoId)
        bypassAudioCacheIds.add(videoId)
        val tKey = CustomConfigStore.buildTrackKey(artist, title)
        if (tKey.isNotBlank()) bypassAudioCacheKeys.add(tKey)
        CustomMediaCacheManager.invalidateTrack(videoId)
    }

    private fun shouldBypassAudioCacheInternal(song: SongItem): Boolean {
        if (song.id in bypassAudioCacheIds) return true
        val artist = (song.canonicalArtists ?: song.artists).joinToString(", ") { it.name }.trim()
        val title = (song.canonicalTitle ?: song.title).trim()
        val tKey = CustomConfigStore.buildTrackKey(artist, title)
        if (tKey.isNotBlank() && tKey in bypassAudioCacheKeys) return true
        return configStore.getTrackSourceOverride(song.id, artist, title) != TrackSourcePreference.AUTO
    }

    private suspend fun preResolveInternal(song: SongItem) {
        val videoId = song.id
        if (videoId.isBlank()) return
        if (CustomMediaCacheManager.hasLocalFile(videoId)) return
        val cfg = configStore.current
        if (!cfg.hybridResolverEnabled) return
        if (song.isLocal || song.isUploaded || song.isPodcast || song.isLive) return

        val now = System.currentTimeMillis()
        preResolvedCache[videoId]?.takeIf { it.expiresAtMs > now }?.let { return }
        if (!preResolvingIds.add(videoId)) return

        try {
            val artist = (song.canonicalArtists ?: song.artists).joinToString(", ") { it.name }.trim()
            val title = (song.canonicalTitle ?: song.title).trim()
            val manualPref = configStore.getTrackSourceOverride(videoId = videoId, artist = artist, title = title)
            if (manualPref == TrackSourcePreference.YTM) return

            // Для зарубежного трека в AUTO — прогреваем Deezer-эталон и YTM ID (если карточка dz_)
            val isForeignAuto =
                manualPref == TrackSourcePreference.AUTO &&
                    !videoId.startsWith("ym_") &&
                    !videoId.startsWith("vk_") &&
                    !LocalCensorshipAdvisor.isRussianTrack(artist, title) &&
                    !configStore.isArtistLearnedCensored(artist)
            if (isForeignAuto) {
                try {
                    deezerClient.lookup(
                        artist = artist,
                        title = title,
                        fallbackDurationSec = song.duration,
                        fallbackExplicit = song.isExplicit || song.explicitness == true,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                if (videoId.startsWith("dz_")) {
                    resolveYouTubeFallbackId(videoId, song)
                }
                return
            }

            if (cfg.yandexToken.isBlank() && cfg.vkToken.isBlank()) return

            val resolved =
                tryResolveCustom(
                    videoId = videoId,
                    sourceSong = song,
                    excludedProfiles = emptySet(),
                    allowPreResolvedCache = false,
                    isPreResolve = true,
                    allowLocalFileCache = false,
                )
            if (resolved != null) {
                if (preResolvedCache.size > 30) {
                    val expiredKeys = preResolvedCache.filterValues { it.expiresAtMs <= now }.keys
                    expiredKeys.forEach { preResolvedCache.remove(it) }
                    if (preResolvedCache.size > 30) preResolvedCache.clear()
                }
                preResolvedCache[videoId] =
                    PreResolvedEntry(
                        resource = resolved,
                        sourcePref = manualPref,
                        expiresAtMs = System.currentTimeMillis() + PRE_RESOLVE_TTL_MS,
                    )
                Log.d(TAG, "Pre-resolved next track: $artist - $title via ${resolved.clientName}")
            }
        } finally {
            preResolvingIds.remove(videoId)
        }
    }

    private suspend fun tryResolveCustom(
        videoId: String,
        sourceSong: SongItem?,
        excludedProfiles: Set<String> = emptySet(),
        allowPreResolvedCache: Boolean = true,
        isPreResolve: Boolean = false,
        allowLocalFileCache: Boolean = true,
        forSystemDownload: Boolean = false,
        forcedOrder: List<String>? = null,
        forcedReason: String? = null,
        prefetchedReference: TrackReference? = null,
    ): PlaybackResource? {
        val song = sourceSong ?: return null
        if (song.isLocal || song.isUploaded || song.isPodcast || song.isLive) return null
        if (song.title.isBlank()) return null

        val artist =
            (song.canonicalArtists ?: song.artists)
                .joinToString(", ") { it.name }
                .trim()
        val title = (song.canonicalTitle ?: song.title).trim()

        if (allowLocalFileCache && !isPreResolve && !forSystemDownload && "custom_cache" !in excludedProfiles) {
            CustomMediaCacheManager.getLocalPlaybackResource(videoId, song)?.let { (localRes, cacheTag) ->
                val badge =
                    ResolverDiagnostics.formatQualityBadge(
                        clientName = localRes.clientName,
                        bitrate = localRes.bitrate,
                        mimeType = localRes.mimeType,
                    ) ?: localRes.clientName
                ResolverDiagnostics.record(
                    videoId = videoId,
                    artist = artist,
                    title = title,
                    qualityBadge = badge,
                    detail = cacheTag,
                )
                return localRes
            }
        }

        val cfg = configStore.current
        if (!cfg.hybridResolverEnabled && !isExternalCatalogId(videoId)) return null
        if (cfg.yandexToken.isBlank() && cfg.vkToken.isBlank()) return null

        val failed = activeFailedProviders(videoId)
        val manualPref = configStore.getTrackSourceOverride(videoId = videoId, artist = artist, title = title)
        if (manualPref == TrackSourcePreference.YTM && forcedOrder == null) {
            if (!isPreResolve && !forSystemDownload) {
                ResolverDiagnostics.record(
                    videoId = videoId,
                    artist = artist,
                    title = title,
                    qualityBadge = "YTM",
                    detail = "ручной выбор",
                )
            }
            return null
        }

        if (allowPreResolvedCache && failed.isEmpty() && excludedProfiles.isEmpty() && manualPref == TrackSourcePreference.AUTO && forcedOrder == null) {
            val cached = preResolvedCache.remove(videoId)
            if (cached != null &&
                cached.expiresAtMs > System.currentTimeMillis() &&
                cached.sourcePref == manualPref
            ) {
                Log.d(TAG, "Instant pre-resolved hit for $artist - $title")
                return cached.resource
            }
        }

        val diskBinding = TrackBindingCache.get(videoId, artist, title)
        val reference =
            prefetchedReference
                ?: deezerClient.lookup(
                    artist = artist,
                    title = title,
                    fallbackDurationSec = song.duration ?: diskBinding?.durationSec,
                    fallbackExplicit = song.isExplicit || song.explicitness == true || diskBinding?.isExplicit == true,
                )

        val isRuTrack = LocalCensorshipAdvisor.isRussianTrack(artist, title)
        val cachedCensored = configStore.getCachedCensorship(artist, title) == true
        val localCensored =
            manualPref == TrackSourcePreference.AUTO &&
                cfg.preferVkForExplicit &&
                cfg.vkToken.isNotBlank() &&
                (
                    cachedCensored ||
                        configStore.isArtistLearnedCensored(artist) ||
                        LocalCensorshipAdvisor.isLikelyCensoredOnYandex(artist, title) ||
                        (reference.isExplicit && isRuTrack)
                )

        val isManualCustom = manualPref == TrackSourcePreference.YANDEX || manualPref == TrackSourcePreference.VK
        val (baseOrder, baseReason) =
            when {
                forcedOrder != null -> forcedOrder to (forcedReason ?: "каскад")
                manualPref == TrackSourcePreference.YANDEX -> listOf("yandex") to "ручной выбор"
                manualPref == TrackSourcePreference.VK -> listOf("vk") to "ручной выбор"
                localCensored -> listOf("vk", "yandex") to "антицензура РФ → VK"
                videoId.startsWith("vk_") -> listOf("vk", "yandex") to "каталог VK"
                videoId.startsWith("ym_") -> listOf("yandex", "vk") to "каталог Яндекса"
                else -> listOf("yandex", "vk") to "приоритет Я.Музыка"
            }

        val vkVariant = vkVariantIndexByVideo[videoId] ?: 0
        val maxAttempts = if (isManualCustom) 2 else 1
        val orderQueue = baseOrder.toMutableList()
        var currentReason = baseReason
        var yandexCensorshipDetected = false
        var deferredYandexTrackId: String? = null

        var idx = 0
        while (idx < orderQueue.size) {
            val provider = orderQueue[idx++]
            if (!isManualCustom && (provider in failed || "custom_$provider" in excludedProfiles)) continue

            var variantTag: String? = null
            var resolved: ResolvedCustomStream? = null

            for (attempt in 0 until maxAttempts) {
                if (attempt > 0) delay(250L)
                when (provider) {
                    "yandex" -> {
                        val directYmId =
                            videoId.takeIf { it.startsWith("ym_") && !it.startsWith("ym_alb_") }?.removePrefix("ym_")
                                ?: diskBinding?.yandexId
                        val allowCensored = isManualCustom || yandexCensorshipDetected || cfg.vkToken.isBlank() || !cfg.preferVkForExplicit
                        if (directYmId != null && !localCensored && !isManualCustom && !yandexCensorshipDetected && !cachedCensored) {
                            resolved =
                                yandexProvider.resolveDirectById(
                                    trackId = directYmId,
                                    fallbackDurationSec = reference.durationSec ?: song.duration,
                                    coverUrl = diskBinding?.hdCoverUrl ?: reference.coverXlUrl,
                                )
                        }
                        if (resolved == null) {
                            val outcome =
                                yandexProvider.resolveDetailed(
                                    reference = reference,
                                    directTrackId = directYmId,
                                    allowSuspectedCensored = allowCensored,
                                )
                            if (outcome.censorshipDetected && !allowCensored && cfg.vkToken.isNotBlank()) {
                                yandexCensorshipDetected = true
                                deferredYandexTrackId = outcome.trackId
                                configStore.putCachedCensorship(artist, title, true)
                                currentReason = outcome.censorshipReason ?: "цензура в Я.Музыке → VK"
                                if ("vk" !in orderQueue.subList(idx, orderQueue.size)) {
                                    orderQueue.add(idx, "vk")
                                }
                                if ("yandex" !in orderQueue.subList(idx, orderQueue.size)) {
                                    orderQueue.add("yandex")
                                }
                                break
                            }
                            resolved = outcome.stream
                        }
                    }

                    "vk" -> {
                        val directVkKey =
                            if (vkVariant == 0) {
                                videoId.takeIf { it.startsWith("vk_") }?.removePrefix("vk_")
                                    ?: diskBinding?.vkAudioKey
                            } else {
                                null
                            }
                        if (!directVkKey.isNullOrBlank() && !configStore.isVkAudioBlacklisted(directVkKey)) {
                            resolved = vkProvider.resolveDirectById(directVkKey)
                        }
                        if (resolved == null) {
                            val pair = vkProvider.resolveWithVariant(reference, variantIndex = vkVariant)
                            variantTag = pair?.second
                            resolved = pair?.first
                        }
                    }
                }
                if (resolved != null) break
            }

            if (resolved == null && provider == "yandex" && deferredYandexTrackId != null && yandexCensorshipDetected) {
                resolved =
                    yandexProvider.resolveDirectById(
                        trackId = deferredYandexTrackId,
                        fallbackDurationSec = reference.durationSec ?: song.duration,
                        coverUrl = reference.coverXlUrl,
                    )
            }

            if (resolved == null) continue

            if (forSystemDownload && (resolved.isHls || resolved.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true))) {
                continue
            }

            if (resolved.clientName == "vk" && !resolved.sourceTrackId.isNullOrBlank()) {
                lastResolvedVkAudioKeyByVideo[videoId] = resolved.sourceTrackId
            }

            val bestCover =
                reference.coverXlUrl
                    ?: resolved.coverUrl?.takeIf { resolved.clientName == "yandex" }
                    ?: diskBinding?.hdCoverUrl
                    ?: resolved.coverUrl

            TrackBindingCache.put(videoId, artist, title) { prev ->
                prev.copy(
                    source = resolved.clientName,
                    yandexId = if (resolved.clientName == "yandex") resolved.sourceTrackId ?: prev.yandexId else prev.yandexId,
                    vkAudioKey = if (resolved.clientName == "vk") resolved.sourceTrackId ?: prev.vkAudioKey else prev.vkAudioKey,
                    deezerId = reference.deezerId ?: prev.deezerId,
                    hdCoverUrl = bestCover ?: prev.hdCoverUrl,
                    durationSec = resolved.durationSec ?: reference.durationSec ?: prev.durationSec,
                    isExplicit = reference.isExplicit || prev.isExplicit,
                )
            }

            CustomMediaCacheManager.recordNetworkSuccess()

            val badge =
                ResolverDiagnostics.formatQualityBadge(
                    clientName = resolved.clientName,
                    bitrate = resolved.bitrate,
                    mimeType = resolved.mimeType,
                    isHls = resolved.isHls,
                    variantTag = variantTag,
                ) ?: resolved.clientName

            val fullDetail =
                when {
                    forSystemDownload -> "$currentReason • скачивание"
                    isPreResolve -> "$currentReason • пре-резолв"
                    else -> currentReason
                }
            ResolverDiagnostics.record(
                videoId = videoId,
                artist = artist,
                title = title,
                qualityBadge = badge,
                detail = fullDetail,
                hdCoverUrl = bestCover,
            )

            return PlaybackResource(
                videoId = videoId,
                url = resolved.url,
                headers = resolved.headers,
                loudnessDb = null,
                expiresAt = null,
                mimeType = resolved.mimeType,
                bitrate = resolved.bitrate,
                durationSeconds = resolved.durationSec ?: song.duration,
                clientName = resolved.clientName,
                profileId = "custom_${resolved.clientName}",
                requireBoundedRange = false,
                useRangeChunks = false,
            )
        }

        return null
    }

    private suspend fun resolveDirectForCacheInternal(song: SongItem): PlaybackResource? {
        val artist = (song.canonicalArtists ?: song.artists).joinToString(", ") { it.name }.trim()
        val title = (song.canonicalTitle ?: song.title).trim()
        val isForeign = !LocalCensorshipAdvisor.isRussianTrack(artist, title) && !configStore.isArtistLearnedCensored(artist)

        if (isForeign) {
            val hints = lastHints
            val quality = lastAudioQuality
            if (hints != null && quality != null) {
                val ytmRes =
                    try {
                        resolveViaDelegate(song.id, song) { ytId, ytSong ->
                            delegate.resolve(ytId, hints, quality, ytSong)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                if (ytmRes != null && !ytmRes.isSabr && ytmRes.url.startsWith("http")) {
                    return ytmRes
                }
            }
            return tryResolveCustom(
                videoId = song.id,
                sourceSong = song,
                excludedProfiles = emptySet(),
                allowPreResolvedCache = false,
                isPreResolve = true,
                allowLocalFileCache = false,
                forSystemDownload = true,
                forcedOrder = listOf("vk", "yandex"),
                forcedReason = "предзагрузка кэша",
            )
        }

        tryResolveCustom(
            videoId = song.id,
            sourceSong = song,
            excludedProfiles = emptySet(),
            allowPreResolvedCache = false,
            isPreResolve = true,
            allowLocalFileCache = false,
            forSystemDownload = true,
        )?.let { return it }

        val hints = lastHints
        val quality = lastAudioQuality
        if (hints != null && quality != null) {
            val ytmRes =
                try {
                    resolveViaDelegate(song.id, song) { ytId, ytSong ->
                        delegate.resolve(ytId, hints, quality, ytSong)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            if (ytmRes != null && !ytmRes.isSabr && ytmRes.url.startsWith("http")) {
                return ytmRes
            }
        }
        return null
    }

    private fun recordYtmFallback(
        videoId: String,
        song: SongItem?,
        resource: PlaybackResource,
        hdCoverOverride: String? = null,
        reasonOverride: String? = null,
    ) {
        if (song == null) return
        val artist = (song.canonicalArtists ?: song.artists).joinToString(", ") { it.name }.trim()
        val title = (song.canonicalTitle ?: song.title).trim()
        val badge =
            ResolverDiagnostics.formatQualityBadge(
                clientName = resource.clientName.ifBlank { "ytm" },
                bitrate = resource.bitrate,
                mimeType = resource.mimeType,
            ) ?: "YTM"
        val manual = configStore.getTrackSourceOverride(videoId, artist, title)
        val detail =
            when {
                manual == TrackSourcePreference.YTM -> "ручной выбор"
                !reasonOverride.isNullOrBlank() -> reasonOverride
                else -> "фолбэк YTM"
            }
        ResolverDiagnostics.record(
            videoId = videoId,
            artist = artist,
            title = title,
            qualityBadge = badge,
            detail = detail,
            hdCoverUrl = hdCoverOverride,
        )
    }

    override suspend fun prewarm() {
        delegate.prewarm()
    }

    override suspend fun clearPlaybackFailures(videoId: String) {
        failedProvidersByVideo.remove(videoId)
        val delegateId = externalToYouTubeId[videoId] ?: videoId
        if (!isExternalCatalogId(delegateId)) {
            delegate.clearPlaybackFailures(delegateId)
        }
    }

    override suspend fun invalidateNetworkScope(state: PlaybackNetworkState) {
        invalidateVkSessionStreamsInternal()
        delegate.invalidateNetworkScope(state)
    }

    private fun invalidateVkSessionStreamsInternal() {
        preResolvedCache.clear()
        failedProvidersByVideo.clear()
        vkProvider.resetFloodCooldown()
    }

    override suspend fun resetExtractionBackoff() {
        failedProvidersByVideo.clear()
        preResolvedCache.clear()
        delegate.resetExtractionBackoff()
    }

    override suspend fun invalidateAttestation() {
        delegate.invalidateAttestation()
    }

    override suspend fun refreshPlayerConfigAfterStreamRejection(): Boolean =
        delegate.refreshPlayerConfigAfterStreamRejection()

    override suspend fun close() {
        preResolvedCache.clear()
        delegate.close()
    }

    override suspend fun reportPlaybackFailure(
        videoId: String,
        clientName: String?,
        responseCode: Int?,
        profileId: String?,
        hints: ContentHints,
        category: PlaybackFailureCategory,
    ) {
        if (profileId?.startsWith("custom_cache_") == true) {
            Log.w(TAG, "Cached custom audio failed for $videoId, deleting corrupt file")
            CustomMediaCacheManager.invalidateTrack(videoId)
            return
        }
        if (category == PlaybackFailureCategory.NETWORK || responseCode == 403 || responseCode == 404) {
            CustomMediaCacheManager.recordNetworkFailure()
        }
        if (clientName == "yandex" || clientName == "vk") {
            Log.w(TAG, "Custom stream failed ($clientName, code=$responseCode) for $videoId, falling back")
            preResolvedCache.remove(videoId)
            markProviderFailed(videoId, clientName)
            return
        }
        markProviderFailed(videoId, "ytm")
        val delegateId = externalToYouTubeId[videoId] ?: videoId
        if (!isExternalCatalogId(delegateId)) {
            delegate.reportPlaybackFailure(delegateId, clientName, responseCode, profileId, hints, category)
        }
    }

    override suspend fun reportPlaybackSuccess(
        profileId: String?,
        hints: ContentHints,
    ) {
        CustomMediaCacheManager.recordNetworkSuccess()
        if (profileId?.startsWith("custom_") == true) return
        delegate.reportPlaybackSuccess(profileId, hints)
    }

    companion object {
        private const val TAG = "HybridResolver"
        private const val PRE_RESOLVE_TTL_MS = 12 * 60 * 1_000L
        private const val FAILED_PROVIDER_TTL_MS = 5 * 60 * 1_000L

        @Volatile
        private var activeInstance: HybridStreamResolver? = null

        private val activeSystemDownloads = ConcurrentHashMap.newKeySet<String>()

        fun getDeezerClient(): DeezerReferenceClient? = activeInstance?.deezerClient

        fun getYandexProvider(): YandexMusicProvider? = activeInstance?.yandexProvider

        fun getVkProvider(): VkMusicProvider? = activeInstance?.vkProvider

        fun invalidateVkSessionStreams() {
            activeInstance?.invalidateVkSessionStreamsInternal()
        }

        fun beginSystemDownload(videoId: String) {
            if (videoId.isNotBlank()) activeSystemDownloads.add(videoId)
        }

        fun endSystemDownload(videoId: String) {
            if (videoId.isNotBlank()) activeSystemDownloads.remove(videoId)
        }

        fun isCachingEnabled(): Boolean =
            activeInstance?.configStore?.current?.cacheWaveAndDownloadLikes != false

        fun getTrackSourceOverrideName(song: SongItem?): String {
            if (song == null) return TrackSourcePreference.AUTO.name
            val inst = activeInstance ?: return TrackSourcePreference.AUTO.name
            val artist = (song.canonicalArtists ?: song.artists).joinToString(", ") { it.name }.trim()
            val title = (song.canonicalTitle ?: song.title).trim()
            return inst.configStore.getTrackSourceOverride(song.id, artist, title).name
        }

        fun selectTrackSourceFromMenu(
            song: SongItem,
            prefName: String,
            activeClientName: String?,
        ): String {
            val pref = TrackSourcePreference.entries.firstOrNull { it.name == prefName } ?: TrackSourcePreference.AUTO
            val inst = activeInstance ?: return "Переключаю на ${pref.label}"
            val artist = (song.canonicalArtists ?: song.artists).joinToString(", ") { it.name }.trim()
            val title = (song.canonicalTitle ?: song.title).trim()
            val currentOverride = inst.configStore.getTrackSourceOverride(song.id, artist, title)
            val alreadyOnVk =
                pref == TrackSourcePreference.VK &&
                    (currentOverride == TrackSourcePreference.VK || activeClientName == "vk")

            if (alreadyOnVk) {
                val badAudioKey =
                    inst.lastResolvedVkAudioKeyByVideo.remove(song.id)
                        ?: TrackBindingCache.get(song.id, artist, title)?.vkAudioKey
                if (!badAudioKey.isNullOrBlank()) {
                    inst.configStore.blacklistVkAudio(badAudioKey)
                }
                TrackBindingCache.clearVkBinding(song.id, artist, title)
                cycleVkVariant(song.id, artist, title)
            } else {
                resetVkVariant(song.id)
                invalidateTrack(song.id, artist, title)
            }

            if (pref == TrackSourcePreference.VK && activeClientName == "yandex") {
                inst.configStore.putCachedCensorship(artist, title, true)
                inst.configStore.recordManualCensorshipSwitch(artist)
            }

            inst.configStore.setTrackSourceOverride(
                videoId = song.id,
                artist = artist,
                title = title,
                preference = pref,
            )
            return if (alreadyOnVk) "Исключил версию и ищу другой файл VK…" else "Переключаю на ${pref.label}"
        }

        fun invalidateTrack(
            videoId: String,
            artist: String = "",
            title: String = "",
        ) {
            activeInstance?.invalidateTrackInternal(videoId, artist, title)
        }

        fun shouldBypassAudioCache(song: SongItem): Boolean =
            activeInstance?.shouldBypassAudioCacheInternal(song) == true

        fun cycleVkVariant(
            videoId: String,
            artist: String = "",
            title: String = "",
        ): Int {
            val inst = activeInstance ?: return 0
            val next = (inst.vkVariantIndexByVideo[videoId] ?: 0) + 1
            inst.vkVariantIndexByVideo[videoId] = next
            inst.invalidateTrackInternal(videoId, artist, title)
            return next
        }

        fun resetVkVariant(videoId: String) {
            activeInstance?.vkVariantIndexByVideo?.remove(videoId)
        }

        suspend fun preResolve(song: SongItem) {
            activeInstance?.preResolveInternal(song)
        }

        suspend fun resolveDirectForCache(song: SongItem): PlaybackResource? {
            val inst = activeInstance ?: return null
            return inst.resolveDirectForCacheInternal(song)
        }
    }
}
