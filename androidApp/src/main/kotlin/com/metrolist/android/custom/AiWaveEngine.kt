package com.metrolist.android.custom

import android.util.Log
import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class AiPlaylistResult(
    val query: String,
    val songs: List<SongItem>,
)

private data class StructuredTasteProfile(
    val coreRecentTracks: List<String>,
    val topArtists: List<String>,
    val extraContextTracks: List<String>,
    val sessionPositiveTracks: List<String>,
) {
    val isNotEmpty: Boolean
        get() =
            coreRecentTracks.isNotEmpty() ||
                topArtists.isNotEmpty() ||
                extraContextTracks.isNotEmpty() ||
                sessionPositiveTracks.isNotEmpty()
}

class AiWaveEngine(
    private val configStore: CustomConfigStore,
    private val geminiClient: GeminiClient,
) {
    private val waveMutex = Mutex()
    private val sessionSkippedUntilMs = ConcurrentHashMap<String, Long>()
    private val sessionSkippedLabels = ConcurrentHashMap<String, String>()
    private val sessionPositiveLabels = ArrayDeque<String>()
    private val sessionLock = Any()

    @Volatile
    private var consecutiveQuickSkips: Int = 0

    @Volatile
    private var lastAutoPivotMs: Long = 0L

    private val _isGeneratingWave = MutableStateFlow(false)
    val isGeneratingWave: StateFlow<Boolean> = _isGeneratingWave.asStateFlow()

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    init {
        sharedInstance = this
    }

    /**
     * Мгновенный старт волны при открытии/перезапуске:
     * 1. Продолжает сохранённую прошлую очередь (`lastWaveTracks`) без сброса и лишнего запроса к Gemini.
     * 2. Если её нет или нет сети — подхватывает умную подборку из локального кэша 4 ГБ / защищённых лайков.
     */
    fun buildInstantStarterSongs(
        excludeIds: Set<String> = emptySet(),
        limit: Int = 12,
    ): List<SongItem> {
        val cfg = configStore.current
        val savedWave =
            filterSongsByFeedback(
                cfg.lastWaveTracks.mapNotNull { it.toStarterSongItem() },
                respectSessionSkips = true,
                respectPlayedCooldown = false,
            ).filter { it.id.isNotBlank() && it.id !in excludeIds }
                .distinctBy { it.id }

        if (savedWave.isNotEmpty()) {
            return savedWave.take(limit)
        }

        val offlinePool =
            filterSongsByFeedback(
                CustomMediaCacheManager.getOfflineCachedSongs(
                    preferredVibe = currentVibeTag(cfg),
                    preferredArtists = configStore.getLocalTopArtists(15).toSet(),
                    excludeIds = excludeIds,
                    limit = limit * 2,
                ),
                respectSessionSkips = true,
                respectPlayedCooldown = false,
            ).distinctBy { it.id }

        if (offlinePool.isNotEmpty()) {
            return offlinePool.take(limit)
        }

        val recentLikedPool =
            filterSongsByFeedback(
                SongLikeState.likedSongs
                    .sortedByDescending { it.libraryAddedAt ?: 0L }
                    .take(24) +
                    cfg.likedTracksBackup.take(24).mapNotNull { it.toStarterSongItem() },
                respectSessionSkips = true,
                respectPlayedCooldown = false,
            ).filter { it.id.isNotBlank() && it.id !in excludeIds }
                .distinctBy { it.id }
                .shuffled()

        return recentLikedPool.take(limit)
    }

    fun currentVibeTag(cfg: CustomAppConfig = configStore.current): String =
        listOfNotNull(
            cfg.waveMood.takeIf { it != WaveMood.ANY }?.name,
            cfg.waveLanguage.takeIf { it != WaveLanguage.ANY }?.name,
            cfg.waveEra.takeIf { it != WaveEra.ANY }?.name,
        ).joinToString("_").ifBlank { "DEFAULT" }

    fun recordSessionSkip(
        song: SongItem,
        isQuickSkip: Boolean = true,
    ) {
        val artist = song.artists.joinToString(", ") { it.name }.trim()
        val key = CustomConfigStore.buildTrackKey(artist, song.title)
        if (key.isNotBlank()) {
            val now = System.currentTimeMillis()
            if (sessionSkippedUntilMs.size > 60) {
                val expired = sessionSkippedUntilMs.filterValues { it <= now }.keys
                expired.forEach {
                    sessionSkippedUntilMs.remove(it)
                    sessionSkippedLabels.remove(it)
                }
            }
            sessionSkippedUntilMs[key] = now + SESSION_SKIP_COOLDOWN_MS
            sessionSkippedLabels[key] = if (artist.isNotBlank()) "$artist — ${song.title}" else song.title
            if (sessionSkippedLabels.size > 20) {
                val firstKey = sessionSkippedLabels.keys.firstOrNull()
                if (firstKey != null) sessionSkippedLabels.remove(firstKey)
            }
        }
        if (isQuickSkip) {
            consecutiveQuickSkips += 1
        }
    }

    /** Вызывается при дослушивании >75% трека или повторе — работает как скрытый «мини-лайк» текущей сессии. */
    fun recordTrackCompletion(
        song: SongItem,
        completed: Boolean = true,
    ) {
        val artist = song.artists.joinToString(", ") { it.name }.trim()
        configStore.recordTrackPlayed(artist = artist, title = song.title, completed = completed)
        if (completed) {
            consecutiveQuickSkips = 0
            val label = if (artist.isNotBlank()) "$artist — ${song.title}" else song.title
            if (label.isNotBlank()) {
                synchronized(sessionLock) {
                    sessionPositiveLabels.remove(label)
                    sessionPositiveLabels.addFirst(label)
                    while (sessionPositiveLabels.size > 8) {
                        sessionPositiveLabels.removeLast()
                    }
                }
            }
        }
    }

    fun shouldTriggerAutoPivot(): Boolean {
        if (consecutiveQuickSkips < AUTO_PIVOT_SKIP_THRESHOLD) return false
        val now = System.currentTimeMillis()
        return (now - lastAutoPivotMs) >= AUTO_PIVOT_COOLDOWN_MS
    }

    fun markAutoPivotTriggered() {
        consecutiveQuickSkips = 0
        lastAutoPivotMs = System.currentTimeMillis()
    }

    fun recordDislike(song: SongItem) {
        val artist = song.artists.joinToString(", ") { it.name }.trim()
        configStore.dislikeTrack(artist = artist, title = song.title)
        recordSessionSkip(song, isQuickSkip = true)
    }

    fun recordLike(song: SongItem) {
        val artist = song.artists.joinToString(", ") { it.name }.trim()
        val realThumb = song.thumbnail?.takeUnless { it == CustomMascotAssets.fallbackCoverUri }
        configStore.likeTrack(artist = artist, title = song.title, songId = song.id, thumbnail = realThumb)
        consecutiveQuickSkips = 0
        val key = CustomConfigStore.buildTrackKey(artist, song.title)
        sessionSkippedUntilMs.remove(key)
        sessionSkippedLabels.remove(key)
        val label = if (artist.isNotBlank()) "$artist — ${song.title}" else song.title
        if (label.isNotBlank()) {
            synchronized(sessionLock) {
                sessionPositiveLabels.remove(label)
                sessionPositiveLabels.addFirst(label)
                while (sessionPositiveLabels.size > 8) {
                    sessionPositiveLabels.removeLast()
                }
            }
        }
    }

    private fun isSessionSkipped(
        artist: String,
        title: String,
    ): Boolean {
        val key = CustomConfigStore.buildTrackKey(artist, title)
        val until = sessionSkippedUntilMs[key] ?: return false
        if (System.currentTimeMillis() > until) {
            sessionSkippedUntilMs.remove(key)
            sessionSkippedLabels.remove(key)
            return false
        }
        return true
    }

    /**
     * Генерация адаптивной пачки волны (12–15 треков):
     * - Gemini по локальному профилю вкуса + сигналам сессии;
     * - Резерв Last.fm при 429 / отсутствии ключа;
     * - Бесшовный офлайн-фолбэк из 4 ГБ кэша при отсутствии интернета.
     */
    suspend fun generateWaveSongs(
        seedSong: SongItem? = null,
        count: Int = DEFAULT_WAVE_BATCH_SIZE,
        backgroundLabel: Boolean = false,
        pivotVibe: Boolean = false,
        excludeIds: Set<String> = emptySet(),
    ): Result<List<SongItem>> =
        waveMutex.withLock {
            var keepErrorStatus = false
            _isGeneratingWave.value = true
            _statusText.value =
                when {
                    pivotVibe -> "Меняю настрой волны…"
                    seedSong != null -> "Собираю волну от «${seedSong.title}»…"
                    backgroundLabel -> "Подбираю следующую пачку…"
                    else -> "Подбираю волну…"
                }
            try {
                val cfg = configStore.current
                val profile = collectStructuredTasteProfile(cfg)
                val targetCount = count.coerceIn(MIN_WAVE_BATCH_SIZE, MAX_WAVE_BATCH_SIZE)
                // Просим у Gemini на пару треков больше с запасом на строгий отсев
                val requestCount = (targetCount + 4).coerceAtMost(20)

                val prompt =
                    buildWavePrompt(
                        cfg = cfg,
                        seedSong = seedSong,
                        profile = profile,
                        pivotVibe = pivotVibe,
                    )

                val geminiResult =
                    if (cfg.geminiApiKey.isNotBlank()) {
                        geminiClient.generateTrackList(prompt = prompt, count = requestCount)
                    } else {
                        Result.failure(IllegalStateException("Gemini API ключ не указан"))
                    }

                val resolvedOnline =
                    geminiResult.fold(
                        onSuccess = { suggestions ->
                            val filtered = filterSuggestionsByFeedback(suggestions, respectPlayedCooldown = true)
                            val found = resolveSuggestionsToSongs(filtered, maxResults = targetCount)
                            diversifyWaveSongs(
                                songs =
                                    filterSongsByFeedback(
                                        found,
                                        respectSessionSkips = true,
                                        respectPlayedCooldown = true,
                                    ).filter { it.id !in excludeIds }.map(::withFallbackCover),
                                cfg = cfg,
                            )
                        },
                        onFailure = { err ->
                            Log.w(TAG, "Gemini wave generation failed (${err.message}), trying Last.fm fallback")
                            emptyList()
                        },
                    )

                val finalSongs =
                    when {
                        resolvedOnline.isNotEmpty() -> resolvedOnline
                        else -> {
                            val lastFmSongs =
                                generateLastFmFallbackSongs(
                                    cfg = cfg,
                                    seedSong = seedSong,
                                    profile = profile,
                                    excludeIds = excludeIds,
                                    limit = targetCount,
                                )
                            if (lastFmSongs.isNotEmpty()) {
                                lastFmSongs
                            } else {
                                CustomMediaCacheManager
                                    .getOfflineCachedSongs(
                                        preferredVibe = currentVibeTag(cfg),
                                        preferredArtists = profile.topArtists.toSet(),
                                        excludeIds = excludeIds,
                                        limit = targetCount,
                                    ).let { filterSongsByFeedback(it, respectSessionSkips = true, respectPlayedCooldown = false) }
                                    .map(::withFallbackCover)
                            }
                        }
                    }

                if (finalSongs.isEmpty()) {
                    val err = geminiResult.exceptionOrNull() ?: IllegalStateException("Не удалось подобрать треки")
                    if (!backgroundLabel) {
                        _statusText.value = err.message ?: "Ошибка генерации волны"
                        keepErrorStatus = true
                    }
                    return@withLock Result.failure(err)
                }

                if (pivotVibe) {
                    markAutoPivotTriggered()
                }

                if (seedSong == null) {
                    val savedWave =
                        finalSongs.map { song ->
                            LikedTrackBackup(
                                id = song.id,
                                artist = song.artists.joinToString(", ") { it.name }.trim(),
                                title = song.title,
                                thumbnail = song.thumbnail?.takeUnless { it == CustomMascotAssets.fallbackCoverUri },
                            )
                        }
                    configStore.saveLastWaveTracks(savedWave)
                }

                CustomMediaCacheManager.preloadUpcomingTracks(
                    songs = finalSongs,
                    vibeTag = currentVibeTag(cfg),
                )

                Result.success(finalSongs)
            } finally {
                if (!keepErrorStatus) {
                    _statusText.value = null
                }
                _isGeneratingWave.value = false
            }
        }

    suspend fun generateCustomPlaylist(
        requestText: String,
        count: Int = configStore.current.aiPlaylistSize,
    ): Result<AiPlaylistResult> {
        val cleanRequest = requestText.trim()
        if (cleanRequest.isBlank()) {
            return Result.failure(IllegalArgumentException("Пустой запрос"))
        }
        val cfg = configStore.current
        val profile = collectStructuredTasteProfile(cfg)
        val targetCount = count.coerceIn(10, 100)

        val allSuggestions = mutableListOf<AiTrackSuggestion>()
        val seenKeys = HashSet<String>()

        val batchSizes =
            if (targetCount > 50) {
                listOf(50, targetCount - 50)
            } else {
                listOf(targetCount)
            }

        var lastError: Throwable? = null
        for ((index, batchCount) in batchSizes.withIndex()) {
            val prompt =
                buildString {
                    appendLine("Пользователь хочет подобрать музыку по запросу: \"$cleanRequest\".")
                    if (cfg.waveLanguage != WaveLanguage.ANY) {
                        appendLine("Языковое предпочтение: ${cfg.waveLanguage.promptHint}.")
                    }
                    if (profile.topArtists.isNotEmpty()) {
                        appendLine("Любимые артисты пользователя (ориентир по уровню и вкусу): ${profile.topArtists.joinToString(", ")}.")
                    }
                    if (profile.coreRecentTracks.isNotEmpty()) {
                        appendLine("Недавние треки пользователя: ${profile.coreRecentTracks.take(10).joinToString("; ")}.")
                    }
                    if (index > 0 && allSuggestions.isNotEmpty()) {
                        val exclude = allSuggestions.takeLast(30).joinToString("; ") { "${it.artist} - ${it.title}" }
                        appendLine("НЕ повторяй уже предложенные треки: $exclude.")
                    }
                    appendFeedbackHints(cfg, includeSessionSkips = false)
                    appendLine("Подбирай только реальные оригинальные студийные треки (без slowed, sped up, nightcore, каверов и караоке), которые точно попадают в этот запрос.")
                }

            val batchResult = geminiClient.generateTrackList(prompt = prompt, count = batchCount)
            batchResult
                .onSuccess { list ->
                    list.forEach { item ->
                        val k = CustomConfigStore.buildTrackKey(item.artist, item.title)
                        if (k.isNotBlank() && seenKeys.add(k)) {
                            allSuggestions.add(item)
                        }
                    }
                }.onFailure { err ->
                    lastError = err
                }
        }

        if (allSuggestions.isEmpty()) {
            return Result.failure(lastError ?: IllegalStateException("Не удалось подобрать треки по запросу"))
        }

        val filtered = filterSuggestionsByFeedback(allSuggestions, respectSessionSkips = false, respectPlayedCooldown = false)
        val resolved = resolveSuggestionsToSongs(filtered, maxResults = targetCount)
        val cleanResolved =
            filterSongsByFeedback(resolved, respectSessionSkips = false, respectPlayedCooldown = false)
                .map(::withFallbackCover)
        if (cleanResolved.isEmpty()) {
            return Result.failure(IllegalStateException("Не удалось найти треки в каталоге"))
        }
        return Result.success(AiPlaylistResult(query = cleanRequest, songs = cleanResolved))
    }

    suspend fun generateMascotPhrase(song: SongItem): String? {
        val cfg = configStore.current
        val artist = song.artists.joinToString(", ") { it.name }.trim().ifBlank { "Неизвестный" }
        val moodHint =
            listOfNotNull(
                cfg.waveMood.takeIf { it != WaveMood.ANY }?.label,
                cfg.waveEra.takeIf { it != WaveEra.ANY }?.label,
                cfg.waveCharacter.takeIf { it != WaveCharacter.DEFAULT }?.label,
            ).joinToString(", ").takeIf { it.isNotBlank() }
        return geminiClient.generateMascotLine(
            artist = artist,
            title = song.title,
            moodHint = moodHint,
        )
    }

    /** Резервная дозагрузка похожих треков через Last.fm (при 429 от Gemini). */
    private suspend fun generateLastFmFallbackSongs(
        cfg: CustomAppConfig,
        seedSong: SongItem?,
        profile: StructuredTasteProfile,
        excludeIds: Set<String>,
        limit: Int,
    ): List<SongItem> =
        withContext(Dispatchers.IO) {
            val apiKey = cfg.lastFmApiKey.trim()
            if (apiKey.isBlank()) return@withContext emptyList()

            val suggestions = mutableListOf<AiTrackSuggestion>()
            val seen = HashSet<String>()

            fun addSuggestion(
                artist: String,
                title: String,
            ) {
                val a = artist.trim()
                val t = title.trim()
                if (a.isBlank() || t.isBlank()) return
                val k = CustomConfigStore.buildTrackKey(a, t)
                if (k.isNotBlank() && seen.add(k)) {
                    suggestions.add(AiTrackSuggestion(artist = a, title = t))
                }
            }

            if (seedSong != null) {
                val seedArtist = seedSong.artists.joinToString(", ") { it.name }.trim()
                fetchLastFmSimilarTracks(apiKey, seedArtist, seedSong.title, limit = 16).forEach {
                    addSuggestion(it.artist, it.title)
                }
            }

            val anchorArtists =
                buildList {
                    seedSong?.artists?.firstOrNull()?.name?.takeIf { it.isNotBlank() }?.let(::add)
                    addAll(profile.topArtists.shuffled().take(4))
                }.distinct().take(4)

            for (artist in anchorArtists) {
                if (suggestions.size >= limit + 6) break
                val similarArtists = fetchLastFmSimilarArtists(apiKey, artist, limit = 4)
                for (sim in similarArtists.shuffled().take(3)) {
                    fetchLastFmArtistTopTracks(apiKey, sim, limit = 3).forEach {
                        addSuggestion(it.artist, it.title)
                    }
                    if (suggestions.size >= limit + 6) break
                }
            }

            if (suggestions.isEmpty()) return@withContext emptyList()
            val filtered = filterSuggestionsByFeedback(suggestions.shuffled(), respectPlayedCooldown = true)
            val resolved = resolveSuggestionsToSongs(filtered, maxResults = limit)
            diversifyWaveSongs(
                songs =
                    filterSongsByFeedback(resolved, respectSessionSkips = true, respectPlayedCooldown = true)
                        .filter { it.id !in excludeIds }
                        .map(::withFallbackCover),
                cfg = cfg,
            )
        }

    private suspend fun fetchLastFmSimilarTracks(
        apiKey: String,
        artist: String,
        title: String,
        limit: Int,
    ): List<AiTrackSuggestion> =
        try {
            val url =
                "https://ws.audioscrobbler.com/2.0/?method=track.getsimilar" +
                    "&artist=${CustomHttp.urlEncode(artist)}" +
                    "&track=${CustomHttp.urlEncode(title)}" +
                    "&limit=$limit&api_key=${CustomHttp.urlEncode(apiKey)}&format=json"
            val resp = CustomHttp.get(url, timeoutMs = 6_000)
            if (!resp.isSuccess) {
                emptyList()
            } else {
                val arr = JSONObject(resp.body).optJSONObject("similartracks")?.optJSONArray("track")
                if (arr == null) {
                    emptyList()
                } else {
                    buildList {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val t = o.optString("name").trim()
                            val a = o.optJSONObject("artist")?.optString("name").orEmpty().trim()
                            if (a.isNotBlank() && t.isNotBlank()) add(AiTrackSuggestion(a, t))
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

    private suspend fun fetchLastFmSimilarArtists(
        apiKey: String,
        artist: String,
        limit: Int,
    ): List<String> =
        try {
            val url =
                "https://ws.audioscrobbler.com/2.0/?method=artist.getsimilar" +
                    "&artist=${CustomHttp.urlEncode(artist)}" +
                    "&limit=$limit&api_key=${CustomHttp.urlEncode(apiKey)}&format=json"
            val resp = CustomHttp.get(url, timeoutMs = 6_000)
            if (!resp.isSuccess) {
                emptyList()
            } else {
                val arr = JSONObject(resp.body).optJSONObject("similarartists")?.optJSONArray("artist")
                if (arr == null) {
                    emptyList()
                } else {
                    buildList {
                        for (i in 0 until arr.length()) {
                            val name = arr.optJSONObject(i)?.optString("name")?.trim().orEmpty()
                            if (name.isNotBlank()) add(name)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

    private suspend fun fetchLastFmArtistTopTracks(
        apiKey: String,
        artist: String,
        limit: Int,
    ): List<AiTrackSuggestion> =
        try {
            val url =
                "https://ws.audioscrobbler.com/2.0/?method=artist.gettoptracks" +
                    "&artist=${CustomHttp.urlEncode(artist)}" +
                    "&limit=$limit&api_key=${CustomHttp.urlEncode(apiKey)}&format=json"
            val resp = CustomHttp.get(url, timeoutMs = 6_000)
            if (!resp.isSuccess) {
                emptyList()
            } else {
                val arr = JSONObject(resp.body).optJSONObject("toptracks")?.optJSONArray("track")
                if (arr == null) {
                    emptyList()
                } else {
                    buildList {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val t = o.optString("name").trim()
                            val a = o.optJSONObject("artist")?.optString("name").orEmpty().trim().ifBlank { artist }
                            if (t.isNotBlank()) add(AiTrackSuggestion(a, t))
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

    private fun SongItem.primaryArtistKey(): String =
        CustomConfigStore.buildArtistKey(artists.joinToString(", ") { it.name }.trim())

    private fun diversifyWaveSongs(
        songs: List<SongItem>,
        cfg: CustomAppConfig,
    ): List<SongItem> {
        if (songs.size <= 2) return songs

        val likedIds = SongLikeState.likedSongIds
        val likedKeys =
            cfg.likedTracksBackup
                .mapTo(HashSet()) { CustomConfigStore.buildTrackKey(it.artist, it.title) }

        val fresh = mutableListOf<SongItem>()
        val familiar = mutableListOf<SongItem>()

        for (song in songs) {
            val artistStr = song.artists.joinToString(", ") { it.name }.trim()
            val tKey = CustomConfigStore.buildTrackKey(artistStr, song.title)
            val isLiked = song.id in likedIds || (tKey.isNotBlank() && tKey in likedKeys)
            if (isLiked) familiar.add(song) else fresh.add(song)
        }

        val orderedFresh = fresh.shuffled().toMutableList()

        val maxFamiliar =
            when (cfg.waveCharacter) {
                WaveCharacter.FAVORITE -> maxOf(3, songs.size / 2)
                WaveCharacter.DISCOVERY -> 0
                else -> maxOf(1, songs.size / 5)
            }
        val familiarPool = familiar.shuffled().take(maxFamiliar).toMutableList()

        val result = mutableListOf<SongItem>()

        fun canAddRespectingWindow(
            song: SongItem,
            forbidConsecutive: Boolean,
        ): Boolean {
            val key = song.primaryArtistKey()
            if (key.isBlank()) return true
            if (forbidConsecutive && result.lastOrNull()?.primaryArtistKey() == key) return false
            val recentWindow = result.takeLast(8)
            return recentWindow.count { it.primaryArtistKey() == key } < 2
        }

        fun pickNext(pool: MutableList<SongItem>): SongItem? {
            if (pool.isEmpty()) return null
            val strictIdx = pool.indexOfFirst { canAddRespectingWindow(it, forbidConsecutive = true) }
            if (strictIdx >= 0) return pool.removeAt(strictIdx)
            val windowIdx = pool.indexOfFirst { canAddRespectingWindow(it, forbidConsecutive = false) }
            if (windowIdx >= 0) return pool.removeAt(windowIdx)
            return null
        }

        while (orderedFresh.isNotEmpty() || familiarPool.isNotEmpty()) {
            var addedAny = false
            repeat(3) {
                pickNext(orderedFresh)?.let {
                    result.add(it)
                    addedAny = true
                }
            }
            pickNext(familiarPool)?.let {
                result.add(it)
                addedAny = true
            }
            if (!addedAny) break
        }

        // Если после жёсткого окна артистов остались треки и пачка получилась короткой — мягко добираем
        val leftovers = (orderedFresh + familiarPool).toMutableList()
        while (result.size < minOf(songs.size, 8) && leftovers.isNotEmpty()) {
            val nonConsecIdx = leftovers.indexOfFirst { it.primaryArtistKey() != result.lastOrNull()?.primaryArtistKey() }
            val picked = if (nonConsecIdx >= 0) leftovers.removeAt(nonConsecIdx) else leftovers.removeAt(0)
            result.add(picked)
        }

        return if (result.isNotEmpty()) result else songs.shuffled()
    }

    private fun youtubeFallbackThumbnail(videoId: String): String? {
        if (videoId.length != 11) return null
        if (videoId.startsWith("ym_") || videoId.startsWith("vk_") || videoId.startsWith("dz_") || videoId.startsWith("local_")) return null
        if (!videoId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

    private fun withFallbackCover(song: SongItem): SongItem {
        val cachedHd = ResolverDiagnostics.hdCoverFor(song.id)
        if (!cachedHd.isNullOrBlank()) return song.copy(thumbnail = cachedHd)
        if (!song.thumbnail.isNullOrBlank() && song.thumbnail != CustomMascotAssets.fallbackCoverUri) return song
        val ytThumb = youtubeFallbackThumbnail(song.id)
        if (!ytThumb.isNullOrBlank()) return song.copy(thumbnail = ytThumb)
        val fallback = CustomMascotAssets.fallbackCoverUri ?: return song
        return song.copy(thumbnail = fallback)
    }

    private fun LikedTrackBackup.toStarterSongItem(): SongItem? {
        if (title.isBlank() || id.isBlank()) return null
        SongLikeState.likedSongs
            .firstOrNull { it.id == id && !it.thumbnail.isNullOrBlank() && it.thumbnail != CustomMascotAssets.fallbackCoverUri }
            ?.let { return it }
        val parsedArtists =
            CustomConfigStore
                .splitArtistNames(artist)
                .map { ArtistTiny(id = null, name = it) }
                .ifEmpty {
                    artist
                        .trim()
                        .takeIf { it.isNotBlank() }
                        ?.let { listOf(ArtistTiny(id = null, name = it)) }
                        ?: listOf(ArtistTiny(id = null, name = "Неизвестный"))
                }
        val resolvedThumbnail =
            thumbnail?.takeIf { it.isNotBlank() && it != CustomMascotAssets.fallbackCoverUri }
                ?: ResolverDiagnostics.hdCoverFor(id)
                ?: youtubeFallbackThumbnail(id)
                ?: CustomMascotAssets.fallbackCoverUri
        return SongItem(
            id = id,
            title = title,
            thumbnail = resolvedThumbnail,
            artists = parsedArtists,
            album = null,
        )
    }

    private fun buildWavePrompt(
        cfg: CustomAppConfig,
        seedSong: SongItem?,
        profile: StructuredTasteProfile,
        pivotVibe: Boolean,
    ): String =
        buildString {
            if (seedSong != null) {
                val seedArtist = seedSong.artists.joinToString(", ") { it.name }.trim()
                appendLine("Собери радио-волну, развивающую трек: \"$seedArtist — ${seedSong.title}\".")
                appendLine("Сохраняй то же звучание, темп (BPM), бас, подачу вокала и настроение, но добавляй органичные свежие треки.")
            } else {
                appendLine("Ты — точный музыкальный куратор. Собери персональную волну, которая звучит как единый цельный плейлист по актуальному вкусу пользователя.")
                appendLine("Характер волны: ${cfg.waveCharacter.promptHint}.")
                appendLine("Эпоха: ${cfg.waveEra.promptHint}.")
                appendLine("Язык: ${cfg.waveLanguage.promptHint}.")
                appendLine("Настроение: ${cfg.waveMood.promptHint}.")
            }

            if (pivotVibe) {
                appendLine()
                appendLine("ВАЖНЫЙ ПОВОРОТ НАСТРОЕНИЯ: пользователь только что пропустил несколько треков подряд. Смени темп, энергетику и поджанр на ДРУГОЙ срез вкуса пользователя из его профиля (сохраняя заданные фильтры языка/настроения).")
            }

            if (profile.sessionPositiveTracks.isNotEmpty() && !pivotVibe) {
                appendLine()
                appendLine("ПРЯМО СЕЙЧАС В ЭТОЙ СЕССИИ ПОЛЬЗОВАТЕЛЮ ЗАШЛИ (держи похожий вайб, плотность баса и подачу): ${profile.sessionPositiveTracks.joinToString("; ")}.")
            }

            if (profile.isNotEmpty) {
                appendLine()
                appendLine("АКТУАЛЬНЫЙ ПРОФИЛЬ ВКУСА (используй как точный эталон поджанра, продакшена и энергии, НЕ копируй эти треки обратно):")
                if (profile.coreRecentTracks.isNotEmpty()) {
                    appendLine("• Свежие любимые и недавние треки: ${profile.coreRecentTracks.joinToString("; ")}.")
                }
                if (profile.topArtists.isNotEmpty()) {
                    appendLine("• Ядро любимых артистов: ${profile.topArtists.joinToString(", ")}.")
                }
                if (profile.extraContextTracks.isNotEmpty()) {
                    appendLine("• Дополнительный контекст вкуса: ${profile.extraContextTracks.joinToString("; ")}.")
                }
            }

            appendLine()
            when (cfg.waveCharacter) {
                WaveCharacter.FAVORITE -> {
                    appendLine("Баланс: около 45% сильных треков (включая редкие/альбомные) от артистов из ядра и близкой сцены + 55% новых треков того же звучания.")
                }
                WaveCharacter.DISCOVERY -> {
                    appendLine("ВАЖНО: 100% треков должны быть новыми открытиями из той же музыкальной сцены и поджанра. НЕ бери треки и главных артистов из ядра.")
                }
                else -> {
                    appendLine("ВАЖНО: минимум 75% подборки должны составлять НОВЫЕ треки и артисты из ТОЙ ЖЕ сцены, поджанра и уровня продакшена. НЕ копируй треки из списка профиля.")
                }
            }

            if (cfg.waveLanguage == WaveLanguage.ANY) {
                appendLine("По языку: органично добавляй 35–45% зарубежных треков ТОЛЬКО того же поджанра, энергии и звучания (если в ядре рэп/трэп/фонк/клуб — давай близкий зарубежный рэп/трэп/фонк/электронику, а не случайный поп, рок или инди).")
            }

            appendLine("Не добавляй случайную попсу или треки из несовместимых жанров. Каждый трек должен органично переходить в следующий.")
            appendLine("Только реальные существующие студийные треки — БЕЗ slowed/reverb, sped up, nightcore, tiktok-ремиксов, 8D, каверов и караоке.")
            appendLine("Не ставь одного артиста подряд и максимум 2 трека одного артиста в пачке.")
            appendFeedbackHints(cfg, includeSessionSkips = true)
        }

    private fun StringBuilder.appendFeedbackHints(
        cfg: CustomAppConfig,
        includeSessionSkips: Boolean,
    ) {
        val mutedArtists = cfg.artistReputation.filterValues { it.isAutoMuted }.keys.take(15)
        if (mutedArtists.isNotEmpty()) {
            appendLine("Исключи артистов, которых пользователь системно дизлайкал: ${mutedArtists.joinToString(", ")}.")
        }
        val lowWeightArtists = cfg.artistReputation.filterValues { it.isLowWeight }.keys.take(12)
        if (lowWeightArtists.isNotEmpty()) {
            appendLine("Добавляй редко (не больше 1 трека и только лучшие хиты): ${lowWeightArtists.joinToString(", ")}.")
        }
        if (includeSessionSkips) {
            val now = System.currentTimeMillis()
            val recentSkips =
                sessionSkippedUntilMs
                    .filterValues { it > now }
                    .keys
                    .mapNotNull { sessionSkippedLabels[it] }
                    .take(8)
            if (recentSkips.isNotEmpty()) {
                appendLine("Недавние пропуски в этой сессии (сейчас не под настроение, избегай такого звучания): ${recentSkips.joinToString("; ")}.")
            }
        }
    }

    private fun filterSuggestionsByFeedback(
        suggestions: List<AiTrackSuggestion>,
        respectSessionSkips: Boolean = true,
        respectPlayedCooldown: Boolean = true,
    ): List<AiTrackSuggestion> =
        suggestions.filter { item ->
            !configStore.isTrackDisliked(item.artist, item.title) &&
                !configStore.isArtistAutoMuted(item.artist) &&
                (!respectSessionSkips || !isSessionSkipped(item.artist, item.title)) &&
                (!respectPlayedCooldown || !configStore.isTrackInRecentCooldown(item.artist, item.title))
        }

    private fun filterSongsByFeedback(
        songs: List<SongItem>,
        respectSessionSkips: Boolean = true,
        respectPlayedCooldown: Boolean = false,
    ): List<SongItem> =
        songs.filter { song ->
            val artist = song.artists.joinToString(", ") { it.name }.trim()
            !configStore.isTrackDisliked(artist, song.title) &&
                !configStore.isArtistAutoMuted(artist) &&
                (!respectSessionSkips || !isSessionSkipped(artist, song.title)) &&
                (!respectPlayedCooldown || !configStore.isTrackInRecentCooldown(artist, song.title))
        }

    /**
     * Единая модель вкуса строго на локальной базе (без истории YouTube):
     * локальные лайки + локальный топ артистов + сигналы текущей сессии + фоновый externalTasteProfile + Last.fm.
     */
    private suspend fun collectStructuredTasteProfile(cfg: CustomAppConfig): StructuredTasteProfile =
        withContext(Dispatchers.IO) {
            val coreRecent = LinkedHashSet<String>()
            val artistScores = HashMap<String, Double>()
            val artistDisplayNames = HashMap<String, String>()

            fun bumpArtist(
                rawArtist: String,
                weight: Double,
            ) {
                CustomConfigStore.splitArtistNames(rawArtist).forEach { single ->
                    val norm = CustomConfigStore.buildArtistKey(single)
                    if (norm.isBlank()) return@forEach
                    val rep = cfg.artistReputation[norm]
                    if (rep?.isAutoMuted == true || rep?.isLowWeight == true) return@forEach
                    artistScores[norm] = (artistScores[norm] ?: 0.0) + weight
                    artistDisplayNames.putIfAbsent(norm, single)
                }
            }

            fun addCore(
                artist: String,
                title: String,
                artistWeight: Double,
            ) {
                val cleanTitle = title.trim()
                val cleanArtist = artist.trim()
                if (cleanTitle.isBlank()) return
                if (configStore.isTrackDisliked(cleanArtist, cleanTitle)) return
                if (cleanArtist.isNotBlank() && configStore.isArtistAutoMuted(cleanArtist)) return
                val label = if (cleanArtist.isNotBlank()) "$cleanArtist - $cleanTitle" else cleanTitle
                if (coreRecent.size < 14) coreRecent.add(label)
                if (cleanArtist.isNotBlank()) bumpArtist(cleanArtist, artistWeight)
            }

            val now = System.currentTimeMillis()
            cfg.localArtistStats.forEach { (norm, stat) ->
                val rep = cfg.artistReputation[norm]
                if (rep?.isAutoMuted == true || rep?.isLowWeight == true) return@forEach
                artistScores[norm] = (artistScores[norm] ?: 0.0) + stat.rankScore(now)
                artistDisplayNames.putIfAbsent(norm, stat.displayName)
            }

            val recentLocalLikes =
                SongLikeState.likedSongs
                    .sortedByDescending { it.libraryAddedAt ?: 0L }
            recentLocalLikes.take(12).forEach { song ->
                val art = song.artists.joinToString(", ") { it.name }.trim()
                addCore(art, song.title, artistWeight = 5.0)
            }
            recentLocalLikes.drop(12).take(80).forEach { song ->
                val art = song.artists.joinToString(", ") { it.name }.trim()
                if (art.isNotBlank()) bumpArtist(art, weight = 2.0)
            }

            if (cfg.lastFmUsername.isNotBlank() && LastFM.apiKeyConfigured()) {
                try {
                    val recent = LastFM.getRecentTracks(GlobalYouTube.client, cfg.lastFmUsername.trim(), limit = 10).getOrNull()
                    if (recent != null) {
                        val root = JSONObject(recent.toString())
                        val trackArr = root.optJSONObject("recenttracks")?.optJSONArray("track") ?: root.optJSONArray("track")
                        if (trackArr != null) {
                            for (i in 0 until trackArr.length()) {
                                val obj = trackArr.optJSONObject(i) ?: continue
                                val name = obj.optString("name").trim()
                                val art = obj.optJSONObject("artist")?.optString("#text").orEmpty().trim()
                                addCore(art, name, artistWeight = 4.0)
                            }
                        }
                    }
                    val top = LastFM.getTopTracks(GlobalYouTube.client, cfg.lastFmUsername.trim(), period = "1month", limit = 10).getOrNull()
                    top?.forEach { t ->
                        val art = t.artist?.name?.trim().orEmpty()
                        val name = t.name.trim()
                        if (art.isNotBlank()) bumpArtist(art, weight = 4.0)
                        if (coreRecent.size < 14) addCore(art, name, artistWeight = 2.0)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }

            cfg.likedTracksBackup.take(15).forEach { liked ->
                addCore(liked.artist, liked.title, artistWeight = 3.0)
            }
            cfg.likedTracksBackup.take(220).forEach { liked ->
                if (liked.artist.isNotBlank()) bumpArtist(liked.artist, weight = 1.0)
            }

            cfg.externalTasteProfile.take(250).forEach { ext ->
                if (ext.artist.isNotBlank()) bumpArtist(ext.artist, weight = 0.8)
                if (coreRecent.size < 10) addCore(ext.artist, ext.title, artistWeight = 0.5)
            }

            cfg.artistReputation.forEach { (normArtist, rep) ->
                if (!rep.isAutoMuted && !rep.isLowWeight && rep.likes > 0) {
                    val delta = (rep.likes * 3.0 - rep.dislikes * 2.0).coerceAtLeast(0.0)
                    if (delta > 0.0) {
                        artistScores[normArtist] = (artistScores[normArtist] ?: 0.0) + delta
                        artistDisplayNames.putIfAbsent(normArtist, normArtist)
                    }
                }
            }

            val topArtists =
                artistScores.entries
                    .sortedByDescending { it.value }
                    .take(14)
                    .mapNotNull { artistDisplayNames[it.key] }

            val extraContextPool =
                (cfg.likedTracksBackup.drop(15) + cfg.externalTasteProfile)
                    .shuffled()
                    .asSequence()
                    .filter { it.title.isNotBlank() && !configStore.isTrackDisliked(it.artist, it.title) }
                    .map { if (it.artist.isNotBlank()) "${it.artist} - ${it.title}" else it.title }
                    .filter { it !in coreRecent }
                    .distinct()
                    .take(6)
                    .toList()

            val sessionPositives = synchronized(sessionLock) { sessionPositiveLabels.take(5) }

            StructuredTasteProfile(
                coreRecentTracks = coreRecent.take(14),
                topArtists = topArtists,
                extraContextTracks = extraContextPool,
                sessionPositiveTracks = sessionPositives,
            )
        }

    /**
     * Строгий каскадный резолв рекомендаций без мусорного `firstOrNull()`:
     * - Зарубежные: YTM → Deezer → VK → Яндекс
     * - Русские без цензуры: Яндекс → VK → Deezer → YTM
     * - Русские цензура/Explicit: VK → Яндекс → Deezer → YTM
     */
    private suspend fun resolveSuggestionsToSongs(
        suggestions: List<AiTrackSuggestion>,
        maxResults: Int = DEFAULT_WAVE_BATCH_SIZE,
    ): List<SongItem> =
        coroutineScope {
            val found =
                suggestions.take(maxResults + 6).chunked(5).flatMap { batch ->
                    batch.map { item ->
                        async(Dispatchers.IO) {
                            resolveSingleSuggestionCascade(item)
                        }
                    }.awaitAll()
                }
            found
                .filterNotNull()
                .distinctBy { song ->
                    val art = song.artists.joinToString(", ") { it.name }.trim()
                    CustomConfigStore.buildTrackKey(art, song.title).ifBlank { song.id }
                }.take(maxResults)
        }

    private suspend fun resolveSingleSuggestionCascade(item: AiTrackSuggestion): SongItem? {
        val artist = item.artist.trim()
        val title = item.title.trim()
        if (title.isBlank()) return null

        val cfg = configStore.current
        val deezer = HybridStreamResolver.getDeezerClient()
        val yandex = HybridStreamResolver.getYandexProvider()
        val vk = HybridStreamResolver.getVkProvider()

        val isRu = LocalCensorshipAdvisor.isRussianTrack(artist, title)
        val isCensored =
            cfg.preferVkForExplicit &&
                (
                    configStore.getCachedCensorship(artist, title) == true ||
                        configStore.isArtistLearnedCensored(artist) ||
                        LocalCensorshipAdvisor.isLikelyCensoredOnYandex(artist, title)
                )

        val deezerTrack =
            try {
                deezer?.searchCatalogTrack(artist, title)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        val effectiveRuCensored =
            isCensored || (isRu && cfg.preferVkForExplicit && deezerTrack?.isExplicit == true)
        val targetDur = deezerTrack?.durationSec
        val hdCover = deezerTrack?.coverUrl

        val cascade: List<String> =
            when {
                !isRu -> listOf("ytm", "deezer", "vk", "yandex")
                effectiveRuCensored -> listOf("vk", "yandex", "deezer", "ytm")
                else -> listOf("yandex", "vk", "deezer", "ytm")
            }

        for (source in cascade) {
            val song: SongItem? =
                when (source) {
                    "deezer" -> {
                        deezerTrack?.toSongItem()
                    }
                    "yandex" -> {
                        if (cfg.yandexToken.isBlank() || yandex == null) {
                            null
                        } else {
                            try {
                                yandex.searchCatalogTrack(
                                    artist = artist,
                                    title = title,
                                    targetDurationSec = targetDur,
                                    rejectSuspectedCensored = !effectiveRuCensored && cfg.vkToken.isNotBlank(),
                                )?.toSongItem()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                    "vk" -> {
                        if (cfg.vkToken.isBlank() || vk == null) {
                            null
                        } else {
                            try {
                                vk.searchCatalogTrack(
                                    artist = artist,
                                    title = title,
                                    targetDurationSec = targetDur,
                                )?.toSongItem()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                    "ytm" -> {
                        searchYouTubeStrict(item, targetDurationSec = targetDur)
                    }
                    else -> null
                }

            if (song != null) {
                val bestCover = hdCover ?: song.thumbnail?.takeUnless { it == CustomMascotAssets.fallbackCoverUri }
                TrackBindingCache.put(song.id, artist, title) { prev ->
                    prev.copy(
                        deezerId = deezerTrack?.externalId?.removePrefix("dz_") ?: prev.deezerId,
                        yandexId = if (song.id.startsWith("ym_")) song.id.removePrefix("ym_") else prev.yandexId,
                        vkAudioKey = if (song.id.startsWith("vk_")) song.id.removePrefix("vk_") else prev.vkAudioKey,
                        youtubeId = if (!song.id.startsWith("ym_") && !song.id.startsWith("vk_") && !song.id.startsWith("dz_")) song.id else prev.youtubeId,
                        hdCoverUrl = bestCover ?: prev.hdCoverUrl,
                        durationSec = targetDur ?: song.duration?.toLong() ?: prev.durationSec,
                        isExplicit = (deezerTrack?.isExplicit == true) || song.isExplicit || prev.isExplicit,
                    )
                }
                return if (!bestCover.isNullOrBlank()) song.copy(thumbnail = bestCover) else song
            }
        }
        return null
    }

    private suspend fun searchYouTubeStrict(
        suggestion: AiTrackSuggestion,
        targetDurationSec: Long? = null,
    ): SongItem? =
        try {
            val q = "${suggestion.artist} ${suggestion.title}".trim()
            val page =
                GlobalYouTubeRepository.instance.searchFiltered(
                    query = q,
                    filter = SearchFilter.SONG,
                    allowOfflineFallback = false,
                )
            pickBestSongStrict(page.items.filterIsInstance<SongItem>(), suggestion, targetDurationSec)
        } catch (e: CancellationException) {
            throw e
        } catch (err: Exception) {
            Log.d(TAG, "YTM search failed for ${suggestion.artist} - ${suggestion.title}: ${err.message}")
            null
        }

    /** Строгий отбор без `firstOrNull()` — если трек не совпал, отбрасываем его. */
    private fun pickBestSongStrict(
        candidates: List<SongItem>,
        suggestion: AiTrackSuggestion,
        targetDurationSec: Long? = null,
    ): SongItem? {
        val playable = candidates.filter { !it.isUnavailable && it.id.isNotBlank() }
        if (playable.isEmpty()) return null
        val targetLower = "${suggestion.artist} ${suggestion.title}".lowercase()

        return playable
            .mapNotNull { song ->
                val art = song.artists.joinToString(", ") { it.name }
                val candLower = "$art ${song.title}".lowercase()
                if (hasUnwantedVersionTag(candLower, targetLower)) {
                    return@mapNotNull null
                }
                val durationSec = song.duration?.toLong()
                if (durationSec != null && durationSec in 1L..45L) {
                    return@mapNotNull null
                }
                if (targetDurationSec != null && durationSec != null &&
                    !TrackMatcher.isDurationAcceptable(targetDurationSec, durationSec, toleranceSec = 8L)
                ) {
                    return@mapNotNull null
                }

                val score =
                    TrackMatcher.matchScore(
                        targetArtist = suggestion.artist,
                        targetTitle = suggestion.title,
                        targetDurationSec = targetDurationSec,
                        candidateArtist = art,
                        candidateTitle = song.title,
                        candidateDurationSec = durationSec,
                    )
                if (score >= 54) song to score else null
            }.maxByOrNull { it.second }
            ?.first
    }

    private fun hasUnwantedVersionTag(
        candLower: String,
        targetLower: String,
    ): Boolean {
        if (UNWANTED_SUBSTRINGS.any { badTag -> candLower.contains(badTag) && !targetLower.contains(badTag) }) {
            return true
        }
        return UNWANTED_WORD_REGEXES.any { (_, regex) ->
            regex.containsMatchIn(candLower) && !regex.containsMatchIn(targetLower)
        }
    }

    companion object {
        private const val TAG = "AiWaveEngine"
        const val DEFAULT_WAVE_BATCH_SIZE = 14
        private const val MIN_WAVE_BATCH_SIZE = 12
        private const val MAX_WAVE_BATCH_SIZE = 18
        private const val SESSION_SKIP_COOLDOWN_MS = 210 * 60 * 1_000L // 3.5 часа
        private const val AUTO_PIVOT_SKIP_THRESHOLD = 3
        private const val AUTO_PIVOT_COOLDOWN_MS = 75_000L

        private val UNWANTED_SUBSTRINGS =
            listOf(
                "slowed",
                "sped up",
                "speed up",
                "nightcore",
                "reverb",
                "8d audio",
                "karaoke",
                "караоке",
                "instrumental",
                "clean version",
                "radio edit",
                "censored",
                "цензур",
                "live at",
                "acoustic",
                "bass boosted",
            )

        private val UNWANTED_WORD_REGEXES =
            listOf("cover", "кавер", "минус", "minus").map { word ->
                word to Regex("(?<![a-zа-яё0-9])${Regex.escape(word)}(?![a-zа-яё0-9])")
            }

        @Volatile
        var sharedInstance: AiWaveEngine? = null
            private set

        fun isPlaylistLikeQuery(rawQuery: String): Boolean {
            val q = rawQuery.trim().lowercase()
            if (q.isBlank()) return false
            val keywords =
                listOf(
                    "плейлист",
                    "подборка",
                    "волна",
                    "микс",
                    "радио",
                    "атмосфер",
                    "настроен",
                    "в машину",
                    "для машины",
                    "под ночь",
                    "ночной",
                    "тренировк",
                    "кач",
                    "playlist",
                )
            if (keywords.any { q.contains(it) }) return true
            val words = q.split(Regex("\\s+")).filter { it.isNotBlank() }
            return words.size >= 4 && !q.contains(" - ") && !q.contains(" — ")
        }
    }
}
