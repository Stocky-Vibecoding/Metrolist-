package com.metrolist.android.custom

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class AiTrackSuggestion(
    val artist: String,
    val title: String,
)

class GeminiClient(
    private val configStore: CustomConfigStore,
) {
    private val cooldownUntilMs = ConcurrentHashMap<String, Long>()
    private val consecutive429 = ConcurrentHashMap<String, Int>()
    private val unavailableModels = ConcurrentHashMap.newKeySet<String>()

    private val _activeModel = MutableStateFlow<String?>(null)
    val activeModel: StateFlow<String?> = _activeModel.asStateFlow()

    suspend fun generateTrackList(
        prompt: String,
        count: Int,
    ): Result<List<AiTrackSuggestion>> {
        val systemInstruction =
            "Ты музыкальный куратор. Отвечай СТРОГО валидным JSON-массивом объектов без пояснений и без markdown. " +
                "Формат каждого объекта: {\"artist\":\"Имя артиста\",\"title\":\"Название трека\"}. " +
                "Верни ровно $count реальных, существующих треков без дубликатов."

        return generateText(
            prompt = prompt,
            systemInstruction = systemInstruction,
            expectJson = true,
            temperature = 0.85,
        ).mapCatching { raw ->
            parseTrackSuggestions(raw).also { list ->
                check(list.isNotEmpty()) { "Gemini вернул пустой список треков" }
            }
        }
    }

    suspend fun generateMascotLine(
        artist: String,
        title: String,
        moodHint: String? = null,
    ): String? {
        val cfg = configStore.current
        if (cfg.geminiApiKey.isBlank()) return null

        val prompt =
            buildString {
                append("Сейчас играет трек: $artist — $title.")
                if (!moodHint.isNullOrBlank()) append(" Настроение волны: $moodHint.")
                append(" Напиши одну короткую (до 9 слов), живую и слегка ироничную фразу на русском о звучании или вайбе этого трека. Без кавычек, без эмодзи, без вступлений.")
            }

        return generateText(
            prompt = prompt,
            systemInstruction = null,
            expectJson = false,
            temperature = 0.9,
        ).getOrNull()
            ?.replace("\"", "")
            ?.replace("\n", " ")
            ?.trim()
            ?.takeIf { it.length in 6..110 }
    }

    suspend fun generateMascotPhrasePack(
        moodHint: String? = null,
        count: Int = 10,
    ): List<String> {
        val cfg = configStore.current
        if (cfg.geminiApiKey.isBlank()) return emptyList()

        val systemInstruction =
            "Отвечай СТРОГО валидным JSON-массивом строк без пояснений и без markdown. " +
                "Формат: [\"Фраза 1\", \"Фраза 2\"]."

        val prompt =
            buildString {
                append("Придумай $count коротких (до 9 слов каждая), живых и слегка ироничных фраз на русском от лица музыкального маскота в плеере")
                if (!moodHint.isNullOrBlank()) append(" под настроение «$moodHint»")
                append(". Без упоминания конкретных треков, без эмодзи, без кавычек внутри строк, чтобы подходили под разные композиции.")
            }

        val raw =
            generateText(
                prompt = prompt,
                systemInstruction = systemInstruction,
                expectJson = true,
                temperature = 0.95,
            ).getOrNull() ?: return emptyList()

        return parseStringArray(raw)
    }

    suspend fun testConnection(apiKeyOverride: String? = null): Result<String> {
        val key = (apiKeyOverride ?: configStore.current.geminiApiKey).trim()
        if (key.isBlank()) return Result.failure(IllegalStateException("Укажи Gemini API ключ"))
        return generateText(
            prompt = "Ответь одним словом: OK",
            systemInstruction = null,
            expectJson = false,
            temperature = 0.0,
            apiKeyOverride = key,
        ).map { _activeModel.value ?: DEFAULT_MODELS.first() }
    }

    private suspend fun generateText(
        prompt: String,
        systemInstruction: String?,
        expectJson: Boolean,
        temperature: Double,
        apiKeyOverride: String? = null,
    ): Result<String> {
        val cfg = configStore.current
        val apiKey = (apiKeyOverride ?: cfg.geminiApiKey).trim()
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("Не задан Gemini API ключ"))
        }

        val modelsToTry = resolveCandidateModels(cfg.geminiModelMode)
        var lastError: Throwable = IllegalStateException("Все модели Gemini временно недоступны")

        for (model in modelsToTry) {
            val payload = buildRequestPayload(prompt, systemInstruction, expectJson, temperature)
            val url = "${apiBaseUrl()}/models/$model:generateContent"

            val response =
                runCatching {
                    CustomHttp.postJson(
                        url = url,
                        jsonBody = payload,
                        headers = mapOf("x-goog-api-key" to apiKey),
                        timeoutMs = 25_000,
                    )
                }.getOrElse { err ->
                    lastError = err
                    Log.w(TAG, "Network error on $model: ${err.message}")
                    continue
                }

            when {
                response.isSuccess -> {
                    val text = extractResponseText(response.body)
                    if (!text.isNullOrBlank()) {
                        consecutive429.remove(model)
                        cooldownUntilMs.remove(model)
                        _activeModel.value = model
                        return Result.success(text)
                    }
                    lastError = IllegalStateException("Пустой ответ от $model")
                }

                response.code == 429 -> {
                    val hits = (consecutive429[model] ?: 0) + 1
                    consecutive429[model] = hits
                    val waitMs =
                        response.retryAfterSeconds?.let { it * 1_000L }
                            ?: if (hits >= 2) COOLDOWN_LONG_MS else COOLDOWN_SHORT_MS
                    cooldownUntilMs[model] = System.currentTimeMillis() + waitMs
                    Log.w(TAG, "Model $model hit 429 (hits=$hits), cooldown ${waitMs / 1000}s")
                    lastError = IllegalStateException("Лимит 429 на $model")
                }

                response.code == 503 || response.code == 500 -> {
                    cooldownUntilMs[model] = System.currentTimeMillis() + COOLDOWN_SHORT_MS
                    Log.w(TAG, "Model $model returned ${response.code}")
                    lastError = IllegalStateException("Сервер ${response.code} на $model")
                }

                response.code == 404 -> {
                    unavailableModels.add(model)
                    Log.w(TAG, "Model $model not found (404)")
                    lastError = IllegalStateException("Модель $model недоступна (404)")
                }

                response.code == 400 || response.code == 401 || response.code == 403 -> {
                    val msg = extractErrorMessage(response.body) ?: "HTTP ${response.code}"
                    return Result.failure(IllegalStateException("Ошибка ключа Gemini: $msg"))
                }

                else -> {
                    val msg = extractErrorMessage(response.body) ?: "HTTP ${response.code}"
                    lastError = IllegalStateException("Gemini $model: $msg")
                }
            }
        }

        return Result.failure(lastError)
    }

    private fun resolveCandidateModels(mode: String): List<String> {
        val now = System.currentTimeMillis()
        if (mode != CustomAppConfig.MODEL_MODE_AUTO && mode.isNotBlank()) {
            val ordered = (listOf(mode) + DEFAULT_MODELS).distinct()
            val ready = ordered.filter { it !in unavailableModels && (cooldownUntilMs[it] ?: 0L) <= now }
            return ready.ifEmpty { ordered.filterNot { it in unavailableModels } }.ifEmpty { listOf(mode) }
        }

        val available = DEFAULT_MODELS.filterNot { it in unavailableModels }
        val ready = available.filter { (cooldownUntilMs[it] ?: 0L) <= now }
        if (ready.isNotEmpty()) return ready
        return available.sortedBy { cooldownUntilMs[it] ?: 0L }.ifEmpty { DEFAULT_MODELS }
    }

    private fun buildRequestPayload(
        prompt: String,
        systemInstruction: String?,
        expectJson: Boolean,
        temperature: Double,
    ): String {
        val root = JSONObject()
        if (!systemInstruction.isNullOrBlank()) {
            root.put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemInstruction)),
                ),
            )
        }
        root.put(
            "contents",
            JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", prompt))),
            ),
        )
        val genConfig = JSONObject().put("temperature", temperature)
        if (expectJson) {
            genConfig.put("responseMimeType", "application/json")
        }
        root.put("generationConfig", genConfig)
        return root.toString()
    }

    private fun extractResponseText(body: String): String? =
        runCatching {
            val root = JSONObject(body)
            val candidates = root.optJSONArray("candidates") ?: return@runCatching null
            val parts =
                candidates
                    .optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?: return@runCatching null
            buildString {
                for (i in 0 until parts.length()) {
                    append(parts.optJSONObject(i)?.optString("text").orEmpty())
                }
            }.trim()
        }.getOrNull()

    private fun extractErrorMessage(body: String): String? =
        runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        }.getOrNull()

    private fun parseTrackSuggestions(raw: String): List<AiTrackSuggestion> {
        val fence = "`".repeat(3)
        val cleaned =
            raw
                .trim()
                .removePrefix(fence + "json")
                .removePrefix(fence)
                .removeSuffix(fence)
                .trim()

        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()

        val array = JSONArray(cleaned.substring(start, end + 1))
        val seen = HashSet<String>()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val artist = obj.optString("artist").trim()
                val title = obj.optString("title").trim()
                if (artist.isBlank() || title.isBlank()) continue
                val key = "${artist.lowercase()}::${title.lowercase()}"
                if (seen.add(key)) {
                    add(AiTrackSuggestion(artist = artist, title = title))
                }
            }
        }
    }

    private fun parseStringArray(raw: String): List<String> {
        val fence = "`".repeat(3)
        val cleaned =
            raw
                .trim()
                .removePrefix(fence + "json")
                .removePrefix(fence)
                .removeSuffix(fence)
                .trim()

        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()

        val array = JSONArray(cleaned.substring(start, end + 1))
        return buildList {
            for (i in 0 until array.length()) {
                val line =
                    array
                        .optString(i)
                        .replace("\"", "")
                        .replace("\n", " ")
                        .trim()
                if (line.length in 6..110) add(line)
            }
        }.distinct()
    }

    companion object {
        private const val TAG = "GeminiClient"
        private const val COOLDOWN_SHORT_MS = 2 * 60 * 1_000L
        private const val COOLDOWN_LONG_MS = 15 * 60 * 1_000L

        private fun apiBaseUrl(): String {
            val host = listOf("generativelanguage", "googleapis", "com").joinToString(".")
            return "https://" + host + "/v1beta"
        }

        val DEFAULT_MODELS =
            listOf(
                "gemini-2.5-flash",
                "gemini-2.5-flash-lite",
                "gemini-2.0-flash",
                "gemini-2.0-flash-lite",
                "gemini-2.5-pro",
            )
    }
}

