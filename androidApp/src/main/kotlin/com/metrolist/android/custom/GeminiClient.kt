package com.metrolist.android.custom

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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

    @Volatile
    private var lastUsedApiKey: String = ""

    @Volatile
    private var discoveredModels: List<String> = emptyList()

    @Volatile
    private var discoveredForKey: String = ""

    @Volatile
    private var lastDiscoveryMs: Long = 0L

    private val _activeModel = MutableStateFlow<String?>(null)
    val activeModel: StateFlow<String?> = _activeModel.asStateFlow()

    init {
        sharedInstance = this
    }

    fun hasActiveCooldown(): Boolean {
        val now = System.currentTimeMillis()
        return cooldownUntilMs.values.any { it > now }
    }

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

    suspend fun checkCensorshipRisk(
        artist: String,
        title: String,
    ): Boolean? {
        val cfg = configStore.current
        if (cfg.geminiApiKey.isBlank() || hasActiveCooldown()) return null

        val systemInstruction =
            "Отвечай СТРОГО валидным JSON без markdown: {\"censored\":true} или {\"censored\":false}."

        val prompt =
            "Оцени, подвержен ли трек \"$artist — $title\" цензуре, вырезанию строк, запикиванию или изъятию " +
                "на российских стримингах (Яндекс.Музыка) из-за ненормативной лексики, упоминаний веществ или ограничений РФ. " +
                "Если высокий риск цензуры на Яндексе — верни censored: true, иначе false."

        val raw =
            generateText(
                prompt = prompt,
                systemInstruction = systemInstruction,
                expectJson = true,
                temperature = 0.0,
            ).getOrNull() ?: return null

        return runCatching {
            val cleaned = stripCodeFences(raw)
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start < 0 || end <= start) return@runCatching null
            JSONObject(cleaned.substring(start, end + 1)).optBoolean("censored", false)
        }.getOrNull()
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
        resetStateIfKeyChanged(key)
        refreshDiscoveredModelsIfNeeded(key, force = true)
        return generateText(
            prompt = "Ответь одним словом: OK",
            systemInstruction = null,
            expectJson = false,
            temperature = 0.0,
            apiKeyOverride = key,
        ).map {
            val used = _activeModel.value ?: DEFAULT_MODELS.first()
            val total = discoveredModels.size
            if (total > 0) "$used (найдено моделей: $total)" else used
        }
    }

    private fun resetStateIfKeyChanged(apiKey: String) {
        if (apiKey != lastUsedApiKey) {
            lastUsedApiKey = apiKey
            cooldownUntilMs.clear()
            consecutive429.clear()
            unavailableModels.clear()
        }
    }

    private suspend fun refreshDiscoveredModelsIfNeeded(
        apiKey: String,
        force: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        if (!force && discoveredForKey == apiKey && discoveredModels.isNotEmpty() && now - lastDiscoveryMs < DISCOVERY_TTL_MS) {
            return
        }
        val found =
            try {
                val url = "${apiBaseUrl()}/models?pageSize=100"
                val resp = CustomHttp.get(url, headers = mapOf("x-goog-api-key" to apiKey), timeoutMs = 8_000)
                if (!resp.isSuccess) {
                    emptyList()
                } else {
                    val arr = JSONObject(resp.body).optJSONArray("models")
                    if (arr == null) {
                        emptyList()
                    } else {
                        val badTokens =
                            listOf(
                                "embedding",
                                "imagen",
                                "veo",
                                "tts",
                                "audio",
                                "live",
                                "aqa",
                                "robotics",
                                "computer-use",
                                "deep-research",
                                "transcribe",
                                "vision",
                                "nano",
                            )
                        val candidates = mutableListOf<String>()
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val rawName = obj.optString("name").removePrefix("models/").trim()
                            if (rawName.isBlank()) continue
                            val lower = rawName.lowercase()
                            if (!lower.startsWith("gemini-") && !lower.startsWith("gemma-3-")) continue
                            if (badTokens.any { lower.contains(it) }) continue
                            val methods = obj.optJSONArray("supportedGenerationMethods")
                            var supportsGenerate = false
                            if (methods != null) {
                                for (j in 0 until methods.length()) {
                                    if (methods.optString(j) == "generateContent") {
                                        supportsGenerate = true
                                        break
                                    }
                                }
                            }
                            if (supportsGenerate) candidates.add(rawName)
                        }
                        candidates.distinct().sortedBy { modelPriority(it) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }

        if (found.isNotEmpty()) {
            discoveredModels = found
            discoveredForKey = apiKey
            lastDiscoveryMs = now
            unavailableModels.removeAll(found.toSet())
            Log.d(TAG, "Discovered ${found.size} models: ${found.take(8).joinToString()}")
        }
    }

    private fun modelPriority(name: String): Int {
        val lower = name.lowercase()
        return when {
            lower == "gemini-2.5-flash" -> 1
            lower == "gemini-2.5-flash-lite" -> 2
            lower.contains("3") && lower.contains("flash-lite") -> 3
            lower.contains("3") && lower.contains("flash") -> 4
            lower == "gemini-2.0-flash" -> 5
            lower == "gemini-2.0-flash-lite" -> 6
            lower.contains("flash-lite") -> 7
            lower.contains("flash") -> 8
            lower.contains("2.5-pro") -> 12
            lower.contains("pro") -> 14
            lower.startsWith("gemma-3-27b") -> 20
            lower.startsWith("gemma-3-12b") -> 21
            lower.startsWith("gemma-3-") -> 22
            else -> 30
        }
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

        resetStateIfKeyChanged(apiKey)

        if (discoveredModels.isEmpty() || discoveredForKey != apiKey) {
            refreshDiscoveredModelsIfNeeded(apiKey, force = false)
        }

        val modelsToTry = resolveCandidateModels(cfg.geminiModelMode)
        val attempts = mutableListOf<String>()

        for (model in modelsToTry) {
            val payload = buildRequestPayload(model, prompt, systemInstruction, expectJson, temperature)
            val url = "${apiBaseUrl()}/models/$model:generateContent"

            val response =
                try {
                    CustomHttp.postJson(
                        url = url,
                        jsonBody = payload,
                        headers = mapOf("x-goog-api-key" to apiKey),
                        timeoutMs = 25_000,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (err: Exception) {
                    attempts.add("${shortName(model)}:net")
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
                    attempts.add("${shortName(model)}:empty")
                }

                response.code == 429 -> {
                    val hits = (consecutive429[model] ?: 0) + 1
                    consecutive429[model] = hits
                    val waitMs =
                        response.retryAfterSeconds?.let { (it * 1_000L).coerceAtMost(COOLDOWN_LONG_MS) }
                            ?: if (hits >= 2) COOLDOWN_LONG_MS else COOLDOWN_SHORT_MS
                    cooldownUntilMs[model] = System.currentTimeMillis() + waitMs
                    attempts.add("${shortName(model)}:429")
                    Log.w(TAG, "Model $model hit 429 (hits=$hits), cooldown ${waitMs / 1000}s")
                }

                response.code == 503 || response.code == 500 -> {
                    cooldownUntilMs[model] = System.currentTimeMillis() + COOLDOWN_SHORT_MS
                    attempts.add("${shortName(model)}:${response.code}")
                    Log.w(TAG, "Model $model returned ${response.code}")
                }

                response.code == 404 -> {
                    unavailableModels.add(model)
                    attempts.add("${shortName(model)}:404")
                    Log.w(TAG, "Model $model not found (404)")
                }

                response.code == 401 || response.code == 403 -> {
                    val msg = extractErrorMessage(response.body) ?: "HTTP ${response.code}"
                    return Result.failure(IllegalStateException("Ошибка ключа Gemini: $msg"))
                }

                response.code == 400 -> {
                    val msg = extractErrorMessage(response.body).orEmpty()
                    if (msg.contains("API key", ignoreCase = true) || msg.contains("API_KEY", ignoreCase = true)) {
                        return Result.failure(IllegalStateException("Ошибка ключа Gemini: $msg"))
                    }
                    unavailableModels.add(model)
                    attempts.add("${shortName(model)}:400")
                    Log.w(TAG, "Model $model rejected payload (400): $msg")
                }

                else -> {
                    attempts.add("${shortName(model)}:${response.code}")
                }
            }
        }

        val summary = attempts.take(5).joinToString(", ")
        return Result.failure(
            IllegalStateException(
                if (summary.isNotBlank()) "Лимит/ошибки моделей ($summary)" else "Все модели Gemini временно недоступны",
            ),
        )
    }

    private fun shortName(model: String): String =
        model.removePrefix("gemini-").removePrefix("gemma-")

    private fun resolveCandidateModels(mode: String): List<String> {
        val now = System.currentTimeMillis()
        val combinedPool =
            (discoveredModels + DEFAULT_MODELS)
                .distinct()
                .filterNot { it in unavailableModels }
                .ifEmpty { DEFAULT_MODELS }

        val ordered =
            if (mode != CustomAppConfig.MODEL_MODE_AUTO && mode.isNotBlank()) {
                (listOf(mode) + combinedPool).distinct()
            } else {
                combinedPool
            }

        val ready = ordered.filter { (cooldownUntilMs[it] ?: 0L) <= now }
        if (ready.isNotEmpty()) return ready
        return ordered.sortedBy { cooldownUntilMs[it] ?: 0L }
    }

    private fun buildRequestPayload(
        model: String,
        prompt: String,
        systemInstruction: String?,
        expectJson: Boolean,
        temperature: Double,
    ): String {
        val isGemma = model.startsWith("gemma-", ignoreCase = true)
        val root = JSONObject()
        val finalPrompt =
            if (isGemma && !systemInstruction.isNullOrBlank()) {
                "$systemInstruction\n\n$prompt"
            } else {
                prompt
            }

        if (!isGemma && !systemInstruction.isNullOrBlank()) {
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
                    .put("parts", JSONArray().put(JSONObject().put("text", finalPrompt))),
            ),
        )
        val genConfig = JSONObject().put("temperature", temperature)
        if (expectJson && !isGemma) {
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

    private fun stripCodeFences(raw: String): String {
        val fence = "`".repeat(3)
        return raw
            .trim()
            .removePrefix(fence + "json")
            .removePrefix(fence)
            .removeSuffix(fence)
            .trim()
    }

    private fun parseTrackSuggestions(raw: String): List<AiTrackSuggestion> =
        runCatching {
            val cleaned = stripCodeFences(raw)
            val start = cleaned.indexOf('[')
            val end = cleaned.lastIndexOf(']')
            if (start < 0 || end <= start) return@runCatching emptyList()

            val array = JSONArray(cleaned.substring(start, end + 1))
            val seen = HashSet<String>()
            buildList {
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
        }.getOrDefault(emptyList())

    private fun parseStringArray(raw: String): List<String> =
        runCatching {
            val cleaned = stripCodeFences(raw)
            val start = cleaned.indexOf('[')
            val end = cleaned.lastIndexOf(']')
            if (start < 0 || end <= start) return@runCatching emptyList()

            val array = JSONArray(cleaned.substring(start, end + 1))
            buildList {
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
        }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "GeminiClient"
        private const val COOLDOWN_SHORT_MS = 2 * 60 * 1_000L
        private const val COOLDOWN_LONG_MS = 15 * 60 * 1_000L
        private const val DISCOVERY_TTL_MS = 12 * 60 * 60 * 1_000L

        @Volatile
        var sharedInstance: GeminiClient? = null
            private set

        private fun apiBaseUrl(): String {
            val host = listOf("generativelanguage", "googleapis", "com").joinToString(".")
            return "https://" + host + "/v1beta"
        }

        val DEFAULT_MODELS =
            listOf(
                "gemini-2.5-flash",
                "gemini-2.5-flash-lite",
                "gemini-3-flash-preview",
                "gemini-2.0-flash",
                "gemini-2.0-flash-lite",
                "gemini-2.5-pro",
                "gemma-3-27b-it",
            )
    }
}

class MascotPhraseBank(
    context: Context,
    private val geminiClient: GeminiClient,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val prefsLock = Any()

    @Volatile
    private var lastShownLine: String? = null

    @Volatile
    private var tracksSinceMoodLine = 1

    suspend fun nextPhraseForTrack(
        artist: String,
        title: String,
        moodKey: String,
        moodLabel: String,
        hasGeminiKey: Boolean,
    ): String? =
        withContext(Dispatchers.IO) {
            val trackKey = "${artist.trim().lowercase()}::${title.trim().lowercase()}"

            synchronized(prefsLock) {
                loadTrackCache().optString(trackKey).takeIf { it.isNotBlank() }
            }?.let { cached ->
                lastShownLine = cached
                tracksSinceMoodLine = 0
                return@withContext cached
            }

            val now = System.currentTimeMillis()
            val lastTrackAiMs = prefs.getLong(KEY_LAST_TRACK_AI_MS, 0L)

            if (hasGeminiKey && !geminiClient.hasActiveCooldown() && now - lastTrackAiMs >= TRACK_AI_COOLDOWN_MS) {
                prefs.edit().putLong(KEY_LAST_TRACK_AI_MS, now).apply()
                val generated = geminiClient.generateMascotLine(artist = artist, title = title, moodHint = moodLabel)
                if (!generated.isNullOrBlank()) {
                    saveTrackPhrase(trackKey, generated)
                    lastShownLine = generated
                    tracksSinceMoodLine = 0
                    return@withContext generated
                }
            }

            tracksSinceMoodLine += 1
            if (tracksSinceMoodLine < 2) {
                return@withContext null
            }

            var pool = synchronized(prefsLock) { loadMoodPool(moodKey) }
            val lastPackMs = prefs.getLong(KEY_LAST_PACK_PREFIX + moodKey, 0L)
            if (hasGeminiKey && !geminiClient.hasActiveCooldown() && pool.size < MIN_POOL_SIZE && now - lastPackMs >= PACK_REFRESH_COOLDOWN_MS) {
                prefs.edit().putLong(KEY_LAST_PACK_PREFIX + moodKey, now).apply()
                val fresh = geminiClient.generateMascotPhrasePack(moodHint = moodLabel, count = 10)
                if (fresh.isNotEmpty()) {
                    pool = (fresh + pool).distinct().take(MAX_MOOD_POOL)
                    saveMoodPool(moodKey, pool)
                }
            }

            if (pool.isEmpty()) return@withContext null

            val candidates = pool.filterNot { it == lastShownLine }.ifEmpty { pool }
            val chosen = candidates.random()
            lastShownLine = chosen
            tracksSinceMoodLine = 0
            chosen
        }

    private fun loadTrackCache(): JSONObject =
        runCatching {
            JSONObject(prefs.getString(KEY_TRACK_CACHE, "{}").orEmpty())
        }.getOrElse { JSONObject() }

    private fun saveTrackPhrase(
        trackKey: String,
        phrase: String,
    ) {
        synchronized(prefsLock) {
            runCatching {
                val obj = loadTrackCache()
                obj.put(trackKey, phrase)
                while (obj.length() > MAX_TRACK_CACHE) {
                    val keys = obj.keys()
                    if (!keys.hasNext()) break
                    obj.remove(keys.next())
                }
                prefs.edit().putString(KEY_TRACK_CACHE, obj.toString()).apply()
            }
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
        synchronized(prefsLock) {
            runCatching {
                val arr = JSONArray()
                phrases.take(MAX_MOOD_POOL).forEach { arr.put(it) }
                prefs.edit().putString(KEY_MOOD_POOL_PREFIX + moodKey, arr.toString()).apply()
            }
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
