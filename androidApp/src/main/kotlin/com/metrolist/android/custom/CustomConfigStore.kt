package com.metrolist.android.custom

import android.content.Context
import com.metrolist.shared.api.lastfm.LastFM
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

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

enum class WaveCharacter(
    val label: String,
    val promptHint: String,
) {
    DEFAULT("Обычная", "сбалансированный микс знакомых и новых треков по вкусу пользователя"),
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
        CustomMediaCacheManager.init(appContext)
        applyRuntimeIntegrations(_config.value)
    }

    fun update(transform: (CustomAppConfig) -> CustomAppConfig) {
        val previous = _config.value
        val updated = transform(previous)
        saveAndPublish(updated, yandexTokenChanged = previous.yandexToken != updated.yandexToken)
    }

    fun setYandexPlusStatus(status: YandexPlusStatus) {
        _yandexPlusStatus.value = status
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
    ) {
        val tKey = buildTrackKey(artist, title)
        val aKey = buildArtistKey(artist)
        update { cfg ->
            val nextDisliked =
                if (tKey.isNotBlank()) cfg.dislikedTracks - tKey else cfg.dislikedTracks
            val nextRep = cfg.artistReputation.toMutableMap()
            if (aKey.isNotBlank()) {
                val prev = nextRep[aKey] ?: ArtistReputation()
                nextRep[aKey] = prev.copy(likes = prev.likes + 1)
            }
            val nextLiked =
                if (songId.isNotBlank() || title.isNotBlank()) {
                    val entry = LikedTrackBackup(id = songId.trim(), artist = artist.trim(), title = title.trim())
                    (listOf(entry) + cfg.likedTracksBackup.filterNot {
                        (entry.id.isNotBlank() && it.id == entry.id) ||
                            (it.artist.equals(entry.artist, ignoreCase = true) && it.title.equals(entry.title, ignoreCase = true))
                    }).take(2000)
                } else {
                    cfg.likedTracksBackup
                }
            cfg.copy(
                dislikedTracks = nextDisliked,
                artistReputation = nextRep,
                likedTracksBackup = nextLiked,
            )
        }
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

    fun exportToJson(extraLikedIds: Set<String> = emptySet()): String =
        toJson(_config.value, extraLikedIds).toString(2)

    fun importFromJson(rawJson: String): Result<CustomAppConfig> =
        runCatching {
            val parsed = fromJson(JSONObject(rawJson.trim()))
            val previous = _config.value
            saveAndPublish(parsed, yandexTokenChanged = previous.yandexToken != parsed.yandexToken)
            parsed
        }

    private fun saveAndPublish(
        updated: CustomAppConfig,
        yandexTokenChanged: Boolean,
    ) {
        prefs.edit().putString(KEY_CONFIG_JSON, toJson(updated).toString()).apply()
        _config.value = updated
        if (yandexTokenChanged) {
            _yandexPlusStatus.value =
                if (updated.yandexToken.isBlank()) YandexPlusStatus.NO_TOKEN else YandexPlusStatus.UNKNOWN
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
            put("schemaVersion", 4)
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
                        .put("title", item.title),
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

        val likedBackup =
            buildList<LikedTrackBackup> {
                val arr = obj.optJSONArray("likedTracksBackup") ?: return@buildList
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.optString("id").trim()
                    val artist = item.optString("artist").trim()
                    val title = item.optString("title").trim()
                    if (id.isNotBlank() || title.isNotBlank()) {
                        add(LikedTrackBackup(id = id, artist = artist, title = title))
                    }
                }
            }

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