class MascotPhraseBank(
    context: Context,
    private val geminiClient: GeminiClient,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var lastShownLine: String? = null
    private var tracksSinceMoodLine = 1

    suspend fun nextPhraseForTrack(
        artist: String,
        title: String,
        moodKey: String,
        moodLabel: String,
        hasGeminiKey: Boolean,
    ): String? {
        val trackKey = "${artist.trim().lowercase()}::${title.trim().lowercase()}"

        // 1. Сначала смотрим кэш конкретного трека (0 запросов к API)
        loadTrackCache().optString(trackKey).takeIf { it.isNotBlank() }?.let { cached ->
            lastShownLine = cached
            tracksSinceMoodLine = 0
            return cached
        }

        val now = System.currentTimeMillis()
        val lastTrackAiMs = prefs.getLong(KEY_LAST_TRACK_AI_MS, 0L)

        // 2. Раз в ~8 минут позволяем ИИ написать персональную фразу под трек и сохраняем её навсегда
        if (hasGeminiKey && now - lastTrackAiMs >= TRACK_AI_COOLDOWN_MS) {
            prefs.edit().putLong(KEY_LAST_TRACK_AI_MS, now).apply()
            val generated = geminiClient.generateMascotLine(artist = artist, title = title, moodHint = moodLabel)
            if (!generated.isNullOrBlank()) {
                saveTrackPhrase(trackKey, generated)
                lastShownLine = generated
                tracksSinceMoodLine = 0
                return generated
            }
        }

        // 3. Для остальных треков — показываем фразу из локального пула примерно через трек
        tracksSinceMoodLine += 1
        if (tracksSinceMoodLine < 2) {
            return null
        }

        var pool = loadMoodPool(moodKey)
        val lastPackMs = prefs.getLong(KEY_LAST_PACK_PREFIX + moodKey, 0L)
        if (hasGeminiKey && pool.size < MIN_POOL_SIZE && now - lastPackMs >= PACK_REFRESH_COOLDOWN_MS) {
            prefs.edit().putLong(KEY_LAST_PACK_PREFIX + moodKey, now).apply()
            val fresh = geminiClient.generateMascotPhrasePack(moodHint = moodLabel, count = 10)
            if (fresh.isNotEmpty()) {
                pool = (fresh + pool).distinct().take(MAX_MOOD_POOL)
                saveMoodPool(moodKey, pool)
            }
        }

        if (pool.isEmpty()) return null

        val candidates = pool.filterNot { it == lastShownLine }.ifEmpty { pool }
        val chosen = candidates.random()
        lastShownLine = chosen
        tracksSinceMoodLine = 0
        return chosen
    }

    private fun loadTrackCache(): JSONObject =
        runCatching {
            JSONObject(prefs.getString(KEY_TRACK_CACHE, "{}").orEmpty())
        }.getOrElse { JSONObject() }

    private fun saveTrackPhrase(
        trackKey: String,
        phrase: String,
    ) {
        runCatching {
            val obj = loadTrackCache()
            obj.put(trackKey, phrase)
            while (obj.length() > MAX_TRACK_CACHE) {
                val oldestKey = obj.keys().next()
                obj.remove(oldestKey)
            }
            prefs.edit().putString(KEY_TRACK_CACHE, obj.toString()).apply()
        }
    }

    private fun loadMoodPool(moodKey: String): List<String> =
        runCatching {
            val arr = JSONArray(prefs.getString(KEY_MOOD_POOL_PREFIX + moodKey, "[]").orEmpty())
            buildList {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i).trim()
                    if (s.isNotBlank()) add(s)
                }
            }
        }.getOrDefault(emptyList())

    private fun saveMoodPool(
        moodKey: String,
        phrases: List<String>,
    ) {
        runCatching {
            val arr = JSONArray()
            phrases.take(MAX_MOOD_POOL).forEach { arr.put(it) }
            prefs.edit().putString(KEY_MOOD_POOL_PREFIX + moodKey, arr.toString()).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "metrolist_mascot_phrases"
        private const val KEY_TRACK_CACHE = "track_cache"
        private const val KEY_MOOD_POOL_PREFIX = "mood_pool_"
        private const val KEY_LAST_TRACK_AI_MS = "last_track_ai_ms"
        private const val KEY_LAST_PACK_PREFIX = "last_pack_ai_ms_"
        private const val MAX_TRACK_CACHE = 120
        private const val MAX_MOOD_POOL = 25
        private const val MIN_POOL_SIZE = 4
        private const val TRACK_AI_COOLDOWN_MS = 8 * 60 * 1_000L
        private const val PACK_REFRESH_COOLDOWN_MS = 30 * 60 * 1_000L
    }
}
