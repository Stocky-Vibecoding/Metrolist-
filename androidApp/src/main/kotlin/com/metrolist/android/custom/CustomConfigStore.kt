package com.metrolist.android.custom

import android.content.Context
import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.ui.components.LocalProfileBridge
import com.metrolist.shared.ui.screens.CustomHomeShelvesBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

object CustomMascotAssets {
    @Volatile
    var drawableResId: Int = 0
        private set

    @Volatile
    var sleepResId: Int = 0
        private set

    @Volatile
    var awakeResId: Int = 0
        private set

    @Volatile
    var vibeResId: Int = 0
        private set

    @Volatile
    var sleepVideoResId: Int = 0
        private set

    @Volatile
    var wakeVideoResId: Int = 0
        private set

    @Volatile
    var awakeVideoResId: Int = 0
        private set

    @Volatile
    var fallbackCoverUri: String? = null
        private set

    val hasDedicatedSprites: Boolean
        get() = sleepResId != 0 || awakeResId != 0

    val hasVideoMascot: Boolean
        get() = sleepVideoResId != 0 || awakeVideoResId != 0 || wakeVideoResId != 0

    fun init(appContext: Context) {
        val pkg = appContext.packageName
        val res = appContext.resources
        val girlId = res.getIdentifier("mascot_girl", "drawable", pkg)
        drawableResId = girlId
        sleepResId = res.getIdentifier("mascot_sleep", "drawable", pkg)
        awakeResId = res.getIdentifier("mascot_awake", "drawable", pkg)
        vibeResId = res.getIdentifier("mascot_vibe", "drawable", pkg)
        sleepVideoResId = res.getIdentifier("mascot_sleep", "raw", pkg)
        wakeVideoResId = res.getIdentifier("mascot_wake", "raw", pkg)
        awakeVideoResId = res.getIdentifier("mascot_awake", "raw", pkg)
        fallbackCoverUri = if (girlId != 0) "android.resource://$pkg/$girlId" else null
    }
}

enum class YandexPlusStatus {
    UNKNOWN,
    ACTIVE,
    NO_PLUS,
    INVALID_TOKEN,
    NO_TOKEN,
}

enum class TrackSourcePreference(
    val label: String,
) {
    AUTO("Авто"),
    YANDEX("Я.Музыка"),
    VK("VK"),
    YTM("YTM"),
}

data class LikedTrackBackup(
    val id: String,
    val artist: String,
    val title: String,
    val thumbnail: String? = null,
)

data class ArtistReputation(
    val likes: Int = 0,
    val dislikes: Int = 0,
) {
    val isAutoMuted: Boolean
        get() = (dislikes >= 4 && likes == 0) || (dislikes >= 6 && dislikes >= likes * 4)

    val isLowWeight: Boolean
        get() = !isAutoMuted && dislikes >= 3 && dislikes > likes * 2
}

data class LocalArtistStat(
    val displayName: String,
    val plays: Int = 0,
    val completes: Int = 0,
    val likes: Int = 0,
    val lastPlayedMs: Long = 0L,
) {
    fun rankScore(nowMs: Long = System.currentTimeMillis()): Double {
        val recencyDays = ((nowMs - lastPlayedMs).coerceAtLeast(0L)).toDouble() / (24.0 * 3600.0 * 1000.0)
        val recencyBoost =
            when {
                lastPlayedMs <= 0L -> 0.0
                recencyDays <= 7.0 -> 8.0
                recencyDays <= 30.0 -> 4.0
                else -> 1.0
            }
        return plays * 1.0 + completes * 2.5 + likes * 4.0 + recencyBoost
    }
}

enum class WaveCharacter(
    val label: String,
    val promptHint: String,
) {
    DEFAULT("Любой", "сбалансированный микс знакомых и новых треков по вкусу пользователя"),
    FAVORITE("Любимое", "максимально близко к любимым артистам и жанрам из истории, проверенные хиты и треки того же круга"),
    DISCOVERY("Незнакомое", "свежие открытия, малоизвестные и новые артисты, НЕ повторяй треки и главных артистов из истории"),
    POPULAR("Популярное", "громкие хиты, чартовые и самые известные треки в подходящем стиле"),
}

enum class WaveEra(
    val label: String,
    val promptHint: String,
) {
    ANY("Любая", "любая эпоха и год выхода треков"),
    MODERN("Современное", "только современные релизы 2020–2026 годов и актуальное звучание"),
    RETRO("Ретро", "ретро-хиты и классика прошлых десятилетий (80-е, 90-е, начало 2000-х)"),
    Y2000S("2000-е", "треки и хиты 2000-х годов"),
    Y90S("90-е", "треки и классика 1990-х годов"),
}

enum class WaveLanguage(
    val label: String,
    val promptHint: String,
) {
    ANY("Любой", "любой язык, исходя из вкуса пользователя"),
    RUSSIAN("Русское", "строго русскоязычные артисты и треки на русском языке"),
    FOREIGN("Зарубежное", "только зарубежные артисты, без русскоязычных треков"),
    INSTRUMENTAL("Без слов", "инструментальная музыка, биты, электроника или фонк без вокала"),
}

