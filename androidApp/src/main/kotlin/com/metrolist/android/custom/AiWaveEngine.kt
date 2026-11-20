package com.metrolist.android.custom

import android.util.Log
import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.state.GlobalYouTubeRepository
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

data class AiPlaylistResult(
    val query: String,
    val songs: List<SongItem>,
)

class AiWaveEngine(
    private val configStore: CustomConfigStore,
    private val geminiClient: GeminiClient,
) {
    private val waveMutex = Mutex()

    private val _isGeneratingWave = MutableStateFlow(false)
    val isGeneratingWave: StateFlow<Boolean> = _isGeneratingWave.asStateFlow()

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    suspend fun generateWaveSongs(
        seedSong: SongItem? = null,
        count: Int = configStore.current.aiPlaylistSize,
    ): Result<List<SongItem>> =
        waveMutex.withLock {
            _isGeneratingWave.value = true
            _statusText.value = if (seedSong != null) "Собираю волну от «${seedSong.title}»…" else "Подбираю волну…"
            try {
                val cfg = configStore.current
                val tasteSummary = collectTasteSummary(cfg)
                val prompt =
                    buildString {
                        if (seedSong != null) {
                            val seedArtist = seedSong.artists.joinToString(", ") { it.name }.trim()
                            appendLine("Собери радио-волну, развивающую трек: \"$seedArtist — ${seedSong.title}\".")
                            appendLine("Сохраняй близкое звучание, темп и настроение, но добавляй свежие и органичные треки.")
                        } else {
                            appendLine("Собери персональную музыкальную волну для пользователя.")
                            appendLine("Характер волны: ${cfg.waveCharacter.promptHint}.")
                            appendLine("Язык: ${cfg.waveLanguage.promptHint}.")
                            appendLine("Настроение: ${cfg.waveMood.promptHint}.")
                        }
                        if (tasteSummary.isNotBlank()) {
                            appendLine("История и вкусы пользователя: $tasteSummary.")
                        }
                        if (cfg.waveCharacter == WaveCharacter.DISCOVERY) {
                            appendLine("ВАЖНО: не предлагай треки и главных артистов из списка истории, ищи свежие варианты.")
                        }
                    }

                geminiClient
                    .generateTrackList(prompt = prompt, count = count.coerceIn(10, 40))
                    .mapCatching { suggestions ->
                        val resolved = resolveSuggestionsToSongs(suggestions)
                        check(resolved.isNotEmpty()) { "Не удалось найти ни одного трека в каталоге" }
                        resolved
                    }.onSuccess {
                        _statusText.value = null
                    }.onFailure { err ->
                        _statusText.value = err.message ?: "Ошибка генерации волны"
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
        val prompt =
            buildString {
                appendLine("Пользователь хочет подобрать музыку по запросу: \"$cleanRequest\".")
                if (cfg.waveLanguage != WaveLanguage.ANY) {
                    appendLine("Языковое предпочтение: ${cfg.waveLanguage.promptHint}.")
                }
                if (tasteSummary.isNotBlank()) {
                    appendLine("Дополнительный контекст вкуса пользователя: $tasteSummary.")
                }
                appendLine("Подбери треки, которые точно попадают в этот запрос, эпоху, стиль или настроение.")
            }

        return geminiClient
            .generateTrackList(prompt = prompt, count = count.coerceIn(10, 40))
            .mapCatching { suggestions ->
                val resolved = resolveSuggestionsToSongs(suggestions)
                check(resolved.isNotEmpty()) { "Не удалось подобрать треки по запросу" }
                AiPlaylistResult(query = cleanRequest, songs = resolved)
            }
    }

    suspend fun generateMascotPhrase(song: SongItem): String? {
        val cfg = configStore.current
        val artist = song.artists.joinToString(", ") { it.name }.trim().ifBlank { "Неизвестный" }
        val moodHint =
            listOfNotNull(
                cfg.waveMood.takeIf { it != WaveMood.ANY }?.label,
                cfg.waveCharacter.takeIf { it != WaveCharacter.DEFAULT }?.label,
            ).joinToString(", ").takeIf { it.isNotBlank() }
        return geminiClient.generateMascotLine(
            artist = artist,
            title = song.title,
            moodHint = moodHint,
        )
    }

    private suspend fun collectTasteSummary(cfg: CustomAppConfig): String =
        withContext(Dispatchers.IO) {
            val entries = mutableListOf<String>()

            if (cfg.lastFmUsername.isNotBlank() && LastFM.apiKeyConfigured()) {
                runCatching {
                    val top = LastFM.getTopTracks(GlobalYouTube.client, cfg.lastFmUsername.trim(), period = "1month", limit = 12).getOrNull()
                    top?.forEach { t ->
                        val art = t.artist?.name?.trim().orEmpty()
                        val name = t.name.trim()
                        if (name.isNotBlank()) entries += if (art.isNotBlank()) "$art - $name" else name
                    }
                    val recent = LastFM.getRecentTracks(GlobalYouTube.client, cfg.lastFmUsername.trim(), limit = 12).getOrNull()
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
                    val history = GlobalYouTubeRepository.instance.getHistory().take(12)
                    history.forEach { entry ->
                        val art = entry.song.artists.joinToString(", ") { it.name }.trim()
                        val title = entry.song.title.trim()
                        if (title.isNotBlank()) entries += if (art.isNotBlank()) "$art - $title" else title
                    }
                }
            }

            entries.distinct().take(20).joinToString("; ")
        }

    private suspend fun resolveSuggestionsToSongs(
        suggestions: List<AiTrackSuggestion>,
    ): List<SongItem> =
        coroutineScope {
            val found =
                suggestions.take(35).chunked(5).flatMap { batch ->
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
