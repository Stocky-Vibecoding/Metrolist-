package com.metrolist.android.custom

import android.content.Context
import android.net.Uri
import android.util.Log
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isLive
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.playback.PlaybackFailureCategory
import com.metrolist.shared.youtube.playback.PlaybackNetworkState
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.metrolist.shared.youtube.playback.StreamResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

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

private data class PreResolvedEntry(
    val resource: PlaybackResource,
    val sourcePref: TrackSourcePreference,
    val expiresAtMs: Long,
)

object CustomMediaCacheManager {
    const val MAX_WAVE_CACHE_BYTES = 2L * 1024L * 1024L * 1024L
    private const val TAG = "CustomMediaCache"

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeDownloads = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var waveCacheDir: File? = null

    @Volatile
    private var likedDownloadsDir: File? = null

    private val _stats = MutableStateFlow(CustomCacheStats())
    val stats: StateFlow<CustomCacheStats> = _stats.asStateFlow()

    fun init(appContext: Context) {
        if (waveCacheDir != null && likedDownloadsDir != null) return
        val wDir = File(appContext.cacheDir, "custom_wave_cache").apply { mkdirs() }
        val lDir = File(appContext.filesDir, "custom_liked_downloads").apply { mkdirs() }
        waveCacheDir = wDir
        likedDownloadsDir = lDir
        refreshStatsAsync()
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

        likedDownloadsDir?.let { dir ->
            readEntry(dir, safe, videoId, song)?.let { res ->
                return res to "скачано"
            }
        }
        waveCacheDir?.let { dir ->
            readEntry(dir, safe, videoId, song)?.let { res ->
                return res to "кэш волны"
            }
        }
        return null
    }

    fun cacheWaveTrack(
        song: SongItem,
        resource: PlaybackResource?,
    ) {
        val dir = waveCacheDir ?: return
        if (song.id.isBlank() || song.isLocal || song.isLive || song.isPodcast) return
        val safe = safeKey(song.id)
        if (File(dir, "$safe.audio").exists()) return
        if (likedDownloadsDir?.let { File(it, "$safe.audio").exists() } == true) return
        if (!activeDownloads.add("wave::$safe")) return

        ioScope.launch {
            try {
                val targetResource =
                    resource?.takeIf { isDirectlyCacheable(it) }
                        ?: HybridStreamResolver.resolveDirectForCache(song)?.takeIf { isDirectlyCacheable(it) }
                        ?: return@launch
                if (downloadToDir(dir, safe, song, targetResource)) {
                    trimDirToLimit(dir, MAX_WAVE_CACHE_BYTES)
                    refreshStats()
                }
            } finally {
                activeDownloads.remove("wave::$safe")
            }
        }
    }

