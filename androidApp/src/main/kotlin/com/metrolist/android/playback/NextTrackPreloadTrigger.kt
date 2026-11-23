package com.metrolist.android.playback

import androidx.media3.common.Player
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

private const val EARLY_PRELOAD_POSITION_MS = 3_000L

internal data class NextTrackPreloadTrigger(
    val currentPlaybackInstanceId: Long,
    val nextVideoId: String?,
    val shouldPreload: Boolean,
    val hasValidPreload: Boolean,
    val invalidationGeneration: Long,
)

internal fun nextTrackPreloadTriggers(
    playbackPosition: Flow<Long>,
    currentMediaItemIndex: Flow<Int>,
    songQueue: Flow<List<SongItem>>,
    repeatMode: Flow<Int>,
    invalidation: Flow<Long>,
    currentPlaybackInstanceId: () -> Long,
    hasMore: () -> Boolean,
    stopAfterCurrentSong: () -> Boolean,
    isPreloadedTargetActive: (Long, String?) -> Boolean,
): Flow<NextTrackPreloadTrigger> =
    combine(
        playbackPosition,
        currentMediaItemIndex,
        songQueue,
        repeatMode,
        invalidation,
    ) { position, currentIndex, songs, mode, generation ->
        val instanceId = currentPlaybackInstanceId()
        val nextVideoId = nextQueuedIndex(songs.size, currentIndex, mode, hasMore())?.let { songs[it].id }
        val earlyReady =
            nextVideoId != null &&
                mode != Player.REPEAT_MODE_ONE &&
                !stopAfterCurrentSong() &&
                position >= EARLY_PRELOAD_POSITION_MS
        NextTrackPreloadTrigger(
            currentPlaybackInstanceId = instanceId,
            nextVideoId = nextVideoId,
            shouldPreload = earlyReady || shouldPreloadNextTrack(position, nextVideoId, mode, stopAfterCurrentSong()),
            hasValidPreload = isPreloadedTargetActive(instanceId, nextVideoId),
            invalidationGeneration = generation,
        )
    }.distinctUntilChanged()
