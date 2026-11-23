package com.metrolist.android.playback

import android.content.Context
import androidx.media3.common.Player
import com.metrolist.android.R
import com.metrolist.android.custom.AiWaveEngine
import com.metrolist.android.custom.CustomMediaCacheManager
import com.metrolist.android.deviceconnect.AndroidDeviceConnect
import com.metrolist.android.listentogether.AndroidListenTogetherManager
import com.metrolist.android.models.PersistPlayerState
import com.metrolist.android.models.PersistRadioQueueChip
import com.metrolist.shared.api.PlaylistPage
import com.metrolist.shared.api.shouldLoadPlaylistPage
import com.metrolist.shared.cast.CastState
import com.metrolist.shared.cast.forQueueEntry
import com.metrolist.shared.deviceconnect.DcPlaybackAction
import com.metrolist.shared.deviceconnect.DcPlaybackCommand
import com.metrolist.shared.model.RadioQueueChip
import com.metrolist.shared.model.RadioQueuePage
import com.metrolist.shared.model.RadioRecommendationHistory
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.buildRadioQueueRecommendations
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.model.selectRadioQueueChip
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.MusicQueueSnapshot
import com.metrolist.shared.playback.PlaybackSeekSource
import com.metrolist.shared.playback.QUEUE_SOURCE_AUTO_MIX
import com.metrolist.shared.playback.QUEUE_SOURCE_RECOMMENDED
import com.metrolist.shared.playback.isAutoMixQueueSource
import com.metrolist.shared.playback.isRecommendationGeneratedQueueSource
import com.metrolist.shared.playback.isRecommendedQueueSource
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

