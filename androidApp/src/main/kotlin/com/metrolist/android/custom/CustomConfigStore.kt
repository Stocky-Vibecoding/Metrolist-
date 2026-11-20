package com.metrolist.android.custom

import android.content.Context
import com.metrolist.shared.api.lastfm.LastFM
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class YandexPlusStatus {
    UNKNOWN,
    ACTIVE,
    NO_PLUS,
    INVALID_TOKEN,
    NO_TOKEN,
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
    val hybridResolverEnabled: Boolean = true,
    val preferVkForExplicit: Boolean = true,
    val yandexToken: String = "",
    val vkToken: String = "",
    val lastFmApiKey: String = "",
    val lastFmSecret: String = "",
    val lastFmUsername: String = "",
    val geminiApiKey: String = "",
    val geminiModelMode: String = MODEL_MODE_AUTO,
    val aiPlaylistSize: Int = 20,
    val useWaveAsHome: Boolean = true,
    val mascotEnabled: Boolean = true,
    val mascotFolderUri: String = "",
    val neonColorMode: NeonColorMode = NeonColorMode.PURPLE,
    val waveCharacter: WaveCharacter = WaveCharacter.DEFAULT,
    val waveLanguage: WaveLanguage = WaveLanguage.ANY,
    val waveMood: WaveMood = WaveMood.ANY,
) {
    fun waveFilterSummary(): String {
        val parts =
            buildList {
                if (waveCharacter != WaveCharacter.DEFAULT) add(waveCharacter.label)
                if (waveLanguage != WaveLanguage.ANY) add(waveLanguage.label)
                if (waveMood != WaveMood.ANY) add(waveMood.label)
            }
        return if (parts.isEmpty()) "Настроить" else parts.joinToString(" • ")
    }

    companion object {
        const val MODEL_MODE_AUTO = "AUTO"
    }
}

class CustomConfigStore(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    fun exportToJson(): String = toJson(_config.value).toString(2)

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

    private fun toJson(cfg: CustomAppConfig): JSONObject =
        JSONObject().apply {
            put("hybridResolverEnabled", cfg.hybridResolverEnabled)
            put("preferVkForExplicit", cfg.preferVkForExplicit)
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
            put("waveLanguage", cfg.waveLanguage.name)
            put("waveMood", cfg.waveMood.name)
        }

    private fun fromJson(obj: JSONObject): CustomAppConfig {
        val defaults = CustomAppConfig()
        return CustomAppConfig(
            hybridResolverEnabled = obj.optBoolean("hybridResolverEnabled", defaults.hybridResolverEnabled),
            preferVkForExplicit = obj.optBoolean("preferVkForExplicit", defaults.preferVkForExplicit),
            yandexToken = obj.optString("yandexToken", defaults.yandexToken),
            vkToken = obj.optString("vkToken", defaults.vkToken),
            lastFmApiKey = obj.optString("lastFmApiKey", defaults.lastFmApiKey),
            lastFmSecret = obj.optString("lastFmSecret", defaults.lastFmSecret),
            lastFmUsername = obj.optString("lastFmUsername", defaults.lastFmUsername),
            geminiApiKey = obj.optString("geminiApiKey", defaults.geminiApiKey),
            geminiModelMode = obj.optString("geminiModelMode", defaults.geminiModelMode).ifBlank { CustomAppConfig.MODEL_MODE_AUTO },
            aiPlaylistSize = obj.optInt("aiPlaylistSize", defaults.aiPlaylistSize).coerceIn(10, 40),
            useWaveAsHome = obj.optBoolean("useWaveAsHome", defaults.useWaveAsHome),
            mascotEnabled = obj.optBoolean("mascotEnabled", defaults.mascotEnabled),
            mascotFolderUri = obj.optString("mascotFolderUri", defaults.mascotFolderUri),
            neonColorMode = enumByName(obj.optString("neonColorMode"), defaults.neonColorMode),
            waveCharacter = enumByName(obj.optString("waveCharacter"), defaults.waveCharacter),
            waveLanguage = enumByName(obj.optString("waveLanguage"), defaults.waveLanguage),
            waveMood = enumByName(obj.optString("waveMood"), defaults.waveMood),
        )
    }

    private inline fun <reified T : Enum<T>> enumByName(
        name: String?,
        fallback: T,
    ): T = enumValues<T>().firstOrNull { it.name == name } ?: fallback

    companion object {
        private const val PREFS_NAME = "stocky_custom_engine_prefs"
        private const val KEY_CONFIG_JSON = "custom_config_v2"
    }
}
