package com.metrolist.android.custom

import android.util.Log
import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.screens.CustomHomeShelvesBridge
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

class AiWaveEngine(
    private val configStore: CustomConfigStore,
    private val geminiClient: GeminiClient,
) {
    private val waveMutex = Mutex()
    private val sessionSkippedUntilMs = ConcurrentHashMap<String, Long>()
    private val sessionSkippedLabels = ConcurrentHashMap<String, String>()

    private val _isGeneratingWave = MutableStateFlow(false)
    val isGeneratingWave: StateFlow<Boolean> = _isGeneratingWave.asStateFlow()

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    init {
        sharedInstance = this
    }

    fun buildInstantStarterSongs(
        excludeIds: Set<String> = emptySet(),
        limit: Int = 8,
    ): List<SongItem> {
        val cfg = configStore.current

        val freshPool =
            filterSongsByFeedback(
                cfg.lastWaveTracks.mapNotNull { it.toStarterSongItem() } +
                    CustomHomeShelvesBridge.shelves
                        .flatMap { it.items }
                        .filterIsInstance<SongItem>(),
            ).filter { it.id.isNotBlank() && it.id !in excludeIds }
                .distinctBy { it.id }
                .shuffled()

        val likedPool =
            filterSongsByFeedback(
                cfg.likedTracksBackup.shuffled().take(12).mapNotNull { it.toStarterSongItem() },
            ).filter { it.id.isNotBlank() && it.id !in excludeIds }
                .distinctBy { it.id }

        if (freshPool.isEmpty()) {
            return likedPool.take(limit)
        }

        val result = mutableListOf<SongItem>()
        val usedIds = HashSet<String>()
        var freshIdx = 0
        var likedIdx = 0

        while (result.size < limit && (freshIdx < freshPool.size || likedIdx < minOf(2, likedPool.size))) {
            repeat(2) {
                if (result.size < limit && freshIdx < freshPool.size) {
                    val s = freshPool[freshIdx++]
                    if (usedIds.add(s.id)) result.add(s)
                }
            }
            if (result.size < limit && likedIdx < minOf(2, likedPool.size)) {
                val s = likedPool[likedIdx++]
                if (usedIds.add(s.id)) result.add(s)
            }
            if (freshIdx >= freshPool.size && likedIdx >= minOf(2, likedPool.size)) break
        }
        return result
    }

    fun recordSessionSkip(song: SongItem) {
        val artist = song.artists.joinToString(", ") { it.name }.trim()
        val key = CustomConfigStore.buildTrackKey(artist, song.title)
        if (key.isBlank()) return
        sessionSkippedUntilMs[key] = System.currentTimeMillis() + SESSION_SKIP_COOLDOWN_MS
        sessionSkippedLabels[key] = if (artist.isNotBlank()) "$artist — ${song.title}" else song.title
        if (sessionSkippedLabels.size > 20) {
            val firstKey = sessionSkippedLabels.keys.firstOrNull()
            if (firstKey != null) sessionSkippedLabels.remove(firstKey)
        }
    }

    fun recordDislike(song: SongItem) {
        val artist = song.artists.joinToString(", ") { it.name }.trim()
        configStore.dislikeTrack(artist = artist, title = song.title)
        recordSessionSkip(song)
    }

    fun recordLike(song: SongItem) {
        val artist = song.artists.joinToString(", ") { it.name }.trim()
        configStore.likeTrack(artist = artist, title = song.title, songId = song.id)
        val key = CustomConfigStore.buildTrackKey(artist, song.title)
        sessionSkippedUntilMs.remove(key)
        sessionSkippedLabels.remove(key)
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

    suspend fun generateWaveSongs(
        seedSong: SongItem? = null,
        count: Int = maxOf(configStore.current.aiPlaylistSize, 40),
        backgroundLabel: Boolean = false,
    ): Result<List<SongItem>> =
        waveMutex.withLock {
            _isGeneratingWave.value = true
            _statusText.value =
                when {
                    seedSong != null -> "Собираю волну от «${seedSong.title}»…"
                    backgroundLabel -> "Подбираю свежую волну в фоне…"
                    else -> "Подбираю волну…"
                }
            try {
                val cfg = configStore.current
                val tasteSummary = collectTasteSummary(cfg)
                val targetCount = count.coerceIn(35, 50)
                val prompt = buildWavePrompt(cfg = cfg, seedSong = seedSong, tasteSummary = tasteSummary)

                geminiClient
                    .generateTrackList(prompt = prompt, count = targetCount)
                    .mapCatching { suggestions ->
                        val filtered = filterSuggestionsByFeedback(suggestions)
                        val resolved = resolveSuggestionsToSongs(filtered, maxResults = targetCount)
                        val cleanResolved =
                            diversifyWaveSongs(
                                songs = filterSongsByFeedback(resolved).map(::withFallbackCover),
                                cfg = cfg,
                            )
                        check(cleanResolved.isNotEmpty()) { "Не удалось найти ни одного трека в каталоге" }
                        val savedWave =
                            cleanResolved.map { song ->
                                LikedTrackBackup(
                                    id = song.id,
                                    artist = song.artists.joinToString(", ") { it.name }.trim(),
                                    title = song.title,
                                )
                            }
                        configStore.saveLastWaveTracks(savedWave)
                        cleanResolved
                    }.onSuccess {
                        _statusText.value = null
                    }.onFailure { err ->
                        _statusText.value = if (backgroundLabel) null else (err.message ?: "Ошибка генерации волны")
                    }
            } finally {
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
        val tasteSummary = collectTasteSummary(cfg)
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
                    if (tasteSummary.isNotBlank()) {
                        appendLine("Дополнительный контекст вкуса пользователя: $tasteSummary.")
                    }
                    if (index > 0 && allSuggestions.isNotEmpty()) {
                        val exclude = allSuggestions.takeLast(30).joinToString("; ") { "${it.artist} - ${it.title}" }
                        appendLine("НЕ повторяй уже предложенные треки: $exclude.")
                    }
                    appendFeedbackHints(cfg, includeSessionSkips = false)
                    appendLine("Подбери треки, которые точно попадают в этот запрос, эпоху, стиль или настроение.")
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

        val filtered = filterSuggestionsByFeedback(allSuggestions, respectSessionSkips = false)
        val resolved = resolveSuggestionsToSongs(filtered, maxResults = targetCount)
        val cleanResolved = filterSongsByFeedback(resolved, respectSessionSkips = false).map(::withFallbackCover)
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

        val maxFamiliar =
            when (cfg.waveCharacter) {
                WaveCharacter.FAVORITE -> maxOf(4, songs.size / 2)
                WaveCharacter.DISCOVERY -> 0
                else -> maxOf(2, songs.size / 5)
            }
        val freshPool = fresh.shuffled().toMutableList()
        val familiarPool = familiar.shuffled().take(maxFamiliar).toMutableList()

        val result = mutableListOf<SongItem>()

        fun canAddRespectingWindow(song: SongItem, forbidConsecutive: Boolean): Boolean {
            val key = song.primaryArtistKey()
            if (key.isBlank()) return true
            if (forbidConsecutive && result.lastOrNull()?.primaryArtistKey() == key) return false
            val recentNine = result.takeLast(9)
            return recentNine.count { it.primaryArtistKey() == key } < 2
        }

        fun pickNext(pool: MutableList<SongItem>): SongItem? {
            if (pool.isEmpty()) return null
            val strictIdx = pool.indexOfFirst { canAddRespectingWindow(it, forbidConsecutive = true) }
            if (strictIdx >= 0) return pool.removeAt(strictIdx)
            val windowIdx = pool.indexOfFirst { canAddRespectingWindow(it, forbidConsecutive = false) }
            if (windowIdx >= 0) return pool.removeAt(windowIdx)
            return null
        }

        // Чередуем 3 новых + 1 знакомый, соблюдая лимит: до 2 треков одного артиста на каждые 10
        while (freshPool.isNotEmpty() || familiarPool.isNotEmpty()) {
            var addedAny = false
            repeat(3) {
                pickNext(freshPool)?.let {
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
        return if (result.isNotEmpty()) result else songs.shuffled()
    }

    private fun withFallbackCover(song: SongItem): SongItem {
        if (!song.thumbnail.isNullOrBlank()) return song
        val fallback = CustomMascotAssets.fallbackCoverUri ?: return song
        return song.copy(thumbnail = fallback)
    }

    private fun LikedTrackBackup.toStarterSongItem(): SongItem? {
        if (title.isBlank() || id.isBlank()) return null
        val parsedArtists =
            artist
                .split(",", "&")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { ArtistTiny(id = null, name = it) }
                .ifEmpty { listOf(ArtistTiny(id = null, name = "Неизвестный")) }
        return SongItem(
            id = id,
            title = title,
            thumbnail = CustomMascotAssets.fallbackCoverUri,
            artists = parsedArtists,
            album = null,
        )
    }

    private fun buildWavePrompt(
        cfg: CustomAppConfig,
        seedSong: SongItem?,
        tasteSummary: String,
    ): String =
        buildString {
            if (seedSong != null) {
                val seedArtist = seedSong.artists.joinToString(", ") { it.name }.trim()
                appendLine("Собери радио-волну, развивающую трек: \"$seedArtist — ${seedSong.title}\".")
                appendLine("Сохраняй близкое звучание, темп и настроение, но добавляй свежие и органичные треки.")
            } else {
                appendLine("Собери персональную музыкальную волну для пользователя.")
                appendLine("Характер волны: ${cfg.waveCharacter.promptHint}.")
                appendLine("Эпоха: ${cfg.waveEra.promptHint}.")
                appendLine("Язык: ${cfg.waveLanguage.promptHint}.")
                appendLine("Настроение: ${cfg.waveMood.promptHint}.")
            }
            if (tasteSummary.isNotBlank()) {
                appendLine("Ориентир вкуса пользователя (НЕ копируй эти треки обратно в ответ, используй только как понимание стиля): $tasteSummary.")
            }
            when (cfg.waveCharacter) {
                WaveCharacter.FAVORITE -> {
                    appendLine("Смешай примерно 50% близких хитов любимых артистов и 50% новых треков похожего вайба.")
                }
                WaveCharacter.DISCOVERY -> {
                    appendLine("ВАЖНО: 100% треков должны быть новыми для пользователя, НЕ бери треки и главных артистов из списка ориентиров.")
                }
                else -> {
                    appendLine("ВАЖНО: минимум 75% подборки должны составлять НОВЫЕ, свежие треки и другие артисты схожего вайба. НЕ повторяй треки из ориентира вкуса.")
                }
            }
            if (cfg.waveLanguage == WaveLanguage.ANY) {
                appendLine("По языку: смело добавляй 40–50% качественных зарубежных треков (хип-хоп, фонк, электроника, инди, альтернатива и т.п. в духе вкуса пользователя), не зацикливайся только на русских исполнителях.")
            }
            appendLine("Допускается до 2 треков одного артиста на каждые 10 треков подборки, но не ставь одного артиста подряд.")
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
                appendLine("Недавние пропуски в этой сессии (временно не под настроение, но без вечного бана артистов): ${recentSkips.joinToString("; ")}.")
            }
        }
    }

    private fun filterSuggestionsByFeedback(
        suggestions: List<AiTrackSuggestion>,
        respectSessionSkips: Boolean = true,
    ): List<AiTrackSuggestion> =
        suggestions.filter { item ->
            !configStore.isTrackDisliked(item.artist, item.title) &&
                !configStore.isArtistAutoMuted(item.artist) &&
                (!respectSessionSkips || !isSessionSkipped(item.artist, item.title))
        }

    private fun filterSongsByFeedback(
        songs: List<SongItem>,
        respectSessionSkips: Boolean = true,
    ): List<SongItem> =
        songs.filter { song ->
            val artist = song.artists.joinToString(", ") { it.name }.trim()
            !configStore.isTrackDisliked(artist, song.title) &&
                !configStore.isArtistAutoMuted(artist) &&
                (!respectSessionSkips || !isSessionSkipped(artist, song.title))
        }

    private suspend fun collectTasteSummary(cfg: CustomAppConfig): String =
        withContext(Dispatchers.IO) {
            val entries = mutableListOf<String>()

            if (cfg.lastFmUsername.isNotBlank() && LastFM.apiKeyConfigured()) {
                runCatching {
                    val top = LastFM.getTopTracks(GlobalYouTube.client, cfg.lastFmUsername.trim(), period = "1month", limit = 10).getOrNull()
                    top?.forEach { t ->
                        val art = t.artist?.name?.trim().orEmpty()
                        val name = t.name.trim()
                        if (name.isNotBlank()) entries += if (art.isNotBlank()) "$art - $name" else name
                    }
                    val recent = LastFM.getRecentTracks(GlobalYouTube.client, cfg.lastFmUsername.trim(), limit = 10).getOrNull()
                    if (recent != null) {
                        val root = JSONObject(recent.toString())
                        val trackArr = root.optJSONObject("recenttracks")?.optJSONArray("track") ?: root.optJSONArray("track")
                        if (trackArr != null) {
                            for (i in 0 until trackArr.length()) {
                                val obj = trackArr.optJSONObject(i) ?: continue
                                val name = obj.optString("name").trim()
                                val art = obj.optJSONObject("artist")?.optString("#text").orEmpty().trim()
                                if (name.isNotBlank()) entries += if (art.isNotBlank()) "$art - $name" else name
                            }
                        }
                    }
                }
            }

            if (entries.size < 8 && GlobalYouTubeRepository.instance.isYouTubeHistoryAvailable()) {
                runCatching {
                    val history = GlobalYouTubeRepository.instance.getHistory().take(10)
                    history.forEach { entry ->
                        val art = entry.song.artists.joinToString(", ") { it.name }.trim()
                        val title = entry.song.title.trim()
                        if (title.isNotBlank()) entries += if (art.isNotBlank()) "$art - $title" else title
                    }
                }
            }

            if (entries.size < 14 && cfg.likedTracksBackup.isNotEmpty()) {
                cfg.likedTracksBackup.shuffled().take(14).forEach { liked ->
                    if (liked.title.isNotBlank()) {
                        entries += if (liked.artist.isNotBlank()) "${liked.artist} - ${liked.title}" else liked.title
                    }
                }
            }

            entries.distinct().shuffled().take(18).joinToString("; ")
        }

    private suspend fun resolveSuggestionsToSongs(
        suggestions: List<AiTrackSuggestion>,
        maxResults: Int = 50,
    ): List<SongItem> =
        coroutineScope {
            val found =
                suggestions.take(maxResults).chunked(6).flatMap { batch ->
                    batch.map { item ->
                        async(Dispatchers.IO) {
                            runCatching {
                                val q = "${item.artist} ${item.title}".trim()
                                val page =
                                    GlobalYouTubeRepository.instance.searchFiltered(
                                        query = q,
                                        filter = SearchFilter.SONG,
                                        allowOfflineFallback = false,
                                    )
                                pickBestSong(page.items.filterIsInstance<SongItem>(), item)
                            }.onFailure { err ->
                                Log.d(TAG, "Search failed for ${item.artist} - ${item.title}: ${err.message}")
                            }.getOrNull()
                        }
                    }.awaitAll()
                }
            found.filterNotNull().distinctBy { it.id }
        }

    private fun pickBestSong(
        candidates: List<SongItem>,
        suggestion: AiTrackSuggestion,
    ): SongItem? {
        val playable = candidates.filter { !it.isUnavailable && it.id.isNotBlank() }
        if (playable.isEmpty()) return null
        return playable
            .mapNotNull { song ->
                val art = song.artists.joinToString(", ") { it.name }
                val score =
                    TrackMatcher.matchScore(
                        targetArtist = suggestion.artist,
                        targetTitle = suggestion.title,
                        targetDurationSec = null,
                        candidateArtist = art,
                        candidateTitle = song.title,
                        candidateDurationSec = song.duration,
                    )
                if (score >= 45) song to score else null
            }.maxByOrNull { it.second }?.first
            ?: playable.firstOrNull()
    }

    companion object {
        private const val TAG = "AiWaveEngine"
        private const val SESSION_SKIP_COOLDOWN_MS = 3 * 60 * 60 * 1_000L

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
