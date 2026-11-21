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

private data class StructuredTasteProfile(
    val coreRecentTracks: List<String>,
    val topArtists: List<String>,
    val extraContextTracks: List<String>,
) {
    val isNotEmpty: Boolean
        get() = coreRecentTracks.isNotEmpty() || topArtists.isNotEmpty() || extraContextTracks.isNotEmpty()
}

class AiWaveEngine(
    private val configStore: CustomConfigStore,
    private val geminiClient: GeminiClient,
) {
    private val waveMutex = Mutex()
    private val sessionSkippedUntilMs = ConcurrentHashMap<String, Long>()
    private val sessionSkippedLabels = ConcurrentHashMap<String, String>()
    private val recentGeneratedKeys = LinkedHashSet<String>()

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
                CustomHomeShelvesBridge.shelves
                    .flatMap { it.items }
                    .filterIsInstance<SongItem>() +
                    cfg.lastWaveTracks.mapNotNull { it.toStarterSongItem() },
            ).filter { it.id.isNotBlank() && it.id !in excludeIds }
                .distinctBy { it.id }
                .shuffled()

        val recentLikedPool =
            filterSongsByFeedback(
                SongLikeState.likedSongs
                    .sortedByDescending { it.libraryAddedAt ?: 0L }
                    .take(20) +
                    cfg.likedTracksBackup.take(20).mapNotNull { it.toStarterSongItem() },
            ).filter { it.id.isNotBlank() && it.id !in excludeIds }
                .distinctBy { it.id }
                .shuffled()

        if (freshPool.isEmpty()) {
            return recentLikedPool.take(limit)
        }

        val result = mutableListOf<SongItem>()
        val usedIds = HashSet<String>()
        var freshIdx = 0
        var likedIdx = 0

        while (result.size < limit && (freshIdx < freshPool.size || likedIdx < minOf(2, recentLikedPool.size))) {
            repeat(2) {
                if (result.size < limit && freshIdx < freshPool.size) {
                    val s = freshPool[freshIdx++]
                    if (usedIds.add(s.id)) result.add(s)
                }
            }
            if (result.size < limit && likedIdx < minOf(2, recentLikedPool.size)) {
                val s = recentLikedPool[likedIdx++]
                if (usedIds.add(s.id)) result.add(s)
            }
            if (freshIdx >= freshPool.size && likedIdx >= minOf(2, recentLikedPool.size)) break
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
        val realThumb = song.thumbnail?.takeUnless { it == CustomMascotAssets.fallbackCoverUri }
        configStore.likeTrack(artist = artist, title = song.title, songId = song.id, thumbnail = realThumb)
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
                val profile = collectStructuredTasteProfile(cfg)
                val targetCount = count.coerceIn(35, 50)
                val prompt = buildWavePrompt(cfg = cfg, seedSong = seedSong, profile = profile)

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

                        rememberGeneratedTracks(cleanResolved)

                        val savedWave =
                            cleanResolved.map { song ->
                                LikedTrackBackup(
                                    id = song.id,
                                    artist = song.artists.joinToString(", ") { it.name }.trim(),
                                    title = song.title,
                                    thumbnail = song.thumbnail?.takeUnless { it == CustomMascotAssets.fallbackCoverUri },
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

    private fun rememberGeneratedTracks(songs: List<SongItem>) {
        synchronized(recentGeneratedKeys) {
            songs.forEach { song ->
                val art = song.artists.joinToString(", ") { it.name }.trim()
                val key = CustomConfigStore.buildTrackKey(art, song.title)
                if (key.isNotBlank()) recentGeneratedKeys.add(key)
            }
            while (recentGeneratedKeys.size > 120) {
                val first = recentGeneratedKeys.firstOrNull() ?: break
                recentGeneratedKeys.remove(first)
            }
        }
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
        val recentSet = synchronized(recentGeneratedKeys) { HashSet(recentGeneratedKeys) }

        val fresh = mutableListOf<SongItem>()
        val recentRepeats = mutableListOf<SongItem>()
        val familiar = mutableListOf<SongItem>()

        for (song in songs) {
            val artistStr = song.artists.joinToString(", ") { it.name }.trim()
            val tKey = CustomConfigStore.buildTrackKey(artistStr, song.title)
            val isLiked = song.id in likedIds || (tKey.isNotBlank() && tKey in likedKeys)
            when {
                isLiked -> familiar.add(song)
                tKey.isNotBlank() && tKey in recentSet -> recentRepeats.add(song)
                else -> fresh.add(song)
            }
        }

        val orderedFresh = (fresh.shuffled() + recentRepeats.shuffled()).toMutableList()

        val maxFamiliar =
            when (cfg.waveCharacter) {
                WaveCharacter.FAVORITE -> maxOf(4, songs.size / 2)
                WaveCharacter.DISCOVERY -> 0
                else -> maxOf(2, songs.size / 5)
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
        return if (result.isNotEmpty()) result else songs.shuffled()
    }

    private fun youtubeFallbackThumbnail(videoId: String): String? {
        if (videoId.length != 11) return null
        if (videoId.startsWith("ym_") || videoId.startsWith("vk_") || videoId.startsWith("local_")) return null
        if (!videoId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

    private fun withFallbackCover(song: SongItem): SongItem {
        if (!song.thumbnail.isNullOrBlank() && song.thumbnail != CustomMascotAssets.fallbackCoverUri) return song
        val realCover =
            ResolverDiagnostics.hdCoverFor(song.id)
                ?: youtubeFallbackThumbnail(song.id)
        if (!realCover.isNullOrBlank()) return song.copy(thumbnail = realCover)
        val fallback = CustomMascotAssets.fallbackCoverUri ?: return song
        return song.copy(thumbnail = fallback)
    }

    private fun LikedTrackBackup.toStarterSongItem(): SongItem? {
        if (title.isBlank() || id.isBlank()) return null
        SongLikeState.likedSongs
            .firstOrNull { it.id == id && !it.thumbnail.isNullOrBlank() && it.thumbnail != CustomMascotAssets.fallbackCoverUri }
            ?.let { return it }
        val parsedArtists =
            artist
                .split(",", "&")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { ArtistTiny(id = null, name = it) }
                .ifEmpty { listOf(ArtistTiny(id = null, name = "Неизвестный")) }
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

            if (profile.isNotEmpty) {
                appendLine()
                appendLine("АКТУАЛЬНЫЙ ПРОФИЛЬ ВКУСА (используй как точный эталон поджанра, продакшена, баса и энергии, НЕ копируй эти треки обратно):")
                if (profile.coreRecentTracks.isNotEmpty()) {
                    appendLine("• Свежие любимые и недавние треки (ГЛАВНЫЙ ориентир): ${profile.coreRecentTracks.joinToString("; ")}.")
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
                appendLine("Недавние пропуски в этой сессии (сейчас не под настроение, избегай такого звучания): ${recentSkips.joinToString("; ")}.")
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

    private suspend fun collectStructuredTasteProfile(cfg: CustomAppConfig): StructuredTasteProfile =
        withContext(Dispatchers.IO) {
            val coreRecent = LinkedHashSet<String>()
            val artistScores = HashMap<String, Int>()
            val artistDisplayNames = HashMap<String, String>()

            fun bumpArtist(
                rawArtist: String,
                weight: Int,
            ) {
                rawArtist
                    .split(",", "&", "feat.", "ft.")
                    .map { it.trim() }
                    .filter { it.length >= 2 }
                    .forEach { single ->
                        val norm = CustomConfigStore.buildArtistKey(single)
                        if (norm.isBlank()) return@forEach
                        val rep = cfg.artistReputation[norm]
                        if (rep?.isAutoMuted == true || rep?.isLowWeight == true) return@forEach
                        artistScores[norm] = (artistScores[norm] ?: 0) + weight
                        artistDisplayNames.putIfAbsent(norm, single)
                    }
            }

            fun addCore(
                artist: String,
                title: String,
                artistWeight: Int,
            ) {
                val cleanTitle = title.trim()
                val cleanArtist = artist.trim()
                if (cleanTitle.isBlank()) return
                if (configStore.isTrackDisliked(cleanArtist, cleanTitle)) return
                if (cleanArtist.isNotBlank() && configStore.isArtistAutoMuted(cleanArtist)) return
                val label = if (cleanArtist.isNotBlank()) "$cleanArtist - $cleanTitle" else cleanTitle
                if (coreRecent.size < 16) coreRecent.add(label)
                if (cleanArtist.isNotBlank()) bumpArtist(cleanArtist, artistWeight)
            }

            val recentLocalLikes =
                SongLikeState.likedSongs
                    .sortedByDescending { it.libraryAddedAt ?: 0L }
            recentLocalLikes.take(12).forEach { song ->
                val art = song.artists.joinToString(", ") { it.name }.trim()
                addCore(art, song.title, artistWeight = 5)
            }
            recentLocalLikes.drop(12).take(60).forEach { song ->
                val art = song.artists.joinToString(", ") { it.name }.trim()
                if (art.isNotBlank()) bumpArtist(art, weight = 2)
            }

            if (cfg.lastFmUsername.isNotBlank() && LastFM.apiKeyConfigured()) {
                runCatching {
                    val recent = LastFM.getRecentTracks(GlobalYouTube.client, cfg.lastFmUsername.trim(), limit = 10).getOrNull()
                    if (recent != null) {
                        val root = JSONObject(recent.toString())
                        val trackArr = root.optJSONObject("recenttracks")?.optJSONArray("track") ?: root.optJSONArray("track")
                        if (trackArr != null) {
                            for (i in 0 until trackArr.length()) {
                                val obj = trackArr.optJSONObject(i) ?: continue
                                val name = obj.optString("name").trim()
                                val art = obj.optJSONObject("artist")?.optString("#text").orEmpty().trim()
                                addCore(art, name, artistWeight = 4)
                            }
                        }
                    }
                    val top = LastFM.getTopTracks(GlobalYouTube.client, cfg.lastFmUsername.trim(), period = "1month", limit = 10).getOrNull()
                    top?.forEach { t ->
                        val art = t.artist?.name?.trim().orEmpty()
                        val name = t.name.trim()
                        if (art.isNotBlank()) bumpArtist(art, weight = 4)
                        if (coreRecent.size < 16) addCore(art, name, artistWeight = 2)
                    }
                }
            }

            if (GlobalYouTubeRepository.instance.isYouTubeHistoryAvailable()) {
                runCatching {
                    GlobalYouTubeRepository.instance.getHistory().take(12).forEach { entry ->
                        val art = entry.song.artists.joinToString(", ") { it.name }.trim()
                        addCore(art, entry.song.title, artistWeight = 3)
                    }
                }
            }

            cfg.likedTracksBackup.take(15).forEach { liked ->
                addCore(liked.artist, liked.title, artistWeight = 3)
            }
            cfg.likedTracksBackup.take(180).forEach { liked ->
                if (liked.artist.isNotBlank()) bumpArtist(liked.artist, weight = 1)
            }

            cfg.artistReputation.forEach { (normArtist, rep) ->
                if (!rep.isAutoMuted && !rep.isLowWeight && rep.likes > 0) {
                    val delta = (rep.likes * 3 - rep.dislikes * 2).coerceAtLeast(0)
                    if (delta > 0) {
                        artistScores[normArtist] = (artistScores[normArtist] ?: 0) + delta
                        artistDisplayNames.putIfAbsent(normArtist, normArtist)
                    }
                }
            }

            val topArtists =
                artistScores.entries
                    .sortedByDescending { it.value }
                    .take(12)
                    .mapNotNull { artistDisplayNames[it.key] }

            val extraContext =
                cfg.likedTracksBackup
                    .drop(15)
                    .shuffled()
                    .asSequence()
                    .filter { it.title.isNotBlank() && !configStore.isTrackDisliked(it.artist, it.title) }
                    .map { if (it.artist.isNotBlank()) "${it.artist} - ${it.title}" else it.title }
                    .filter { it !in coreRecent }
                    .distinct()
                    .take(6)
                    .toList()

            StructuredTasteProfile(
                coreRecentTracks = coreRecent.take(14),
                topArtists = topArtists,
                extraContextTracks = extraContext,
            )
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
        val targetLower = "${suggestion.artist} ${suggestion.title}".lowercase()

        return playable
            .mapNotNull { song ->
                val art = song.artists.joinToString(", ") { it.name }
                var score =
                    TrackMatcher.matchScore(
                        targetArtist = suggestion.artist,
                        targetTitle = suggestion.title,
                        targetDurationSec = null,
                        candidateArtist = art,
                        candidateTitle = song.title,
                        candidateDurationSec = song.duration,
                    )

                val candLower = "$art ${song.title}".lowercase()
                for (badTag in UNWANTED_VERSION_TAGS) {
                    if (candLower.contains(badTag) && !targetLower.contains(badTag)) {
                        score -= 40
                    }
                }
                val durationSec = song.duration?.toLong()
                if (durationSec != null && durationSec in 1L..45L) {
                    score -= 35
                }

                if (score >= 45) song to score else null
            }.maxByOrNull { it.second }?.first
            ?: playable.firstOrNull { song ->
                val candLower = song.title.lowercase()
                UNWANTED_VERSION_TAGS.none { tag -> candLower.contains(tag) && !targetLower.contains(tag) }
            }
            ?: playable.firstOrNull()
    }

    companion object {
        private const val TAG = "AiWaveEngine"
        private const val SESSION_SKIP_COOLDOWN_MS = 3 * 60 * 60 * 1_000L

        private val UNWANTED_VERSION_TAGS =
            listOf(
                "slowed",
                "sped up",
                "speed up",
                "nightcore",
                "reverb",
                "8d audio",
                "karaoke",
                "караоке",
                "минус",
                "instrumental",
                "cover",
                "кавер",
                "clean version",
                "radio edit",
                "censored",
                "цензур",
                "live at",
                "acoustic",
                "bass boosted",
            )

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