    fun downloadLikedTrack(
        song: SongItem,
        resource: PlaybackResource? = null,
    ) {
        val dir = likedDownloadsDir ?: return
        if (song.id.isBlank() || song.isLocal || song.isLive || song.isPodcast) return
        val safe = safeKey(song.id)
        val dstAudio = File(dir, "$safe.audio")
        if (dstAudio.exists() && dstAudio.length() > 16_384L) return
        if (!activeDownloads.add("like::$safe")) return

        ioScope.launch {
            try {
                // Если трек уже лежит в кэше волны — просто копируем его в скачанное мгновенно
                val waveDir = waveCacheDir
                if (waveDir != null) {
                    val cachedAudio = File(waveDir, "$safe.audio")
                    val cachedMeta = File(waveDir, "$safe.json")
                    if (cachedAudio.exists() && cachedAudio.length() > 16_384L) {
                        runCatching {
                            cachedAudio.copyTo(dstAudio, overwrite = true)
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
                if (downloadToDir(dir, safe, song, targetResource)) {
                    refreshStats()
                }
            } finally {
                activeDownloads.remove("like::$safe")
            }
        }
    }

    fun removeLikedDownload(videoId: String) {
        val dir = likedDownloadsDir ?: return
        if (videoId.isBlank()) return
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
        audioFile.setLastModified(System.currentTimeMillis())

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

    private fun downloadToDir(
        dir: File,
        safe: String,
        song: SongItem,
        resource: PlaybackResource,
    ): Boolean {
        val tmpFile = File(dir, "$safe.tmp")
        val finalFile = File(dir, "$safe.audio")
        val metaFile = File(dir, "$safe.json")
        return runCatching {
            val conn = (URL(resource.url).openConnection() as HttpURLConnection)
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 12_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                resource.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (conn.responseCode !in 200..299) {
                    return@runCatching false
                }
                conn.inputStream.use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buf = ByteArray(16_384)
                        var read = input.read(buf)
                        while (read >= 0) {
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
                return@runCatching false
            }

            if (finalFile.exists()) finalFile.delete()
            if (!tmpFile.renameTo(finalFile)) {
                tmpFile.copyTo(finalFile, overwrite = true)
                tmpFile.delete()
            }
            val meta =
                JSONObject()
                    .put("clientName", resource.clientName)
                    .put("mimeType", resource.mimeType ?: "audio/mpeg")
                    .put("bitrate", resource.bitrate ?: -1)
                    .put("durationSec", resource.durationSeconds ?: song.duration ?: -1L)
                    .put("title", song.title)
            metaFile.writeText(meta.toString(), Charsets.UTF_8)
            Log.d(TAG, "Saved audio to ${dir.name}: ${song.title} (${finalFile.length() / 1024} KB)")
            true
        }.onFailure { err ->
            tmpFile.delete()
            Log.w(TAG, "Cache download failed for ${song.title}: ${err.message}")
        }.getOrDefault(false)
    }

    private fun trimDirToLimit(
        dir: File,
        maxBytes: Long,
    ) {
        val audioFiles =
            dir
                .listFiles()
                ?.filter { it.isFile && it.name.endsWith(".audio") }
                ?.sortedBy { it.lastModified() }
                ?: return
        var total = audioFiles.sumOf { it.length() }
        for (file in audioFiles) {
            if (total <= maxBytes) break
            val size = file.length()
            val meta = File(dir, file.name.removeSuffix(".audio") + ".json")
            file.delete()
            meta.delete()
            total -= size
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
            "alblak",
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
        )

    fun rememberCensoredArtist(artist: String) {
        val parts = artist.split(",", "&", "feat.", "ft.")
        parts.forEach { raw ->
            val norm = TrackMatcher.normalize(raw)
            if (norm.length >= 2) learnedArtists.add(norm)
        }
    }

    fun isLikelyCensoredOnYandex(
        artist: String,
        title: String,
    ): Boolean {
        val normArtist = TrackMatcher.normalize(artist)
        if (normArtist.isBlank()) return false
        if (learnedArtists.any { normArtist.contains(it) }) return true
        if (knownRuCensoredArtists.any { normArtist.contains(it) }) return true
        val normTitle = TrackMatcher.normalize(title)
        return normTitle.contains("explicit") || normTitle.contains("uncensored") || normTitle.contains("без цензуры")
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

    fun hdCoverFor(videoId: String?): String? =
        videoId?.let { coverByVideo[it] }

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
    private val failedProvidersByVideo = ConcurrentHashMap<String, MutableSet<String>>()
    private val preResolvedCache = ConcurrentHashMap<String, PreResolvedEntry>()
    private val preResolvingIds = ConcurrentHashMap.newKeySet<String>()
    private val vkVariantIndexByVideo = ConcurrentHashMap<String, Int>()
    private val bypassAudioCacheIds = ConcurrentHashMap.newKeySet<String>()
    private val bypassAudioCacheKeys = ConcurrentHashMap.newKeySet<String>()

    init {
        activeInstance = this
    }

    override suspend fun resolve(
        videoId: String,
        hints: ContentHints,
        audioQuality: AudioQuality,
        sourceSong: SongItem?,
    ): PlaybackResource {
        tryResolveCustom(videoId, sourceSong)?.let { return it }
        val fallback = delegate.resolve(videoId, hints, audioQuality, sourceSong)
        recordYtmFallback(videoId, sourceSong, fallback)
        return fallback
    }

    override suspend fun refresh(
        videoId: String,
        hints: ContentHints,
        audioQuality: AudioQuality,
        sourceSong: SongItem?,
    ): PlaybackResource {
        preResolvedCache.remove(videoId)
        tryResolveCustom(videoId, sourceSong, allowPreResolvedCache = false)?.let { return it }
        val fallback = delegate.refresh(videoId, hints, audioQuality, sourceSong)
        recordYtmFallback(videoId, sourceSong, fallback)
        return fallback
    }

    override suspend fun resolveRemote(
        videoId: String,
        hints: ContentHints,
        audioQuality: AudioQuality,
        sourceSong: SongItem?,
        refresh: Boolean,
        excludedProfiles: Set<String>,
    ): PlaybackResource {
        if (refresh) preResolvedCache.remove(videoId)
        tryResolveCustom(videoId, sourceSong, excludedProfiles, allowPreResolvedCache = !refresh)?.let { return it }
        val fallback = delegate.resolveRemote(videoId, hints, audioQuality, sourceSong, refresh, excludedProfiles)
        recordYtmFallback(videoId, sourceSong, fallback)
        return fallback
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
        val cfg = configStore.current
        if (!cfg.hybridResolverEnabled) return
        if (cfg.yandexToken.isBlank() && cfg.vkToken.isBlank()) return
        if (song.isLocal || song.isUploaded || song.isPodcast || song.isLive) return

        val now = System.currentTimeMillis()
        preResolvedCache[videoId]?.takeIf { it.expiresAtMs > now }?.let { return }
        if (!preResolvingIds.add(videoId)) return

        try {
            val artist = (song.canonicalArtists ?: song.artists).joinToString(", ") { it.name }.trim()
            val title = (song.canonicalTitle ?: song.title).trim()
            val manualPref = configStore.getTrackSourceOverride(videoId = videoId, artist = artist, title = title)
            if (manualPref == TrackSourcePreference.YTM) return

            val resolved =
                tryResolveCustom(
                    videoId = videoId,
                    sourceSong = song,
                    excludedProfiles = emptySet(),
                    allowPreResolvedCache = false,
                    isPreResolve = true,
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
    ): PlaybackResource? {
        val song = sourceSong ?: return null
        if (song.isLocal || song.isUploaded || song.isPodcast || song.isLive) return null
        if (song.title.isBlank()) return null

        val artist =
            (song.canonicalArtists ?: song.artists)
                .joinToString(", ") { it.name }
                .trim()
        val title = (song.canonicalTitle ?: song.title).trim()

        if (allowLocalFileCache && !isPreResolve && "custom_cache" !in excludedProfiles) {
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
        if (!cfg.hybridResolverEnabled) return null
        if (cfg.yandexToken.isBlank() && cfg.vkToken.isBlank()) return null

        val failed = failedProvidersByVideo[videoId].orEmpty()
        val manualPref = configStore.getTrackSourceOverride(videoId = videoId, artist = artist, title = title)
        if (manualPref == TrackSourcePreference.YTM) {
            if (!isPreResolve) {
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

        if (allowPreResolvedCache && failed.isEmpty() && excludedProfiles.isEmpty() && manualPref == TrackSourcePreference.AUTO) {
            val cached = preResolvedCache.remove(videoId)
            if (cached != null &&
                cached.expiresAtMs > System.currentTimeMillis() &&
                cached.sourcePref == manualPref
            ) {
                Log.d(TAG, "Instant pre-resolved hit for $artist - $title")
                return cached.resource
            }
        }

        val reference =
            deezerClient.lookup(
                artist = artist,
                title = title,
                fallbackDurationSec = song.duration,
                fallbackExplicit = song.isExplicit || song.explicitness == true,
            )

        val localCensored =
            manualPref == TrackSourcePreference.AUTO &&
                cfg.preferVkForExplicit &&
                cfg.vkToken.isNotBlank() &&
                LocalCensorshipAdvisor.isLikelyCensoredOnYandex(artist, title)

        val isManualCustom = manualPref == TrackSourcePreference.YANDEX || manualPref == TrackSourcePreference.VK
        val (order, reason) =
            when {
                manualPref == TrackSourcePreference.YANDEX -> listOf("yandex") to "ручной выбор"
                manualPref == TrackSourcePreference.VK -> listOf("vk") to "ручной выбор"
                videoId.startsWith("vk_") -> listOf("vk", "yandex") to "каталог VK"
                videoId.startsWith("ym_") -> listOf("yandex", "vk") to "каталог Яндекса"
                (reference.isExplicit || localCensored) && cfg.preferVkForExplicit && cfg.vkToken.isNotBlank() -> {
                    val tag = if (localCensored) "антицензура → VK" else "Explicit → VK"
                    listOf("vk", "yandex") to tag
                }
                else -> listOf("yandex", "vk") to "автокаскад"
            }

        val vkVariant = vkVariantIndexByVideo[videoId] ?: 0
        val maxAttempts = if (isManualCustom) 2 else 1

        for (provider in order) {
            if (!isManualCustom && (provider in failed || "custom_$provider" in excludedProfiles)) continue
            var variantTag: String? = null
            var resolved: ResolvedCustomStream? = null

            for (attempt in 0 until maxAttempts) {
                if (attempt > 0) delay(250L)
                resolved =
                    when (provider) {
                        "yandex" -> yandexProvider.resolve(reference)
                        "vk" -> {
                            val pair = vkProvider.resolveWithVariant(reference, variantIndex = vkVariant)
                            variantTag = pair?.second
                            pair?.first
                        }
                        else -> null
                    }
                if (resolved != null) break
            }
            if (resolved == null) continue

            val badge =
                ResolverDiagnostics.formatQualityBadge(
                    clientName = resolved.clientName,
                    bitrate = resolved.bitrate,
                    mimeType = resolved.mimeType,
                    isHls = resolved.isHls,
                    variantTag = variantTag,
                ) ?: resolved.clientName

            val fullDetail = if (isPreResolve) "$reason • пре-резолв" else reason
            ResolverDiagnostics.record(
                videoId = videoId,
                artist = artist,
                title = title,
                qualityBadge = badge,
                detail = fullDetail,
                hdCoverUrl = reference.coverXlUrl,
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

    private fun recordYtmFallback(
        videoId: String,
        song: SongItem?,
        resource: PlaybackResource,
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
        val detail = if (manual == TrackSourcePreference.YTM) "ручной выбор" else "фолбэк YTM"
        ResolverDiagnostics.record(
            videoId = videoId,
            artist = artist,
            title = title,
            qualityBadge = badge,
            detail = detail,
        )
    }

    override suspend fun prewarm() {
        delegate.prewarm()
    }

    override suspend fun clearPlaybackFailures(videoId: String) {
        failedProvidersByVideo.remove(videoId)
        delegate.clearPlaybackFailures(videoId)
    }

    override suspend fun invalidateNetworkScope(state: PlaybackNetworkState) {
        preResolvedCache.clear()
        delegate.invalidateNetworkScope(state)
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
        if (clientName == "yandex" || clientName == "vk") {
            Log.w(TAG, "Custom stream failed ($clientName, code=$responseCode) for $videoId, falling back")
            preResolvedCache.remove(videoId)
            failedProvidersByVideo.getOrPut(videoId) { ConcurrentHashMap.newKeySet() }.add(clientName)
            return
        }
        delegate.reportPlaybackFailure(videoId, clientName, responseCode, profileId, hints, category)
    }

    override suspend fun reportPlaybackSuccess(
        profileId: String?,
        hints: ContentHints,
    ) {
        if (profileId?.startsWith("custom_") == true) return
        delegate.reportPlaybackSuccess(profileId, hints)
    }

    companion object {
        private const val TAG = "HybridResolver"
        private const val PRE_RESOLVE_TTL_MS = 12 * 60 * 1_000L

        @Volatile
        private var activeInstance: HybridStreamResolver? = null

        fun isCachingEnabled(): Boolean =
            activeInstance?.configStore?.current?.cacheWaveAndDownloadLikes != false

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
            return inst.tryResolveCustom(
                videoId = song.id,
                sourceSong = song,
                excludedProfiles = emptySet(),
                allowPreResolvedCache = false,
                isPreResolve = true,
                allowLocalFileCache = false,
            )
        }
    }
}