internal class AndroidQueueCoordinator(
    private val context: Context,
    private val player: AndroidPlaybackPlayer,
    private val scope: CoroutineScope,
    private val _repeatMode: MutableStateFlow<Int>,
    private val _shuffleModeEnabled: MutableStateFlow<Boolean>,
    private val onSave: () -> Unit,
    private val onSessionReset: () -> Unit,
    private val onVideoSelected: (SongItem) -> Unit = {},
    private val castState: () -> CastState = { CastState() },
) {
    companion object {
        private const val TAG = "MusicService"
        private const val MAX_RADIO_CONTINUATION_PAGES = 10
        private const val WAVE_REFILL_REMAINING_THRESHOLD = 3
    }

    lateinit var transitions: AndroidQueueTransitions
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()
    private val _isQueueLoading = MutableStateFlow(false)
    val isQueueLoading: StateFlow<Boolean> = _isQueueLoading.asStateFlow()
    private val queueLoadingOperations = AtomicInteger()
    private val _isPlaybackLoading = MutableStateFlow(false)
    val isPlaybackLoading: StateFlow<Boolean> = _isPlaybackLoading.asStateFlow()
    private val playbackLoadingOperations = AtomicInteger()

    private val queueTitleState = MutableStateFlow<String?>(null)
    val queueTitle: StateFlow<String?> = queueTitleState.asStateFlow()

    private val queueChipsState = MutableStateFlow<List<RadioQueueChip>>(emptyList())
    val queueChips: StateFlow<List<RadioQueueChip>> = queueChipsState.asStateFlow()

    var recommendationSeedSongId: String? = null
        private set
    private var radioQueueContinuation: String? = null
    private var radioQueuePlaylistId: String? = null
    private var radioQueueParams: String? = null
    private var isRadioQueue = false
    private val queuedSongIds = linkedSetOf<String>()
    private val recommendationHistory = RadioRecommendationHistory()
    private val queueSessionLock = Any()
    var isFiniteQueue = false
        private set
    private var recommendationRequestGeneration = 0L
    var recommendationBootstrapJob: Job? = null
        private set
    private var autoLoadMoreJob: Job? = null
    private var autoPivotJob: Job? = null
    private val _playlistPage = MutableStateFlow<PlaylistPage?>(null)
    val playlistPageState = _playlistPage.asStateFlow()
    var playlistPage: PlaylistPage?
        get() = _playlistPage.value
        private set(value) {
            _playlistPage.value = value
        }
    private var playlistPageJob: Job? = null
    private var lastAutoLoadedSongId: String? = null

    @Volatile
    var queueSessionId = 0L
        private set
    private var pendingRestorePositionMs: Long? = null
    private var restoredSongId: String? = null
    private var remoteLoadGeneration = 0L

    fun isMyWaveQueue(title: String? = queueTitleState.value): Boolean =
        title?.startsWith("Моя волна", ignoreCase = true) == true

    fun saveShuffleMode() {
        val queue = player.queueSnapshots.value
        if (queue.displayedItems.isNotEmpty()) Settings.shared.shuffleEnabled = queue.isShuffled
    }

    fun restoreShuffleMode() {
        _shuffleModeEnabled.value =
            restoredShuffleMode(Settings.shared.shuffleEnabled, Settings.shared.rememberShuffleAndRepeat)
    }

    fun buildPersistPlayerState(): PersistPlayerState? {
        if (!Settings.shared.persistentQueue) return null
        val queue = player.queueSnapshots.value
        if (queue.displayedItems.isEmpty()) return null
        val currentIndex = queue.currentIndex.coerceIn(queue.displayedItems.indices)
        val persistedQueue = queue.copy(currentIndex = currentIndex)

        val remote = castState().takeIf { it.isConnected }?.forQueueEntry(player.queueState.value)
        val playing = remote?.isPlaying ?: player.isPlaying.value
        val isWave = isMyWaveQueue()
        return PersistPlayerState(
            playWhenReady = playing,
            currentMediaItemIndex = currentIndex,
            currentPosition = remote?.positionMs ?: player.getRealtimePositionMs(),
            queueSnapshot = persistedQueue,
            playbackState = if (playing) 3 else 1,
            repeatMode = repeatMode.value,
            shuffleModeEnabled = persistedQueue.isShuffled,
            volume = 1.0f,
            queueTitle = queueTitleState.value,
            radioQueueSeedSongId = recommendationSeedSongId,
            radioQueueFinite = if (isWave) false else isFiniteQueue,
            radioQueue = if (isWave) false else isRadioQueue,
            queueSessionId = queueSessionId.takeIf { isRadioQueue && !isWave },
            radioQueueChips =
                if (isWave) {
                    emptyList()
                } else {
                    queueChipsState.value.map { chip ->
                        PersistRadioQueueChip(chip.id, chip.title, chip.playlistId, chip.params, chip.isSelected)
                    }
                },
            radioQueueSelectedChipId =
                if (isWave) {
                    null
                } else {
                    queueChipsState.value
                        .firstOrNull { it.isSelected }
                        ?.id
                },
            radioQueueContinuation = if (isWave) null else radioQueueContinuation,
            radioQueuePlaylistId = if (isWave) null else radioQueuePlaylistId,
            radioQueueParams = if (isWave) null else radioQueueParams,
        )
    }

    fun restorePersistedQueue(
        state: PersistPlayerState,
        restoredQueue: MusicQueueSnapshot,
        expectedQueueSessionId: Long,
        restoreModes: Boolean,
    ): Boolean =
        synchronized(queueSessionLock) {
            if (queueSessionId != expectedQueueSessionId || player.songQueue.value.isNotEmpty()) return@synchronized false
            val restoredSongs = restoredQueue.displayedItems
            val restoredSeed =
                restoredQueue.originalItems.firstOrNull { !it.queueSourceTitle.isRecommendationGeneratedQueueSource() }
                    ?: restoredSongs.getOrNull(restoredQueue.currentIndex)
            val restoredMix = restoredQueue.originalItems.any { it.queueSourceTitle.isAutoMixQueueSource() }
            val resolvedTitle =
                state.queueTitle
                    ?: restoredSeed?.title?.takeIf { restoredMix }?.let { context.getString(R.string.song_mix_title, it) }
            val restoredIsWave = isMyWaveQueue(resolvedTitle)
            resetQueueSession(
                seedSongId = state.radioQueueSeedSongId ?: restoredSeed?.id,
                queueTitle = resolvedTitle,
                finite = if (restoredIsWave) false else (state.radioQueueFinite ?: !restoredMix),
                sessionId = state.queueSessionId,
            )
            val restoredPosition = state.currentPosition
            pendingRestorePositionMs = restoredPosition.takeIf { it > 0L }
            restoredSongId = restoredSongs.getOrNull(restoredQueue.currentIndex)?.id
            isRadioQueue = !restoredIsWave && (state.radioQueue || restoredMix)
            radioQueueContinuation = if (restoredIsWave) null else state.radioQueueContinuation
            radioQueuePlaylistId = if (restoredIsWave) null else state.radioQueuePlaylistId
            radioQueueParams = if (restoredIsWave) null else state.radioQueueParams
            queueChipsState.value =
                if (restoredIsWave) {
                    emptyList()
                } else {
                    state.radioQueueChips.map { chip ->
                        RadioQueueChip(
                            chip.id,
                            chip.title,
                            chip.playlistId,
                            chip.params,
                            state.radioQueueSelectedChipId?.let { it == chip.id } ?: chip.isSelected,
                        )
                    }
                }
            queuedSongIds.addAll(restoredSongs.map(SongItem::id))
            player.setQueue(
                songs = restoredSongs,
                startIndex = restoredQueue.currentIndex,
                startPlaying = false,
            )
            player.restoreQueue(restoredQueue)
            _shuffleModeEnabled.value = player.isShuffleEnabled
            _repeatMode.value = if (restoreModes) state.repeatMode else Player.REPEAT_MODE_OFF
            player.seekTo(restoredPosition, PlaybackSeekSource("queue_restore", mapOf("reason" to "saved_queue")))
            true
        }

    fun takeRestorePosition(
        song: SongItem,
        requestedPositionMs: Long,
    ): Long {
        val position = (pendingRestorePositionMs?.takeIf { song.id == restoredSongId } ?: requestedPositionMs).coerceAtLeast(0L)
        pendingRestorePositionMs = null
        restoredSongId = null
        return position
    }

    fun setTitle(title: String?) {
        queueTitleState.value = title
        if (isMyWaveQueue(title)) {
            queueChipsState.value = emptyList()
        }
    }

    fun resetQueueSession(
        seedSongId: String? = null,
        queueTitle: String? = null,
        finite: Boolean = false,
        sessionId: Long? = null,
    ) {
        if (sessionId == null) {
            pendingRestorePositionMs = null
            restoredSongId = null
            Settings.shared.pendingContinuePlayback = false
        }
        recommendationBootstrapJob?.cancel()
        recommendationBootstrapJob = null
        playlistPageJob?.cancel()
        playlistPageJob = null
        playlistPage = null
        autoLoadMoreJob?.cancel()
        autoLoadMoreJob = null
        autoPivotJob?.cancel()
        autoPivotJob = null
        lastAutoLoadedSongId = null
        transitions.cancelAndroidCrossfade()
        transitions.cancelNextTrackPreload()
        transitions.cancelSongCache()
        queueSessionId = sessionId ?: (queueSessionId + 1)
        recommendationRequestGeneration++
        recommendationSeedSongId = seedSongId
        queueTitleState.value = queueTitle
        queueChipsState.value = emptyList()
        isFiniteQueue = if (isMyWaveQueue(queueTitle)) false else finite
        isRadioQueue = false
        radioQueueContinuation = null
        radioQueuePlaylistId = null
        radioQueueParams = null
        queuedSongIds.clear()
        seedSongId?.let { queuedSongIds.add(it) }
        onSessionReset()
    }

    fun synchronizeMediaSessionQueue(replacesQueue: Boolean) {
        val songs = player.songQueue.value
        synchronized(queueSessionLock) {
            if (replacesQueue || songs.isEmpty()) {
                resetQueueSession(
                    seedSongId = songs.getOrNull(player.currentMediaItemIndex.value)?.id,
                    finite = true,
                )
            }
            queuedSongIds.clear()
            queuedSongIds.addAll(songs.map(SongItem::id))
        }
        onSave()
    }

    fun replaceQueueSession(
        songs: List<SongItem>,
        startIndex: Int,
        queueTitle: String?,
        finite: Boolean,
        playlistPage: PlaylistPage? = null,
        replacement: () -> Unit,
    ) {
        val seed = songs.getOrNull(startIndex) ?: return
        synchronized(queueSessionLock) {
            resetQueueSession(seed.id, queueTitle, finite)
            this.playlistPage = playlistPage
            queuedSongIds.addAll(songs.map(SongItem::id))
            replacement()
        }
        onSave()
    }

    fun playQueue(
        songs: List<SongItem>,
        startIndex: Int = 0,
        queueTitle: String? = null,
        finite: Boolean? = null,
        fromRemote: Boolean = false,
        shuffleModeEnabled: Boolean? = null,
        playlistPage: PlaylistPage? = null,
        songsOnly: Boolean = false,
        startPositionMs: Long = 0L,
        startPlaying: Boolean = true,
        startVideo: Boolean = false,
    ) {
        val (playableSongs, adjustedStartIndex) =
            filteredQueueSelection(songs, startIndex, BlockedContent.snapshot()::isBlocked) ?: return
        val isWave = isMyWaveQueue(queueTitle)
        val effectiveFinite = if (isWave) false else (finite ?: true)
        val shuffleEnabled =
            newQueueShuffleEnabled(
                requestedShuffle = shuffleModeEnabled,
                currentShuffle = if (player.songQueue.value.isEmpty()) _shuffleModeEnabled.value else player.isShuffleEnabled,
                persistentShuffle = Settings.shared.persistentShuffleAcrossQueues,
            )
        if (!fromRemote &&
            AndroidDeviceConnect.intercept(
                DcPlaybackCommand(
                    DcPlaybackAction.REPLACE_QUEUE,
                    songs = playableSongs,
                    index = adjustedStartIndex,
                    title = queueTitle,
                    finite = effectiveFinite,
                    enabled = shuffleEnabled,
                    preserveHistory = shuffleModeEnabled != true,
                    positionMs = startPositionMs,
                ),
            )
        ) {
            return
        }
        replaceQueueSession(playableSongs, adjustedStartIndex, queueTitle, effectiveFinite, playlistPage) {
            if (startVideo && !fromRemote) onVideoSelected(playableSongs[adjustedStartIndex])
            player.setQueue(
                songs = playableSongs,
                startIndex = adjustedStartIndex,
                startPlaying = startPlaying,
                preparePlayback = true,
                startPositionMs = startPositionMs,
                recommendationsSongsOnly = songsOnly,
            )
            if (shuffleEnabled && !isWave) player.toggleShuffle(preserveHistory = shuffleModeEnabled != true)
            _shuffleModeEnabled.value = player.isShuffleEnabled
        }
    }

    suspend fun playQueueFromRemote(
        songs: List<SongItem>,
        startIndex: Int,
        title: String?,
        prepare: suspend (SongItem, () -> Boolean) -> Boolean,
    ): Boolean {
        val (playableSongs, adjustedStartIndex) =
            filteredQueueSelection(songs, startIndex, BlockedContent.snapshot()::isBlocked) ?: return false
        val generation = ++remoteLoadGeneration
        val sessionId = queueSessionId
        val context = currentCoroutineContext()
        val isCurrent = { context.isActive && generation == remoteLoadGeneration && sessionId == queueSessionId }
        return withQueueLoading(isPlaybackLoading = true) {
            if (!prepare(playableSongs[adjustedStartIndex], isCurrent) || !isCurrent()) return@withQueueLoading false
            val shuffleEnabled =
                newQueueShuffleEnabled(
                    requestedShuffle = null,
                    currentShuffle = if (player.songQueue.value.isEmpty()) _shuffleModeEnabled.value else player.isShuffleEnabled,
                    persistentShuffle = Settings.shared.persistentShuffleAcrossQueues,
                )
            val preparedSongs = playableSongs.toMutableList()
            player.currentSong.value?.let {
                preparedSongs[adjustedStartIndex] = it
            }
            val preparedQueue =
                MusicQueue().apply {
                    setQueue(preparedSongs, adjustedStartIndex)
                    if (shuffleEnabled) toggleShuffle(legacyShuffle = Settings.shared.useLegacyShuffle)
                }
            replaceQueueSession(preparedSongs, adjustedStartIndex, title, finite = true) {
                player.restoreQueue(preparedQueue.snapshot())
                _shuffleModeEnabled.value = player.isShuffleEnabled
            }
            true
        }
    }

    fun extendPlaylistBeforeSkip(resumePlayback: Boolean? = null): Boolean {
        if (playlistPage?.hasMore != true || player.currentMediaItemIndex.value < player.songQueue.value.lastIndex) return false
        val sessionId = queueSessionId
        schedulePlaylistPage()
        scope.launch {
            playlistPageJob?.join()
            if (sessionId == queueSessionId && player.currentMediaItemIndex.value < player.songQueue.value.lastIndex) {
                player.skipToNext(resumePlayback ?: player.wantsPlayback)
            }
        }
        return true
    }

    private fun appendPlaylistSongs(songs: List<SongItem>) {
        player.appendPlaylistSongs(songs)
        queuedSongIds.clear()
        queuedSongIds.addAll(player.songQueue.value.map(SongItem::id))
        onSave()
    }

    fun schedulePlaylistPage() {
        if (!PlaybackNetworkStateStore.current.connected) return
        if (playlistPageJob?.isActive == true || playlistPage?.hasMore != true) return
        val sessionId = queueSessionId
        playlistPageJob =
            scope.launch {
                try {
                    do {
                        val previous = playlistPage ?: break
                        val next =
                            withContext(Dispatchers.IO) {
                                GlobalYouTubeRepository.instance.continuePlaylistPage(previous, applyContentFilters = false)
                            }
                        if (!PlaybackNetworkStateStore.current.connected || sessionId != queueSessionId ||
                            AndroidListenTogetherManager.isGuestPlaybackLocked ||
                            AndroidDeviceConnect.manager.remote.isFollowing
                        ) {
                            return@launch
                        }
                        playlistPage = next
                        val additions = BlockedContent.filterPlayableSongs(next.songs.drop(previous.songs.size))
                        appendPlaylistSongs(additions)
                    } while (playlistPage?.hasMore == true &&
                        (player.isShuffleEnabled || shouldLoadPlaylistPage(player.currentMediaItemIndex.value, player.songQueue.value.size))
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).w("Playlist page failed type=%s", e::class.simpleName)
                }
            }
    }

    /** Запуск волны по конкретному треку (из поиска, чарта или уведомления) — сразу через наш AiWaveEngine. */
    fun playMix(
        seed: SongItem,
        startPlaying: Boolean,
    ) {
        if (seed.isUnavailable || BlockedContent.isBlocked(seed)) return
        val waveEngine = AiWaveEngine.sharedInstance
        val title =
            if (waveEngine != null) {
                "Моя волна • ${seed.title}"
            } else {
                context.getString(R.string.song_mix_title, seed.title)
            }
        val current = player.currentSong.value?.takeIf { it.id == seed.id }
        if (AndroidDeviceConnect.intercept(
                DcPlaybackCommand(
                    DcPlaybackAction.REPLACE_QUEUE,
                    songs = listOf(seed),
                    index = 0,
                    title = title,
                    finite = false,
                    positionMs = if (current != null) player.getRealtimePositionMs() else 0L,
                    preserveCurrentTrack = true,
                ),
            )
        ) {
            if (startPlaying) AndroidDeviceConnect.intercept(DcPlaybackCommand(DcPlaybackAction.PLAY))
            return
        }
        _repeatMode.value = Player.REPEAT_MODE_OFF
        val preserveCurrent = current != null && player.isReady && !player.playbackTransitionPending.value
        if (preserveCurrent) {
            replaceQueueSession(listOf(requireNotNull(current)), 0, title, finite = false) {
