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
import java.util.concurrent.ConcurrentHashMap

class HybridStreamResolver(
    private val delegate: StreamResolver,
    private val configStore: CustomConfigStore,
    private val deezerClient: DeezerReferenceClient,
    private val yandexProvider: YandexMusicProvider,
    private val vkProvider: VkMusicProvider,
) : StreamResolver {
    private val failedProvidersByVideo = ConcurrentHashMap<String, MutableSet<String>>()

    override suspend fun resolve(
        videoId: String,
        hints: ContentHints,
        audioQuality: AudioQuality,
        sourceSong: SongItem?,
    ): PlaybackResource {
        tryResolveCustom(videoId, sourceSong)?.let { return it }
        return delegate.resolve(videoId, hints, audioQuality, sourceSong)
    }

    override suspend fun refresh(
        videoId: String,
        hints: ContentHints,
        audioQuality: AudioQuality,
        sourceSong: SongItem?,
    ): PlaybackResource {
        tryResolveCustom(videoId, sourceSong)?.let { return it }
        return delegate.refresh(videoId, hints, audioQuality, sourceSong)
    }

    override suspend fun resolveRemote(
        videoId: String,
        hints: ContentHints,
        audioQuality: AudioQuality,
        sourceSong: SongItem?,
        refresh: Boolean,
        excludedProfiles: Set<String>,
    ): PlaybackResource {
        tryResolveCustom(videoId, sourceSong, excludedProfiles)?.let { return it }
        return delegate.resolveRemote(videoId, hints, audioQuality, sourceSong, refresh, excludedProfiles)
    }

    private suspend fun tryResolveCustom(
        videoId: String,
        sourceSong: SongItem?,
        excludedProfiles: Set<String> = emptySet(),
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

        val reference =
            deezerClient.lookup(
                artist = artist,
                title = title,
                fallbackDurationSec = song.duration,
                fallbackExplicit = song.isExplicit || song.explicitness == true,
            )

        val order =
            if (reference.isExplicit && cfg.preferVkForExplicit && cfg.vkToken.isNotBlank()) {
                listOf("vk", "yandex")
            } else {
                listOf("yandex", "vk")
            }

        for (provider in order) {
            if (provider in failed || "custom_$provider" in excludedProfiles) continue
            val resolved =
                when (provider) {
                    "yandex" -> yandexProvider.resolve(reference)
                    "vk" -> vkProvider.resolve(reference)
                    else -> null
                } ?: continue

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

    override suspend fun prewarm() {
        delegate.prewarm()
    }

    override suspend fun clearPlaybackFailures(videoId: String) {
        failedProvidersByVideo.remove(videoId)
        delegate.clearPlaybackFailures(videoId)
    }

    override suspend fun invalidateNetworkScope(state: PlaybackNetworkState) {
        delegate.invalidateNetworkScope(state)
    }

    override suspend fun resetExtractionBackoff() {
        failedProvidersByVideo.clear()
        delegate.resetExtractionBackoff()
    }

    override suspend fun invalidateAttestation() {
        delegate.invalidateAttestation()
    }

    override suspend fun refreshPlayerConfigAfterStreamRejection(): Boolean =
        delegate.refreshPlayerConfigAfterStreamRejection()

    override suspend fun close() {
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
    }
}