enum class WaveMood(
    val label: String,
    val promptHint: String,
) {
    ANY("Любое", "естественное настроение по истории прослушиваний"),
    DRIVE("Драйв", "энергично, качает, высокий темп, адреналин"),
    NIGHT("Ночь", "атмосферно, ночная поездка, глубокий вайб, тёмное звучание"),
    HARD_BASS("Жёсткий бас", "жирный саббас, 808, фонк, трэп, басовые бэнгеры"),
    CHILL("Чилл", "расслабленно, плавно, спокойно, без агрессии"),
    SAD("Грусть", "меланхолично, лирично, эмоционально"),
}

enum class NeonColorMode(
    val label: String,
) {
    PURPLE("Фиолетовый"),
    AUTO("Под обложку"),
    CYAN("Неон-циан"),
    CRIMSON("Алый"),
}

data class CustomAppConfig(
    val localAccountName: String = "Локальный профиль",
    val useLocalAccountOnly: Boolean = true,
    val hybridResolverEnabled: Boolean = true,
    val preferVkForExplicit: Boolean = true,
    val cacheWaveAndDownloadLikes: Boolean = true,
    val aiCensorshipCheckEnabled: Boolean = false,
    val yandexToken: String = "",
    val vkToken: String = "",
    val lastFmApiKey: String = "",
    val lastFmSecret: String = "",
    val lastFmUsername: String = "",
    val geminiApiKey: String = "",
    val geminiModelMode: String = MODEL_MODE_AUTO,
    val aiPlaylistSize: Int = 25,
    val useWaveAsHome: Boolean = true,
    val mascotEnabled: Boolean = true,
    val mascotFolderUri: String = "",
    val neonColorMode: NeonColorMode = NeonColorMode.AUTO,
    val waveCharacter: WaveCharacter = WaveCharacter.DEFAULT,
    val waveEra: WaveEra = WaveEra.ANY,
    val waveLanguage: WaveLanguage = WaveLanguage.ANY,
    val waveMood: WaveMood = WaveMood.ANY,
    val trackSourceOverrides: Map<String, TrackSourcePreference> = emptyMap(),
    val dislikedTracks: Set<String> = emptySet(),
    val artistReputation: Map<String, ArtistReputation> = emptyMap(),
    val likedTracksBackup: List<LikedTrackBackup> = emptyList(),
    val lastWaveTracks: List<LikedTrackBackup> = emptyList(),
    val externalTasteProfile: List<LikedTrackBackup> = emptyList(),
    val localArtistStats: Map<String, LocalArtistStat> = emptyMap(),
    val playedTrackTimestamps: Map<String, Long> = emptyMap(),
    val blacklistedVkAudioIds: Set<String> = emptySet(),
    val learnedCensoredArtists: Map<String, Int> = emptyMap(),
    val lastTasteScanMs: Long = 0L,
) {
    fun waveFilterSummary(): String {
        val parts =
            buildList {
                if (waveCharacter != WaveCharacter.DEFAULT) add(waveCharacter.label)
                if (waveEra != WaveEra.ANY) add(waveEra.label)
                if (waveLanguage != WaveLanguage.ANY) add(waveLanguage.label)
                if (waveMood != WaveMood.ANY) add(waveMood.label)
            }
        return if (parts.isEmpty()) "Настроить волну" else parts.joinToString(" • ")
    }

    companion object {
        const val MODEL_MODE_AUTO = "AUTO"
    }
}

class CustomConfigStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val censorshipPrefs = appContext.getSharedPreferences(CENSORSHIP_PREFS_NAME, Context.MODE_PRIVATE)
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistChannel = Channel<CustomAppConfig>(Channel.CONFLATED)

    private val _config = MutableStateFlow(loadInitial())
    val config: StateFlow<CustomAppConfig> = _config.asStateFlow()

    private val _yandexPlusStatus =
        MutableStateFlow(
            if (_config.value.yandexToken.isBlank()) YandexPlusStatus.NO_TOKEN else YandexPlusStatus.UNKNOWN,
        )
    val yandexPlusStatus: StateFlow<YandexPlusStatus> = _yandexPlusStatus.asStateFlow()

    val current: CustomAppConfig
        get() = _config.value

    init {
        CustomMascotAssets.init(appContext)
        CustomMediaCacheManager.init(appContext)
        CustomCatalogProvider.bindHomeShelvesBridge(this)
        syncLocalProfileBridge(_config.value)
        syncLearnedCensorship(_config.value)
        LocalProfileBridge.onUpdateLocalName = { newName ->
            update { it.copy(localAccountName = newName.trim().ifBlank { "Локальный профиль" }) }
        }
        applyRuntimeIntegrations(_config.value)

        persistScope.launch {
            for (snapshot in persistChannel) {
                runCatching {
                    prefs.edit().putString(KEY_CONFIG_JSON, toJson(snapshot).toString()).apply()
                }
            }
        }
    }

    fun update(transform: (CustomAppConfig) -> CustomAppConfig) {
        val previous = _config.value
        val updated = transform(previous)
        saveAndPublish(
            updated = updated,
            yandexTokenChanged = previous.yandexToken != updated.yandexToken,
            vkTokenChanged = previous.vkToken != updated.vkToken,
        )
    }

    fun setYandexPlusStatus(status: YandexPlusStatus) {
        _yandexPlusStatus.value = status
    }

    fun saveLastWaveTracks(tracks: List<LikedTrackBackup>) {
        if (tracks.isEmpty()) return
        val cleaned = tracks.filter { it.title.isNotBlank() }.take(40)
        if (cleaned.isEmpty()) return
        update { it.copy(lastWaveTracks = cleaned) }
    }

    fun getTrackSourceOverride(
        videoId: String?,
        artist: String,
        title: String,
    ): TrackSourcePreference {
        val map = _config.value.trackSourceOverrides
        if (!videoId.isNullOrBlank()) {
            map["id::$videoId"]?.let { return it }
        }
        val tKey = buildTrackKey(artist, title)
        if (tKey.isNotBlank()) {
            map[tKey]?.let { return it }
        }
        return TrackSourcePreference.AUTO
    }

    fun setTrackSourceOverride(
        videoId: String?,
        artist: String,
        title: String,
        preference: TrackSourcePreference,
    ) {
        val tKey = buildTrackKey(artist, title)
        val idKey = videoId?.takeIf { it.isNotBlank() }?.let { "id::$it" }
        update { cfg ->
            val next = cfg.trackSourceOverrides.toMutableMap()
            if (preference == TrackSourcePreference.AUTO) {
                if (tKey.isNotBlank()) next.remove(tKey)
                if (idKey != null) next.remove(idKey)
            } else {
                if (tKey.isNotBlank()) next[tKey] = preference
                if (idKey != null) next[idKey] = preference
            }
            cfg.copy(trackSourceOverrides = next)
        }
    }

    fun isTrackDisliked(
        artist: String,
        title: String,
    ): Boolean {
        val key = buildTrackKey(artist, title)
        return key.isNotBlank() && key in _config.value.dislikedTracks
    }

    fun isArtistAutoMuted(artist: String): Boolean {
        val aKey = buildArtistKey(artist)
        if (aKey.isBlank()) return false
        return _config.value.artistReputation[aKey]?.isAutoMuted == true
    }

    fun dislikeTrack(
        artist: String,
        title: String,
    ) {
        val tKey = buildTrackKey(artist, title)
        val aKey = buildArtistKey(artist)
        update { cfg ->
            val nextDisliked =
                if (tKey.isNotBlank()) cfg.dislikedTracks + tKey else cfg.dislikedTracks
            val nextRep = cfg.artistReputation.toMutableMap()
            if (aKey.isNotBlank()) {
                val prev = nextRep[aKey] ?: ArtistReputation()
                nextRep[aKey] = prev.copy(dislikes = prev.dislikes + 1)
            }
            cfg.copy(dislikedTracks = nextDisliked, artistReputation = nextRep)
        }
    }

    fun likeTrack(
        artist: String,
        title: String,
        songId: String = "",
        thumbnail: String? = null,
    ) {
        val tKey = buildTrackKey(artist, title)
        val aKey = buildArtistKey(artist)
        val cleanThumb = thumbnail?.trim()?.takeIf { it.isNotBlank() && it != CustomMascotAssets.fallbackCoverUri }
        val now = System.currentTimeMillis()
        update { cfg ->
            val nextDisliked =
                if (tKey.isNotBlank()) cfg.dislikedTracks - tKey else cfg.dislikedTracks
            val nextRep = cfg.artistReputation.toMutableMap()
            if (aKey.isNotBlank()) {
                val prev = nextRep[aKey] ?: ArtistReputation()
                nextRep[aKey] = prev.copy(likes = prev.likes + 1)
            }
            val nextArtistStats = cfg.localArtistStats.toMutableMap()
            splitArtistNames(artist).forEach { singleName ->
                val norm = buildArtistKey(singleName)
                if (norm.isNotBlank()) {
                    val prev = nextArtistStats[norm] ?: LocalArtistStat(displayName = singleName)
                    nextArtistStats[norm] =
                        prev.copy(
                            displayName = prev.displayName.ifBlank { singleName },
                            likes = prev.likes + 1,
                            lastPlayedMs = maxOf(prev.lastPlayedMs, now),
                        )
                }
            }
            val trimmedArtistStats = trimArtistStats(nextArtistStats)

            val nextLiked =
                if (songId.isNotBlank() || title.isNotBlank()) {
                    val existingThumb =
                        cfg.likedTracksBackup
                            .firstOrNull {
                                (songId.isNotBlank() && it.id == songId) ||
                                    (it.artist.equals(artist, ignoreCase = true) && it.title.equals(title, ignoreCase = true))
                            }?.thumbnail
                    val entry =
                        LikedTrackBackup(
                            id = songId.trim(),
                            artist = artist.trim(),
                            title = title.trim(),
                            thumbnail = cleanThumb ?: existingThumb,
                        )
                    (listOf(entry) + cfg.likedTracksBackup.filterNot {
                        (entry.id.isNotBlank() && it.id == entry.id) ||
                            (it.artist.equals(entry.artist, ignoreCase = true) && it.title.equals(entry.title, ignoreCase = true))
                    }).take(MAX_LIKED_BACKUP)
                } else {
                    cfg.likedTracksBackup
                }
            cfg.copy(
                dislikedTracks = nextDisliked,
                artistReputation = nextRep,
                localArtistStats = trimmedArtistStats,
                likedTracksBackup = nextLiked,
            )
        }
    }

    fun recordTrackPlayed(
        artist: String,
        title: String,
        completed: Boolean,
    ) {
        val tKey = buildTrackKey(artist, title)
        val now = System.currentTimeMillis()
        update { cfg ->
            val nextPlayed = cfg.playedTrackTimestamps.toMutableMap()
            if (tKey.isNotBlank()) {
                nextPlayed[tKey] = now
            }
            val cutoff = now - MAX_PLAYED_HISTORY_AGE_MS
            val cleanedPlayed =
                nextPlayed.entries
                    .asSequence()
                    .filter { it.value >= cutoff }
                    .sortedByDescending { it.value }
                    .take(MAX_PLAYED_HISTORY)
                    .associate { it.key to it.value }

            val nextStats = cfg.localArtistStats.toMutableMap()
            splitArtistNames(artist).forEach { singleName ->
                val norm = buildArtistKey(singleName)
                if (norm.isNotBlank()) {
                    val prev = nextStats[norm] ?: LocalArtistStat(displayName = singleName)
                    nextStats[norm] =
                        prev.copy(
                            displayName = prev.displayName.ifBlank { singleName },
                            plays = prev.plays + 1,
                            completes = prev.completes + if (completed) 1 else 0,
                            lastPlayedMs = now,
                        )
                }
            }
            cfg.copy(
                playedTrackTimestamps = cleanedPlayed,
                localArtistStats = trimArtistStats(nextStats),
            )
        }
    }

    fun isTrackInRecentCooldown(
        artist: String,
        title: String,
        cooldownMs: Long = DEFAULT_REPEAT_COOLDOWN_MS,
    ): Boolean {
        val tKey = buildTrackKey(artist, title)
        if (tKey.isBlank()) return false
        val playedAt = _config.value.playedTrackTimestamps[tKey] ?: return false
        return (System.currentTimeMillis() - playedAt) < cooldownMs
    }

    fun getLocalTopArtists(limit: Int = 25): List<String> {
        val cfg = _config.value
        val now = System.currentTimeMillis()
        val combined = LinkedHashMap<String, Pair<String, Double>>()

        cfg.localArtistStats.forEach { (norm, stat) ->
            if (cfg.artistReputation[norm]?.isAutoMuted == true) return@forEach
            val score = stat.rankScore(now)
            if (score > 0.0) {
                combined[norm] = stat.displayName.ifBlank { norm } to score
            }
        }

        cfg.likedTracksBackup.take(250).forEachIndexed { idx, item ->
            val weight = if (idx < 30) 2.5 else 1.0
            splitArtistNames(item.artist).forEach { single ->
                val norm = buildArtistKey(single)
                if (norm.isBlank() || cfg.artistReputation[norm]?.isAutoMuted == true) return@forEach
                val prev = combined[norm]
                combined[norm] = (prev?.first ?: single) to ((prev?.second ?: 0.0) + weight)
            }
        }

        if (combined.size < limit) {
            cfg.externalTasteProfile.take(200).forEach { item ->
                splitArtistNames(item.artist).forEach { single ->
                    val norm = buildArtistKey(single)
                    if (norm.isBlank() || cfg.artistReputation[norm]?.isAutoMuted == true) return@forEach
                    val prev = combined[norm]
                    combined[norm] = (prev?.first ?: single) to ((prev?.second ?: 0.0) + 0.6)
                }
            }
        }

        return combined.values
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    fun mergeExternalTasteProfile(entries: List<LikedTrackBackup>): Int {
        if (entries.isEmpty()) return _config.value.externalTasteProfile.size
        var finalCount = 0
        val now = System.currentTimeMillis()
        update { cfg ->
            val seenKeys = HashSet<String>()
            val merged = ArrayList<LikedTrackBackup>(minOf(entries.size + cfg.externalTasteProfile.size, MAX_EXTERNAL_TASTE))

            fun addIfUnique(item: LikedTrackBackup) {
                if (merged.size >= MAX_EXTERNAL_TASTE) return
                val cleanTitle = item.title.trim()
                val cleanArtist = item.artist.trim()
                val cleanId = item.id.trim()
                if (cleanTitle.isBlank() && cleanId.isBlank()) return
                val key = buildTrackKey(cleanArtist, cleanTitle).ifBlank { "id::$cleanId" }
                if (seenKeys.add(key)) {
                    merged.add(LikedTrackBackup(id = cleanId, artist = cleanArtist, title = cleanTitle, thumbnail = null))
                }
            }

            entries.forEach(::addIfUnique)
            cfg.externalTasteProfile.forEach(::addIfUnique)
            finalCount = merged.size
            cfg.copy(externalTasteProfile = merged, lastTasteScanMs = now)
        }
        return finalCount
    }

    fun blacklistVkAudio(audioKey: String) {
        val clean = audioKey.trim()
        if (clean.isBlank()) return
        update { cfg ->
            val next = (cfg.blacklistedVkAudioIds + clean)
            val trimmed = if (next.size > MAX_BLACKLISTED_VK) next.drop(next.size - MAX_BLACKLISTED_VK).toSet() else next
            cfg.copy(blacklistedVkAudioIds = trimmed)
        }
    }

    fun isVkAudioBlacklisted(audioKey: String): Boolean {
        val clean = audioKey.trim()
        return clean.isNotBlank() && clean in _config.value.blacklistedVkAudioIds
    }

    fun recordManualCensorshipSwitch(
        artist: String,
        forceImmediate: Boolean = false,
    ) {
        val names = splitArtistNames(artist)
        if (names.isEmpty()) return
        update { cfg ->
            val next = cfg.learnedCensoredArtists.toMutableMap()
            names.forEach { single ->
                val norm = buildArtistKey(single)
                if (norm.length >= 2) {
                    val prev = next[norm] ?: 0
                    val updatedCount = if (forceImmediate) maxOf(prev + 1, 2) else prev + 1
                    next[norm] = updatedCount
                    if (updatedCount >= 2) {
                        LocalCensorshipAdvisor.rememberCensoredArtist(single)
                    }
                }
            }
            cfg.copy(learnedCensoredArtists = next)
        }
    }

    fun isArtistLearnedCensored(artist: String): Boolean {
        val map = _config.value.learnedCensoredArtists
        if (map.isEmpty()) return false
        return splitArtistNames(artist).any { single ->
            val norm = buildArtistKey(single)
            norm.length >= 2 && (map[norm] ?: 0) >= 2
        }
    }

    fun mergeImportedLikes(entries: List<LikedTrackBackup>): Int {
        if (entries.isEmpty()) return _config.value.likedTracksBackup.size
        var finalCount = 0
        update { cfg ->
            val seenKeys = HashSet<String>()
            val merged = ArrayList<LikedTrackBackup>(minOf(entries.size + cfg.likedTracksBackup.size, MAX_LIKED_BACKUP))

            fun addIfUnique(item: LikedTrackBackup) {
                if (merged.size >= MAX_LIKED_BACKUP) return
                val cleanTitle = item.title.trim()
                val cleanArtist = item.artist.trim()
                val cleanId = item.id.trim()
                val cleanThumb = item.thumbnail?.trim()?.takeIf { it.isNotBlank() && it != CustomMascotAssets.fallbackCoverUri }
                if (cleanId.isBlank() && cleanTitle.isBlank()) return
                val key = buildTrackKey(cleanArtist, cleanTitle).ifBlank { "id::$cleanId" }
                if (seenKeys.add(key)) {
                    merged.add(LikedTrackBackup(id = cleanId, artist = cleanArtist, title = cleanTitle, thumbnail = cleanThumb))
                }
            }

            entries.forEach(::addIfUnique)
            cfg.likedTracksBackup.forEach(::addIfUnique)
            finalCount = merged.size
            cfg.copy(likedTracksBackup = merged)
        }
        return finalCount
    }

    fun removeLikedTrackBackup(
        songId: String,
        artist: String,
        title: String,
    ) {
        update { cfg ->
            val nextLiked =
                cfg.likedTracksBackup.filterNot {
                    (songId.isNotBlank() && it.id == songId) ||
                        (it.artist.equals(artist, ignoreCase = true) && it.title.equals(title, ignoreCase = true))
                }
            cfg.copy(likedTracksBackup = nextLiked)
        }
    }

    fun clearDislikedTracks() {
        update { it.copy(dislikedTracks = emptySet()) }
    }

    fun resetMutedArtists() {
        update { cfg ->
            val cleaned = cfg.artistReputation.filterValues { !it.isAutoMuted }
            cfg.copy(artistReputation = cleaned)
        }
    }

    fun clearTrackSourceOverrides() {
        update { it.copy(trackSourceOverrides = emptyMap()) }
    }

    fun clearBlacklistedVkAudios() {
        update { it.copy(blacklistedVkAudioIds = emptySet()) }
    }

    fun clearPlayedHistory() {
        update { it.copy(playedTrackTimestamps = emptyMap()) }
    }

    fun getCachedCensorship(
        artist: String,
        title: String,
    ): Boolean? {
        val key = buildTrackKey(artist, title)
        if (key.isBlank() || !censorshipPrefs.contains(key)) return null
        return censorshipPrefs.getBoolean(key, false)
    }

    fun putCachedCensorship(
        artist: String,
        title: String,
        censored: Boolean,
    ) {
        val key = buildTrackKey(artist, title)
        if (key.isBlank()) return
        if (censorshipPrefs.all.size > 800) {
            censorshipPrefs.edit().clear().apply()
        }
        censorshipPrefs.edit().putBoolean(key, censored).apply()
    }

    /** Собирается строго на лету по нажатию «Экспорт», лишних файлов в памяти не держит. */
    fun exportToJson(extraLikedIds: Set<String> = emptySet()): String =
        toJson(_config.value, extraLikedIds).toString(2)

    fun importFromJson(rawJson: String): Result<CustomAppConfig> =
        runCatching {
            val parsed = fromJson(JSONObject(rawJson.trim()))
            val previous = _config.value
            saveAndPublish(
                updated = parsed,
                yandexTokenChanged = previous.yandexToken != parsed.yandexToken,
                vkTokenChanged = previous.vkToken != parsed.vkToken,
            )
            parsed
        }

    private fun trimArtistStats(map: Map<String, LocalArtistStat>): Map<String, LocalArtistStat> {
        if (map.size <= MAX_LOCAL_ARTISTS) return map
        val now = System.currentTimeMillis()
        return map.entries
            .sortedByDescending { it.value.rankScore(now) }
            .take(MAX_LOCAL_ARTISTS)
            .associate { it.key to it.value }
    }

    private fun syncLocalProfileBridge(cfg: CustomAppConfig) {
        LocalProfileBridge.localName = cfg.localAccountName
        LocalProfileBridge.localModeEnabled = cfg.useLocalAccountOnly
    }

    private fun syncLearnedCensorship(cfg: CustomAppConfig) {
        cfg.learnedCensoredArtists.forEach { (norm, count) ->
            if (count >= 2 && norm.length >= 2) {
                LocalCensorshipAdvisor.rememberCensoredArtist(norm)
            }
        }
    }

    private fun saveAndPublish(
        updated: CustomAppConfig,
        yandexTokenChanged: Boolean,
        vkTokenChanged: Boolean,
    ) {
        _config.value = updated
        persistChannel.trySend(updated)
        syncLocalProfileBridge(updated)
        syncLearnedCensorship(updated)
        if (yandexTokenChanged) {
            _yandexPlusStatus.value =
                if (updated.yandexToken.isBlank()) YandexPlusStatus.NO_TOKEN else YandexPlusStatus.UNKNOWN
        }
        if (yandexTokenChanged || vkTokenChanged) {
            CustomHomeShelvesBridge.requestLoad(force = true)
            CustomCatalogProvider.triggerBackgroundTasteScan(this, force = true)
        }
        applyRuntimeIntegrations(updated)
    }

    private fun loadInitial(): CustomAppConfig {
        val raw = prefs.getString(KEY_CONFIG_JSON, null) ?: return CustomAppConfig()
        return runCatching { fromJson(JSONObject(raw)) }.getOrDefault(CustomAppConfig())
    }

    fun applyRuntimeIntegrations(cfg: CustomAppConfig = _config.value) {
        if (cfg.lastFmApiKey.isNotBlank() && cfg.lastFmSecret.isNotBlank()) {
            LastFM.initialize(cfg.lastFmApiKey.trim(), cfg.lastFmSecret.trim())
        }
    }

    private fun toJson(
        cfg: CustomAppConfig,
        extraLikedIds: Set<String> = emptySet(),
    ): JSONObject =
        JSONObject().apply {
            put("schemaVersion", 7)
            put("localAccountName", cfg.localAccountName)
            put("useLocalAccountOnly", cfg.useLocalAccountOnly)
            put("hybridResolverEnabled", cfg.hybridResolverEnabled)
            put("preferVkForExplicit", cfg.preferVkForExplicit)
            put("cacheWaveAndDownloadLikes", cfg.cacheWaveAndDownloadLikes)
            put("aiCensorshipCheckEnabled", cfg.aiCensorshipCheckEnabled)
            put("yandexToken", cfg.yandexToken)
            put("vkToken", cfg.vkToken)
            put("lastFmApiKey", cfg.lastFmApiKey)
            put("lastFmSecret", cfg.lastFmSecret)
            put("lastFmUsername", cfg.lastFmUsername)
            put("geminiApiKey", cfg.geminiApiKey)
            put("geminiModelMode", cfg.geminiModelMode)
            put("aiPlaylistSize", cfg.aiPlaylistSize)
            put("useWaveAsHome", cfg.useWaveAsHome)
            put("mascotEnabled", cfg.mascotEnabled)
            put("mascotFolderUri", cfg.mascotFolderUri)
            put("neonColorMode", cfg.neonColorMode.name)
            put("waveCharacter", cfg.waveCharacter.name)
            put("waveEra", cfg.waveEra.name)
            put("waveLanguage", cfg.waveLanguage.name)
            put("waveMood", cfg.waveMood.name)
            put("lastTasteScanMs", cfg.lastTasteScanMs)

            val overridesObj = JSONObject()
            cfg.trackSourceOverrides.forEach { (k, v) -> overridesObj.put(k, v.name) }
            put("trackSourceOverrides", overridesObj)

            val dislikedArr = JSONArray()
            cfg.dislikedTracks.forEach { dislikedArr.put(it) }
            put("dislikedTracks", dislikedArr)

            val repObj = JSONObject()
            cfg.artistReputation.forEach { (artist, stat) ->
                repObj.put(
                    artist,
                    JSONObject()
                        .put("l", stat.likes)
                        .put("d", stat.dislikes),
                )
            }
            put("artistReputation", repObj)

            val likedArr = JSONArray()
            val knownIds = HashSet<String>()
            cfg.likedTracksBackup.forEach { item ->
                if (item.id.isNotBlank()) knownIds.add(item.id)
                likedArr.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("artist", item.artist)
                        .put("title", item.title)
                        .apply {
                            if (!item.thumbnail.isNullOrBlank()) put("thumbnail", item.thumbnail)
                        },
                )
            }
            extraLikedIds.forEach { id ->
                if (id.isNotBlank() && knownIds.add(id)) {
                    likedArr.put(
                        JSONObject()
                            .put("id", id)
                            .put("artist", "")
                            .put("title", ""),
                    )
                }
            }
            put("likedTracksBackup", likedArr)

            val waveArr = JSONArray()
            cfg.lastWaveTracks.forEach { item ->
                waveArr.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("artist", item.artist)
                        .put("title", item.title)
                        .apply {
                            if (!item.thumbnail.isNullOrBlank()) put("thumbnail", item.thumbnail)
                        },
                )
            }
            put("lastWaveTracks", waveArr)

            val extTasteArr = JSONArray()
            cfg.externalTasteProfile.forEach { item ->
                extTasteArr.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("artist", item.artist)
                        .put("title", item.title),
                )
            }
            put("externalTasteProfile", extTasteArr)

            val artistStatsObj = JSONObject()
            cfg.localArtistStats.forEach { (norm, stat) ->
                artistStatsObj.put(
                    norm,
                    JSONObject()
                        .put("n", stat.displayName)
                        .put("p", stat.plays)
                        .put("c", stat.completes)
                        .put("l", stat.likes)
                        .put("t", stat.lastPlayedMs),
                )
            }
            put("localArtistStats", artistStatsObj)

            val playedObj = JSONObject()
            cfg.playedTrackTimestamps.forEach { (k, ts) -> playedObj.put(k, ts) }
            put("playedTrackTimestamps", playedObj)

            val blacklistedVkArr = JSONArray()
            cfg.blacklistedVkAudioIds.forEach { blacklistedVkArr.put(it) }
            put("blacklistedVkAudioIds", blacklistedVkArr)

            val learnedCensObj = JSONObject()
            cfg.learnedCensoredArtists.forEach { (k, cnt) -> learnedCensObj.put(k, cnt) }
            put("learnedCensoredArtists", learnedCensObj)
        }

    private fun fromJson(obj: JSONObject): CustomAppConfig {
        val defaults = CustomAppConfig()

        val overrides =
            buildMap<String, TrackSourcePreference> {
                val mapObj = obj.optJSONObject("trackSourceOverrides") ?: return@buildMap
                val keys = mapObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val pref = enumByName(mapObj.optString(k), TrackSourcePreference.AUTO)
                    if (pref != TrackSourcePreference.AUTO) put(k, pref)
                }
            }

        val disliked =
            buildSet<String> {
                val arr = obj.optJSONArray("dislikedTracks") ?: return@buildSet
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i).trim()
                    if (s.isNotBlank()) add(s)
                }
            }

        val repMap =
            buildMap<String, ArtistReputation> {
                val repObj = obj.optJSONObject("artistReputation") ?: return@buildMap
                val keys = repObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val item = repObj.optJSONObject(k) ?: continue
                    put(
                        k,
                        ArtistReputation(
                            likes = item.optInt("l", 0).coerceAtLeast(0),
                            dislikes = item.optInt("d", 0).coerceAtLeast(0),
                        ),
                    )
                }
            }

        val localStats =
            buildMap<String, LocalArtistStat> {
                val statsObj = obj.optJSONObject("localArtistStats") ?: return@buildMap
                val keys = statsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val item = statsObj.optJSONObject(k) ?: continue
                    put(
                        k,
                        LocalArtistStat(
                            displayName = item.optString("n", k).ifBlank { k },
                            plays = item.optInt("p", 0).coerceAtLeast(0),
                            completes = item.optInt("c", 0).coerceAtLeast(0),
                            likes = item.optInt("l", 0).coerceAtLeast(0),
                            lastPlayedMs = item.optLong("t", 0L).coerceAtLeast(0L),
                        ),
                    )
                }
            }

        val playedTimestamps =
            buildMap<String, Long> {
                val pObj = obj.optJSONObject("playedTrackTimestamps") ?: return@buildMap
                val keys = pObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val ts = pObj.optLong(k, 0L)
                    if (ts > 0L) put(k, ts)
                }
            }

        val blacklistedVk =
            buildSet<String> {
                val arr = obj.optJSONArray("blacklistedVkAudioIds") ?: return@buildSet
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i).trim()
                    if (s.isNotBlank()) add(s)
                }
            }

        val learnedCensored =
            buildMap<String, Int> {
                val cObj = obj.optJSONObject("learnedCensoredArtists") ?: return@buildMap
                val keys = cObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val cnt = cObj.optInt(k, 0)
                    if (cnt > 0) put(k, cnt)
                }
            }

        fun parseTrackList(
            arr: JSONArray?,
            limit: Int,
        ): List<LikedTrackBackup> {
            if (arr == null) return emptyList()
            return buildList {
                for (i in 0 until minOf(arr.length(), limit)) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.optString("id").trim()
                    val artist = item.optString("artist").trim()
                    val title = item.optString("title").trim()
                    val thumb = item.optString("thumbnail").trim().takeIf { it.isNotBlank() }
                    if (id.isNotBlank() || title.isNotBlank()) {
                        add(LikedTrackBackup(id = id, artist = artist, title = title, thumbnail = thumb))
                    }
                }
            }
        }

        val likedBackup = parseTrackList(obj.optJSONArray("likedTracksBackup"), MAX_LIKED_BACKUP)
        val lastWave = parseTrackList(obj.optJSONArray("lastWaveTracks"), 40)
        val extTaste = parseTrackList(obj.optJSONArray("externalTasteProfile"), MAX_EXTERNAL_TASTE)

        return CustomAppConfig(
            localAccountName = obj.optString("localAccountName", defaults.localAccountName).ifBlank { defaults.localAccountName },
            useLocalAccountOnly = obj.optBoolean("useLocalAccountOnly", defaults.useLocalAccountOnly),
            hybridResolverEnabled = obj.optBoolean("hybridResolverEnabled", defaults.hybridResolverEnabled),
            preferVkForExplicit = obj.optBoolean("preferVkForExplicit", defaults.preferVkForExplicit),
            cacheWaveAndDownloadLikes = obj.optBoolean("cacheWaveAndDownloadLikes", defaults.cacheWaveAndDownloadLikes),
            aiCensorshipCheckEnabled = obj.optBoolean("aiCensorshipCheckEnabled", defaults.aiCensorshipCheckEnabled),
            yandexToken = obj.optString("yandexToken", defaults.yandexToken),
            vkToken = obj.optString("vkToken", defaults.vkToken),
            lastFmApiKey = obj.optString("lastFmApiKey", defaults.lastFmApiKey),
            lastFmSecret = obj.optString("lastFmSecret", defaults.lastFmSecret),
            lastFmUsername = obj.optString("lastFmUsername", defaults.lastFmUsername),
            geminiApiKey = obj.optString("geminiApiKey", defaults.geminiApiKey),
            geminiModelMode = obj.optString("geminiModelMode", defaults.geminiModelMode).ifBlank { CustomAppConfig.MODEL_MODE_AUTO },
            aiPlaylistSize = obj.optInt("aiPlaylistSize", defaults.aiPlaylistSize).coerceIn(10, 100),
            useWaveAsHome = obj.optBoolean("useWaveAsHome", defaults.useWaveAsHome),
            mascotEnabled = obj.optBoolean("mascotEnabled", defaults.mascotEnabled),
            mascotFolderUri = obj.optString("mascotFolderUri", defaults.mascotFolderUri),
            neonColorMode = enumByName(obj.optString("neonColorMode"), defaults.neonColorMode),
            waveCharacter = enumByName(obj.optString("waveCharacter"), defaults.waveCharacter),
            waveEra = enumByName(obj.optString("waveEra"), defaults.waveEra),
            waveLanguage = enumByName(obj.optString("waveLanguage"), defaults.waveLanguage),
            waveMood = enumByName(obj.optString("waveMood"), defaults.waveMood),
            trackSourceOverrides = overrides,
            dislikedTracks = disliked,
            artistReputation = repMap,
            likedTracksBackup = likedBackup,
            lastWaveTracks = lastWave,
            externalTasteProfile = extTaste,
            localArtistStats = localStats,
            playedTrackTimestamps = playedTimestamps,
            blacklistedVkAudioIds = blacklistedVk,
            learnedCensoredArtists = learnedCensored,
            lastTasteScanMs = obj.optLong("lastTasteScanMs", 0L).coerceAtLeast(0L),
        )
    }

    private inline fun <reified T : Enum<T>> enumByName(
        name: String?,
        fallback: T,
    ): T = enumValues<T>().firstOrNull { it.name == name } ?: fallback

    companion object {
        private const val PREFS_NAME = "stocky_custom_engine_prefs"
        private const val CENSORSHIP_PREFS_NAME = "stocky_censorship_cache_prefs"
        private const val KEY_CONFIG_JSON = "custom_config_v2"
        private const val MAX_LIKED_BACKUP = 5_000
        private const val MAX_EXTERNAL_TASTE = 1_500
        private const val MAX_LOCAL_ARTISTS = 400
        private const val MAX_PLAYED_HISTORY = 300
        private const val MAX_BLACKLISTED_VK = 600
        private const val MAX_PLAYED_HISTORY_AGE_MS = 72L * 60 * 60 * 1_000L
        const val DEFAULT_REPEAT_COOLDOWN_MS = 16L * 60 * 60 * 1_000L

        fun splitArtistNames(raw: String): List<String> =
            raw
                .split(",", "&", "feat.", "ft.", "featuring", "×")
                .map { it.trim() }
                .filter { it.length >= 2 }
                .distinctBy { it.lowercase() }

        fun buildTrackKey(
            artist: String,
            title: String,
        ): String {
            val a = TrackMatcher.normalize(artist)
            val t = TrackMatcher.normalize(title)
            if (t.isBlank()) return ""
            return "$a::$t"
        }

        fun buildArtistKey(artist: String): String {
            val primary =
                artist
                    .substringBefore(",")
                    .substringBefore("&")
                    .substringBefore("feat.")
                    .substringBefore("ft.")
                    .trim()
            return TrackMatcher.normalize(primary)
        }
    }
}
