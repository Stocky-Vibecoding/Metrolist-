package com.metrolist.android.custom

import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

private data class VkAudioCandidate(
    val artist: String,
    val title: String,
    val rawTitle: String,
    val subtitle: String,
    val durationSec: Long,
    val url: String,
    val isExplicit: Boolean,
    val isOfficial: Boolean,
)

private enum class VkSearchEndpoint(val label: String) {
    CATALOG("catalog"),
    LEGACY("audio.search"),
}

private data class VkClientProfile(
    val label: String,
    val userAgent: String,
    val apiVersion: String,
    val endpoint: VkSearchEndpoint,
)

class VkMusicProvider(
    private val configStore: CustomConfigStore,
) {
    @Volatile
    private var activeProfile: VkClientProfile = CLIENT_PROFILES.first()

    @Volatile
    private var floodCooldownUntilMs: Long = 0L

    suspend fun checkToken(
        token: String = configStore.current.vkToken,
    ): Boolean = checkTokenDetailed(token).isSuccess

    suspend fun checkTokenDetailed(
        token: String = configStore.current.vkToken,
    ): Result<String> {
        val cleanToken = sanitizeToken(token)
        if (cleanToken.isBlank()) {
            return Result.failure(IllegalStateException("Токен ВК не указан"))
        }

        val notes = mutableListOf<String>()
        for ((index, profile) in CLIENT_PROFILES.withIndex()) {
            if (index > 0) delay(700L)
            val attempt = testSearchProfile(cleanToken, profile)
            if (attempt.isSuccess) {
                activeProfile = profile
                floodCooldownUntilMs = 0L
                val count = attempt.getOrDefault(0)
                return Result.success("ВК аудио работает (${profile.label}/${profile.endpoint.label}, треков: $count)")
            }
            val errMsg = attempt.exceptionOrNull()?.message.orEmpty()
            if (errMsg.contains("#9")) {
                floodCooldownUntilMs = System.currentTimeMillis() + FLOOD_COOLDOWN_MS
                return Result.failure(
                    IllegalStateException("VK #9: Флуд-контроль (часто из-за VPN). Выключи VPN для ВК или подожди 3 мин"),
                )
            }
            if (errMsg.contains("#5")) {
                return Result.failure(IllegalStateException("Токен ВК истёк или невалиден (#5) — обнови через Kate Mobile"))
            }
            notes.add("${profile.label}/${profile.endpoint.label}: $errMsg")
        }

        return Result.failure(IllegalStateException("ВК аудио недоступно (${notes.joinToString("; ")})"))
    }

    suspend fun resolve(reference: TrackReference): ResolvedCustomStream? =
        resolveWithVariant(reference, variantIndex = 0)?.first

    suspend fun resolveWithVariant(
        reference: TrackReference,
        variantIndex: Int = 0,
    ): Pair<ResolvedCustomStream, String?>? {
        if (System.currentTimeMillis() < floodCooldownUntilMs) return null
        val token = sanitizeToken(configStore.current.vkToken)
        if (token.isBlank()) return null

        val query = listOf(reference.artist, reference.title).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return null

        val candidates = searchAudio(token, query)
        val strict = rankMatches(candidates, reference, relaxed = false)
        val ranked =
            if (variantIndex > 0) {
                (strict + rankMatches(candidates, reference, relaxed = true))
                    .distinctBy { it.url.substringBefore('?') }
            } else {
                strict.ifEmpty { rankMatches(candidates, reference, relaxed = true) }
            }
        if (ranked.isEmpty()) return null

        val idx = variantIndex.coerceAtLeast(0) % ranked.size
        val best = ranked[idx]
        val variantTag = if (ranked.size > 1 && variantIndex > 0) "#${idx + 1}/${ranked.size}" else null

        val isHls = best.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        Log.d(
            TAG,
            "Resolved via VK (${if (isHls) "HLS" else "MP3"} ${variantTag ?: "#1"}, official=${best.isOfficial}, dur=${best.durationSec}s): ${best.artist} - ${best.title}",
        )

        val stream =
            ResolvedCustomStream(
                url = best.url,
                headers = mapOf("User-Agent" to activeProfile.userAgent),
                mimeType = if (isHls) "application/x-mpegURL" else "audio/mpeg",
                bitrate = null,
                durationSec = best.durationSec.takeIf { it > 0L },
                clientName = "vk",
                isHls = isHls,
            )
        return stream to variantTag
    }

    private suspend fun testSearchProfile(
        token: String,
        profile: VkClientProfile,
    ): Result<Int> =
        runCatching<Result<Int>> {
            val url = buildSearchUrl(token = token, query = "Кино Группа крови", count = 3, profile = profile)
            val response = CustomHttp.get(url, headers = mapOf("User-Agent" to profile.userAgent), timeoutMs = 8_000)
            if (!response.isSuccess) {
                return@runCatching Result.failure<Int>(IllegalStateException("HTTP ${response.code}"))
            }
            val root = JSONObject(response.body)
            if (root.has("error")) {
                val err = root.optJSONObject("error")
                val code = err?.optInt("error_code", -1) ?: -1
                val msg = err?.optString("error_msg").orEmpty()
                Result.failure<Int>(IllegalStateException("#$code $msg"))
            } else {
                val respObj = root.optJSONObject("response")
                val arr =
                    when (profile.endpoint) {
                        VkSearchEndpoint.CATALOG -> respObj?.optJSONArray("audios") ?: respObj?.optJSONArray("items")
                        VkSearchEndpoint.LEGACY -> respObj?.optJSONArray("items") ?: respObj?.optJSONArray("audios")
                    }
                val parsed = parseAudioArray(arr)
                if (respObj != null) {
                    Result.success(parsed.size)
                } else {
                    Result.failure<Int>(IllegalStateException("пустой ответ"))
                }
            }
        }.getOrElse { Result.failure(it) }

    private suspend fun searchAudio(
        token: String,
        query: String,
    ): List<VkAudioCandidate> {
        val profilesToTry = (listOf(activeProfile) + CLIENT_PROFILES).distinct()
        for (profile in profilesToTry) {
            if (System.currentTimeMillis() < floodCooldownUntilMs) return emptyList()
            val result: List<VkAudioCandidate>? =
                runCatching<List<VkAudioCandidate>?> {
                    val url = buildSearchUrl(token = token, query = query, count = 35, profile = profile)
                    val response =
                        CustomHttp.get(
                            url = url,
                            headers = mapOf("User-Agent" to profile.userAgent),
                            timeoutMs = 6_000,
                        )
                    if (!response.isSuccess) return@runCatching null
                    val root = JSONObject(response.body)
                    if (root.has("error")) {
                        val code = root.optJSONObject("error")?.optInt("error_code", -1) ?: -1
                        Log.w(TAG, "VK ${profile.label}/${profile.endpoint.label} error #$code")
                        if (code == 9) {
                            floodCooldownUntilMs = System.currentTimeMillis() + FLOOD_COOLDOWN_MS
                            return@runCatching emptyList<VkAudioCandidate>()
                        }
                        return@runCatching null
                    }
                    activeProfile = profile
                    val respObj = root.optJSONObject("response") ?: return@runCatching emptyList<VkAudioCandidate>()
                    val array =
                        when (profile.endpoint) {
                            VkSearchEndpoint.CATALOG -> respObj.optJSONArray("audios") ?: respObj.optJSONArray("items")
                            VkSearchEndpoint.LEGACY -> respObj.optJSONArray("items") ?: respObj.optJSONArray("audios")
                        }
                    parseAudioArray(array)
                }.getOrNull()

            if (result != null) return result
        }
        return emptyList()
    }

    private fun buildSearchUrl(
        token: String,
        query: String,
        count: Int,
        profile: VkClientProfile,
    ): String {
        val encodedQuery = CustomHttp.urlEncode(query)
        val encodedToken = CustomHttp.urlEncode(token)
        return when (profile.endpoint) {
            VkSearchEndpoint.CATALOG ->
                "${apiBaseUrl()}/catalog.getAudioSearch" +
                    "?query=$encodedQuery" +
                    "&need_blocks=1" +
                    "&lang=ru" +
                    "&access_token=$encodedToken" +
                    "&v=${profile.apiVersion}"

            VkSearchEndpoint.LEGACY ->
                "${apiBaseUrl()}/audio.search" +
                    "?q=$encodedQuery" +
                    "&count=$count" +
                    "&sort=2" +
                    "&auto_complete=1" +
                    "&lang=ru" +
                    "&access_token=$encodedToken" +
                    "&v=${profile.apiVersion}"
        }
    }

    private fun parseAudioArray(items: JSONArray?): List<VkAudioCandidate> {
        if (items == null) return emptyList()
        return buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val streamUrl = item.optString("url").trim()
                if (streamUrl.isBlank()) continue
                val rawTitle = item.optString("title").trim()
                val subtitle = item.optString("subtitle").trim()
                val fullTitle = if (subtitle.isNotBlank()) "$rawTitle ($subtitle)" else rawTitle
                val isOfficial =
                    item.optJSONObject("album") != null ||
                        item.optBoolean("is_licensed", false) ||
                        (item.optJSONArray("main_artists")?.length() ?: 0) > 0
                add(
                    VkAudioCandidate(
                        artist = item.optString("artist").trim(),
                        title = fullTitle,
                        rawTitle = rawTitle,
                        subtitle = subtitle,
                        durationSec = item.optLong("duration", 0L),
                        url = streamUrl,
                        isExplicit = item.optBoolean("is_explicit", false),
                        isOfficial = isOfficial,
                    ),
                )
            }
        }
    }

    private fun rankMatches(
        candidates: List<VkAudioCandidate>,
        reference: TrackReference,
        relaxed: Boolean,
    ): List<VkAudioCandidate> {
        if (candidates.isEmpty()) return emptyList()

        val targetLower = "${reference.artist} ${reference.title}".lowercase()
        val isRuTrack =
            LocalCensorshipAdvisor.hasCyrillic(reference.artist) ||
                LocalCensorshipAdvisor.hasCyrillic(reference.title)
        val preferUncensoredUpload =
            configStore.current.preferVkForExplicit &&
                (
                    LocalCensorshipAdvisor.isLikelyCensoredOnYandex(reference.artist, reference.title) ||
                        (reference.isExplicit && isRuTrack)
                )

        // Если Deezer/YouTube не дал длительность, вычисляем медианную длительность чистых совпадений
        val effectiveDurationSec =
            reference.durationSec?.takeIf { it > 30L }
                ?: inferConsensusDuration(candidates, reference, targetLower)

        val tolerance = if (relaxed) 10L else 4L
        val minScore = if (relaxed) 52 else 62

        return candidates
            .mapNotNull { candidate ->
                val fullCandLower = "${candidate.artist} ${candidate.title}".lowercase()

                // 1. Жёстко отсекаем цензурные / clean / radio версии, если они явно помечены
                if (hasCensoredMarker(fullCandLower, targetLower)) return@mapNotNull null

                // 2. Отсекаем замедленные, ускоренные, ремиксы, каверы, лайвы, тикток-эдиты
                if (hasUnwantedVersionTag(fullCandLower, targetLower)) return@mapNotNull null

                // Убираем позитивные пометки "(без цензуры)" / "(uncensored)" перед сравнением названий,
                // чтобы они не занижали matchScore и не блокировались TrackMatcher'ом
                val isMarkedUncensored = hasUncensoredMarker(fullCandLower)
                val cleanTitleForMatch = stripUncensoredMarkers(candidate.title)
                if (TrackMatcher.hasForbiddenStopWord(reference.title, cleanTitleForMatch)) {
                    return@mapNotNull null
                }

                // 3. Проверка длительности (отсекает sped up / slowed даже без пометки в названии)
                if (candidate.durationSec in 1L..35L && (effectiveDurationSec == null || effectiveDurationSec > 50L)) {
                    return@mapNotNull null
                }
                if (effectiveDurationSec != null &&
                    candidate.durationSec > 0L &&
                    !TrackMatcher.isDurationAcceptable(effectiveDurationSec, candidate.durationSec, toleranceSec = tolerance)
                ) {
                    return@mapNotNull null
                }

                val baseScore =
                    TrackMatcher.matchScore(
                        targetArtist = reference.artist,
                        targetTitle = reference.title,
                        targetDurationSec = effectiveDurationSec,
                        candidateArtist = candidate.artist,
                        candidateTitle = cleanTitleForMatch,
                        candidateDurationSec = candidate.durationSec,
                    )
                if (baseScore < minScore) return@mapNotNull null

                var bonus = 0

                // Бонус за точнейшее совпадение длительности (главная защита от +5%/-5% питча)
                if (effectiveDurationSec != null && candidate.durationSec > 0L) {
                    val diff = abs(effectiveDurationSec - candidate.durationSec)
                    bonus +=
                        when {
                            diff <= 1L -> 18
                            diff <= 2L -> 12
                            diff <= 4L -> 5
                            diff >= 7L -> -18
                            else -> 0
                        }
                }

                // Штраф за непонятные приписки в скобках (если их нет в оригинале и это не feat/без цензуры)
                if (hasUnexpectedBracketJunk(candidate.title, reference.title)) {
                    bonus -= 14
                }

                // Логика выбора: антицензура РФ vs обычный трек
                if (preferUncensoredUpload) {
                    if (isMarkedUncensored) bonus += 35
                    if (!candidate.isOfficial) {
                        // Пользовательские заливки с точным хронометражом для РФ-рэпа чаще без цензуры, чем лейбл-релизы ВК
                        bonus += 10
                    } else if (!candidate.isExplicit) {
                        // Официальный трек без плашки Explicit у цензурируемого артиста — почти наверняка Clean-версия
                        bonus -= 28
                    } else {
                        bonus += 4
                    }
                } else {
                    if (isMarkedUncensored && reference.isExplicit) bonus += 16
                    if (candidate.isOfficial) bonus += 10
                    if (reference.isExplicit && candidate.isExplicit) bonus += 10
                }

                // Небольшой бонус за прямой MP3 вместо HLS
                if (!candidate.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
                    bonus += 3
                }

                candidate to (baseScore + bonus)
            }.sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { it.url.substringBefore('?') }
    }

    private fun inferConsensusDuration(
        candidates: List<VkAudioCandidate>,
        reference: TrackReference,
        targetLower: String,
    ): Long? {
        val durations =
            candidates
                .asSequence()
                .filter { it.durationSec > 45L }
                .filter { cand ->
                    val lower = "${cand.artist} ${cand.title}".lowercase()
                    !hasUnwantedVersionTag(lower, targetLower) && !hasCensoredMarker(lower, targetLower)
                }.filter { cand ->
                    val cleanTitle = stripUncensoredMarkers(cand.title)
                    TrackMatcher.matchScore(
                        targetArtist = reference.artist,
                        targetTitle = reference.title,
                        targetDurationSec = null,
                        candidateArtist = cand.artist,
                        candidateTitle = cleanTitle,
                        candidateDurationSec = null,
                    ) >= 60
                }.map { it.durationSec }
                .sorted()
                .toList()
        if (durations.size < 3) return null
        return durations[durations.size / 2]
    }

    private fun hasUncensoredMarker(lowerText: String): Boolean =
        UNCENSORED_PATTERNS.any { it.containsMatchIn(lowerText) }

    private fun stripUncensoredMarkers(title: String): String {
        var cleaned = title
        UNCENSORED_STRIP_REGEXES.forEach { regex ->
            cleaned = cleaned.replace(regex, " ")
        }
        return cleaned
            .replace(EMPTY_BRACKETS_REGEX, " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
            .ifBlank { title }
    }

    private fun hasCensoredMarker(
        candLower: String,
        targetLower: String,
    ): Boolean {
        val withoutUncensored = stripUncensoredMarkers(candLower).lowercase()
        if (CENSORED_SUBSTRINGS.any { tag -> withoutUncensored.contains(tag) && !targetLower.contains(tag) }) {
            return true
        }
        return CENSORED_WORD_REGEXES.any { (key, regex) ->
            regex.containsMatchIn(withoutUncensored) && !targetLower.contains(key)
        }
    }

    private fun hasUnwantedVersionTag(
        candLower: String,
        targetLower: String,
    ): Boolean {
        if (UNWANTED_SUBSTRINGS.any { tag -> candLower.contains(tag) && !targetLower.contains(tag) }) {
            return true
        }
        return UNWANTED_WORD_REGEXES.any { (key, regex) ->
            regex.containsMatchIn(candLower) && !targetLower.contains(key)
        }
    }

    private fun hasUnexpectedBracketJunk(
        candTitle: String,
        targetTitle: String,
    ): Boolean {
        val targetLower = targetTitle.lowercase()
        val matches = BRACKET_CONTENT_REGEX.findAll(candTitle)
        for (m in matches) {
            val inner = m.groupValues[1].trim().lowercase()
            if (inner.isBlank()) continue
            if (targetLower.contains(inner)) continue
            if (ALLOWED_BRACKET_PREFIXES.any { inner.startsWith(it) || inner.contains(it) }) continue
            if (hasUncensoredMarker(inner)) continue
            return true
        }
        return false
    }

    private fun sanitizeToken(raw: String): String {
        val trimmed = raw.trim()
        val afterParam =
            if (trimmed.contains("access_token=")) {
                trimmed.substringAfter("access_token=").substringBefore("&")
            } else {
                trimmed
            }
        return afterParam.trim()
    }

    companion object {
        private const val TAG = "VkProvider"
        private const val FLOOD_COOLDOWN_MS = 3 * 60 * 1_000L

        private const val KATE_UA =
            "KateMobileAndroid/103.1 lite-545 (Android 13; SDK 33; arm64-v8a; samsung SM-F956B; ru)"
        private const val VK_ANDROID_UA =
            "VKAndroidApp/8.99-23423 (Android 13; SDK 33; arm64-v8a; samsung SM-S918B; ru; 2340x1080)"

        private val MULTI_SPACE_REGEX = Regex("\\s+")
        private val EMPTY_BRACKETS_REGEX = Regex("[(\\[{]\\s*[)\\]}]")
        private val BRACKET_CONTENT_REGEX = Regex("[(\\[]([^)\\]]+)[)\\]]")

        private val ALLOWED_BRACKET_PREFIXES =
            listOf("feat", "ft.", "ft ", "prod", "при уч", "explicit")

        private val UNCENSORED_PATTERNS =
            listOf(
                Regex("без\\s*ценз[уa-zа-яё]*", RegexOption.IGNORE_CASE),
                Regex("uncensor[a-z]*", RegexOption.IGNORE_CASE),
                Regex("no\\s*censor[a-z]*", RegexOption.IGNORE_CASE),
                Regex("с\\s*матом", RegexOption.IGNORE_CASE),
                Regex("(?<![a-zа-яё0-9])(оригинал|original|unfiltered)(?![a-zа-яё0-9])", RegexOption.IGNORE_CASE),
            )

        private val UNCENSORED_STRIP_REGEXES =
            listOf(
                Regex("[(\\[]?\\s*без\\s*ценз[уa-zа-яё]*\\s*[)\\]]?", RegexOption.IGNORE_CASE),
                Regex("[(\\[]?\\s*uncensor[a-z]*\\s*[)\\]]?", RegexOption.IGNORE_CASE),
                Regex("[(\\[]?\\s*no\\s*censor[a-z]*\\s*[)\\]]?", RegexOption.IGNORE_CASE),
                Regex("[(\\[]?\\s*explicit\\s*[)\\]]?", RegexOption.IGNORE_CASE),
                Regex("[(\\[]?\\s*с\\s*матом\\s*[)\\]]?", RegexOption.IGNORE_CASE),
                Regex("[(\\[]?\\s*(оригинал|original)\\s*[)\\]]?", RegexOption.IGNORE_CASE),
            )

        private val CENSORED_SUBSTRINGS =
            listOf(
                "без мата",
                "с цензурой",
                "цензур",
                "запикан",
                "radio edit",
                "clean version",
                "censored",
            )

        private val CENSORED_WORD_REGEXES =
            listOf(
                "clean" to Regex("(?<![a-zа-яё0-9])clean(?![a-zа-яё0-9])"),
            )

        private val UNWANTED_SUBSTRINGS =
            listOf(
                "slowed",
                "sped up",
                "speed up",
                "spedup",
                "speedup",
                "nightcore",
                "найткор",
                "reverb",
                "реверб",
                "замедл",
                "ускор",
                "bass boost",
                "bassboost",
                "бассбуст",
                "басс буст",
                "супербасс",
                "karaoke",
                "караоке",
                "instrumental",
                "инструментал",
                "acoustic",
                "акустик",
                "tiktok",
                "тикток",
                "mashup",
                "мэшап",
                "мешап",
                "bootleg",
                "бутлег",
                "acapella",
                "акапелла",
                "акапела",
                "snippet",
                "сниппет",
                "отрывок",
                "hardstyle",
                "хардстайл",
                "hoodtrap",
                "нейросеть",
                "нейрокавер",
                "ai cover",
                "концерт",
                "1 hour",
                "1 час",
                "10 hours",
                "10 часов",
                "club mix",
                "extended",
            )

        private val UNWANTED_WORD_REGEXES =
            listOf(
                "remix",
                "ремикс",
                "rmx",
                "edit",
                "эдит",
                "cover",
                "кавер",
                "live",
                "лайв",
                "минус",
                "minus",
                "pitch",
                "pitched",
                "8d",
                "16d",
                "vip",
                "flip",
                "dnb",
                "cut",
                "demo",
                "демо",
                "loop",
                "луп",
                "freestyle",
                "фристайл",
                "short",
                "drill",
                "jerk",
                "phonk",
                "фонк",
                "techno",
                "техно",
            ).map { word ->
                word to Regex("(?<![a-zа-яё0-9])${Regex.escape(word)}(?![a-zа-яё0-9])")
            }

        private fun apiBaseUrl(): String {
            val host = listOf("api", "vk", "com").joinToString(".")
            return "https://" + host + "/method"
        }

        private val CLIENT_PROFILES =
            listOf(
                VkClientProfile(
                    label = "Kate",
                    userAgent = KATE_UA,
                    apiVersion = "5.131",
                    endpoint = VkSearchEndpoint.CATALOG,
                ),
                VkClientProfile(
                    label = "Kate",
                    userAgent = KATE_UA,
                    apiVersion = "5.95",
                    endpoint = VkSearchEndpoint.LEGACY,
                ),
                VkClientProfile(
                    label = "VKApp",
                    userAgent = VK_ANDROID_UA,
                    apiVersion = "5.131",
                    endpoint = VkSearchEndpoint.CATALOG,
                ),
            )
    }
}
