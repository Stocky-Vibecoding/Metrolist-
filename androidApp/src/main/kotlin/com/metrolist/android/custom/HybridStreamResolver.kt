package com.metrolist.android.custom

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class ResolverLogEntry(
    val timestampMs: Long,
    val trackTitle: String,
    val qualityBadge: String,
    val detail: String,
)

private data class PreResolvedEntry(
    val resource: PlaybackResource,
    val sourcePref: TrackSourcePreference,
    val expiresAtMs: Long,
)

object ResolverDiagnostics {
    private val _logs = MutableStateFlow<List<ResolverLogEntry>>(emptyList())
    val logs: StateFlow<List<ResolverLogEntry>> = _logs.asStateFlow()

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
        val label = if (artist.isNotBlank()) "$artist —$title" else title
        val entry =
            ResolverLogEntry(
                timestampMs = System.currentTimeMillis(),
                trackTitle = label,
                qualityBadge = qualityBadge,
                detail = detail,
            )
        _logs.value = (listOf(entry) + _logs.value).take(40)
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
    ): String? {
        if (clientName.isNullOrBlank()) return null
        val kbps = bitrate?.takeIf { it > 0 }?.let { if (it > 1_000) it / 1_000 else it }
        return when (clientName) {
            "yandex" -> if (kbps != null) "Я.Музыка • $kbps" else "Я.Музыка • 320"
            "vk" -> if (isHls || mimeType?.contains("mpegURL", ignoreCase = true) == true) "VK • HLS" else "VK • MP3"
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

    private fun invalidateTrackInternal(videoId: String) {
        failedProvidersByVideo.remove(videoId)
        preResolvedCache.remove(videoId)
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
                Log.d(TAG, "Pre-resolved next track: $artist - $title via${resolved.clientName}")
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
    ): PlaybackResource? {
        val cfg = configStore.current
        if (!cfg.hybridResolverEnabled) return null
        if (cfg.yandexToken.isBlank() && cfg.vkToken.isBlank()) return null

        val song = sourceSong ?: return null
        if (song.isLocal || song.isUploaded || song.isPodcast || song.isLive) return null
        if (song.title.isBlank()) return null

        val failed = failedProvidersByVideo[videoId].orEmpty()
        val artist =
            (song.canonicalArtists ?: song.artists)
                .joinToString(", ") { it.name }
                .trim()
        val title = (song.canonicalTitle ?: song.title).trim()

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

        if (allowPreResolvedCache && failed.isEmpty() && excludedProfiles.isEmpty()) {
            val cached = preResolvedCache.remove(videoId)
            if (cached != null &&
                cached.expiresAtMs > System.currentTimeMillis() &&
                cached.sourcePref == manualPref
            ) {
                Log.d(TAG, "Instant pre-resolved hit for $artist -$title")
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

        val aiCensored =
            if (manualPref == TrackSourcePreference.AUTO &&
                !reference.isExplicit &&
                cfg.aiCensorshipCheckEnabled &&
                cfg.preferVkForExplicit &&
                cfg.vkToken.isNotBlank() &&
                shouldEvaluateCensorship(artist, title)
            ) {
                evaluateCensorshipWithCache(artist, title)
            } else {
                false
            }

        val (order, reason) =
            when {
                manualPref == TrackSourcePreference.YANDEX -> listOf("yandex") to "ручной выбор"
                manualPref == TrackSourcePreference.VK -> listOf("vk") to "ручной выбор"
                videoId.startsWith("vk_") -> listOf("vk", "yandex") to "каталог VK"
                videoId.startsWith("ym_") -> listOf("yandex", "vk") to "каталог Яндекса"
                (reference.isExplicit || aiCensored) && cfg.preferVkForExplicit && cfg.vkToken.isNotBlank() -> {
                    val tag = if (aiCensored) "ИИ-антицензура" else "Explicit → VK"
                    listOf("vk", "yandex") to tag
                }
                else -> listOf("yandex", "vk") to "автокаскад"
            }

        for (provider in order) {
            if (provider in failed || "custom_$provider" in excludedProfiles) continue
            val resolved =
                when (provider) {
                    "yandex" -> yandexProvider.resolve(reference)
                    "vk" -> vkProvider.resolve(reference)
                    else -> null
                } ?: continue

            val badge =
                ResolverDiagnostics.formatQualityBadge(
                    clientName = resolved.clientName,
                    bitrate = resolved.bitrate,
                    mimeType = resolved.mimeType,
                    isHls = resolved.isHls,
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

    private fun shouldEvaluateCensorship(
        artist: String,
        title: String,
    ): Boolean {
        val combined = "$artist$title"
        return combined.any { ch -> ch in '\u0400'..'\u04FF' }
    }

    private suspend fun evaluateCensorshipWithCache(
        artist: String,
        title: String,
    ): Boolean {
        configStore.getCachedCensorship(artist, title)?.let { return it }
        val client = GeminiClient.sharedInstance ?: return false
        val verdict = client.checkCensorshipRisk(artist, title) ?: return false
        configStore.putCachedCensorship(artist, title, verdict)
        return verdict
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
        if (clientName == "yandex" || clientName == "vk") {
            Log.w(TAG, "Custom stream failed ($clientName, code=$responseCode) for$videoId, falling back")
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

        fun invalidateTrack(videoId: String) {
            activeInstance?.invalidateTrackInternal(videoId)
        }

        suspend fun preResolve(song: SongItem) {
            activeInstance?.preResolveInternal(song)
        }
    }
}
