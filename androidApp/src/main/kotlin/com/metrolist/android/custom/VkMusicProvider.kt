package com.metrolist.android.custom

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

private data class VkAudioCandidate(
    val audioKey: String,
    val artist: String,
    val title: String,
    val rawTitle: String,
    val subtitle: String,
    val durationSec: Long,
    val url: String,
    val isExplicit: Boolean,
    val isOfficial: Boolean,
    val coverUrl: String? = null,
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

    private val tokenRefreshMutex = Mutex()

    @Volatile
    private var lastAutoRefreshAttemptMs: Long = 0L

    fun resetFloodCooldown() {
        floodCooldownUntilMs = 0L
    }

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
                return Result.failure(IllegalStateException("Токен ВК истёк или невалиден (#5) — нажми «Войти в ВК»"))
            }
            notes.add("${profile.label}/${profile.endpoint.label}: $errMsg")
        }

        return Result.failure(IllegalStateException("ВК аудио недоступно (${notes.joinToString("; ")})"))
    }

    suspend fun resolve(reference: TrackReference): ResolvedCustomStream? =
        resolveWithVariant(reference, variantIndex = 0)?.first

    /**
     * Прямой резолв по сохранённому `vk_ownerId_audioId` (или `ownerId_audioId`) через `audio.getById`
     * без лишнего вызова поиска — быстрее и защищает от flood control (#9).
     */
    suspend fun resolveDirectById(
        vkTrackId: String,
        allowAutoRefresh: Boolean = true,
    ): ResolvedCustomStream? {
        if (System.currentTimeMillis() < floodCooldownUntilMs) return null
        val audioKey = vkTrackId.removePrefix("vk_").trim()
        if (audioKey.isBlank() || !audioKey.contains('_')) return null
        if (configStore.isVkAudioBlacklisted(audioKey)) return null

        val token = sanitizeToken(configStore.current.vkToken)
        if (token.isBlank()) return null

        val profilesToTry = (listOf(activeProfile) + CLIENT_PROFILES).distinctBy { "${it.userAgent}_${it.apiVersion}" }
        for (profile in profilesToTry) {
            if (System.currentTimeMillis() < floodCooldownUntilMs) return null
            val outcome =
                try {
                    val url =
                        "${apiBaseUrl()}/audio.getById" +
                            "?audios=${CustomHttp.urlEncode(audioKey)}" +
                            "&access_token=${CustomHttp.urlEncode(token)}" +
                            "&v=${profile.apiVersion}"
                    val response =
                        CustomHttp.get(
                            url = url,
                            headers = mapOf("User-Agent" to profile.userAgent),
                            timeoutMs = 6_000,
                        )
                    if (!response.isSuccess) {
                        DirectLookupResult.RetryNext
                    } else {
                        val root = JSONObject(response.body)
                        if (root.has("error")) {
                            val code = root.optJSONObject("error")?.optInt("error_code", -1) ?: -1
                            when (code) {
                                9 -> {
                                    floodCooldownUntilMs = System.currentTimeMillis() + FLOOD_COOLDOWN_MS
                                    DirectLookupResult.Stop
                                }
                                5 -> DirectLookupResult.TokenExpired
                                else -> DirectLookupResult.RetryNext
                            }
                        } else {
                            val arr = root.optJSONArray("response")
                            val candidate = parseAudioArray(arr).firstOrNull()
                            if (candidate != null) {
                                activeProfile = profile
                                DirectLookupResult.Found(candidate, profile.userAgent)
                            } else {
                                DirectLookupResult.Stop
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    DirectLookupResult.RetryNext
                }

            when (outcome) {
                is DirectLookupResult.Found -> {
                    val best = outcome.candidate
                    val isHls = best.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
                    Log.d(TAG, "Direct VK hit (${best.audioKey}, ${if (isHls) "HLS" else "MP3"}): ${best.artist} - ${best.title}")
                    return ResolvedCustomStream(
                        url = best.url,
                        headers = mapOf("User-Agent" to outcome.userAgent),
                        mimeType = if (isHls) "application/x-mpegURL" else "audio/mpeg",
                        bitrate = null,
                        durationSec = best.durationSec.takeIf { it > 0L },
                        clientName = "vk",
                        isHls = isHls,
                        sourceTrackId = best.audioKey.takeIf { it.isNotBlank() },
                        coverUrl = best.coverUrl,
                    )
                }
                DirectLookupResult.TokenExpired -> {
                    if (allowAutoRefresh) {
                        val refreshed = tryRefreshExpiredToken(token)
                        if (!refreshed.isNullOrBlank()) {
                            return resolveDirectById(vkTrackId, allowAutoRefresh = false)
                        }
                    }
                    return null
                }
                DirectLookupResult.Stop -> return null
                DirectLookupResult.RetryNext -> Unit
            }
        }
        return null
    }

    suspend fun resolveWithVariant(
        reference: TrackReference,
        variantIndex: Int = 0,
        excludedAudioKeys: Set<String> = emptySet(),
    ): Pair<ResolvedCustomStream, String?>? {
        if (System.currentTimeMillis() < floodCooldownUntilMs) return null
        val token = sanitizeToken(configStore.current.vkToken)
        if (token.isBlank()) return null

        val query = listOf(reference.artist, reference.title).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return null

        val (candidates, matchedUserAgent) = searchAudio(token, query, allowAutoRefresh = true)
        val nonBlacklisted =
            candidates.filter { cand ->
                cand.audioKey.isBlank() ||
                    (!configStore.isVkAudioBlacklisted(cand.audioKey) && cand.audioKey !in excludedAudioKeys)
            }

        val strict = rankMatches(nonBlacklisted, reference, relaxed = false)
        val ranked =
            if (variantIndex > 0) {
                (strict + rankMatches(nonBlacklisted, reference, relaxed = true))
                    .distinctBy { it.audioKey.ifBlank { it.url.substringBefore('?') } }
            } else {
                strict.ifEmpty { rankMatches(nonBlacklisted, reference, relaxed = true) }
            }
        if (ranked.isEmpty()) return null

        val idx = variantIndex.coerceAtLeast(0) % ranked.size
        val best = ranked[idx]
        val variantTag = if (ranked.size > 1 && variantIndex > 0) "#${idx + 1}/${ranked.size}" else null

        val isHls = best.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        Log.d(
            TAG,
            "Resolved via VK (${if (isHls) "HLS" else "MP3"} ${variantTag ?: "#1"}, key=${best.audioKey}, official=${best.isOfficial}, dur=${best.durationSec}s): ${best.artist} - ${best.title}",
        )

        val stream =
            ResolvedCustomStream(
                url = best.url,
                headers = mapOf("User-Agent" to matchedUserAgent),
                mimeType = if (isHls) "application/x-mpegURL" else "audio/mpeg",
                bitrate = null,
                durationSec = best.durationSec.takeIf { it > 0L },
                clientName = "vk",
                isHls = isHls,
                sourceTrackId = best.audioKey.takeIf { it.isNotBlank() },
                coverUrl = best.coverUrl,
            )
        return stream to variantTag
    }

    /** Строгий поиск карточки в VK для каскада волны (удалённые/цензурные треки без мусора). */
    suspend fun searchCatalogTrack(
        artist: String,
        title: String,
        targetDurationSec: Long? = null,
    ): ExternalCatalogTrack? {
        if (System.currentTimeMillis() < floodCooldownUntilMs) return null
        val token = sanitizeToken(configStore.current.vkToken)
        if (token.isBlank()) return null

        val cleanArtist = TrackMatcher.cleanSearchTerm(artist)
        val cleanTitle = TrackMatcher.cleanSearchTerm(title)
        val query = listOf(cleanArtist, cleanTitle).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return null

        val (rawCandidates, _) = searchAudio(token, query, allowAutoRefresh = true)
        val candidates =
            rawCandidates.filter { it.audioKey.isBlank() || !configStore.isVkAudioBlacklisted(it.audioKey) }
        if (candidates.isEmpty()) return null

        val reference =
            TrackReference(
                title = cleanTitle,
                artist = cleanArtist,
                durationSec = targetDurationSec,
                isExplicit = false,
                fromDeezer = targetDurationSec != null,
            )
        val best = rankMatches(candidates, reference, relaxed = false).firstOrNull() ?: return null
        val extId = if (best.audioKey.isNotBlank()) "vk_${best.audioKey}" else ""
        return ExternalCatalogTrack(
            source = "vk",
            externalId = extId,
            artist = best.artist.ifBlank { cleanArtist },
            title = stripUncensoredMarkers(best.title).ifBlank { cleanTitle },
            durationSec = best.durationSec.takeIf { it > 0L },
            coverUrl = best.coverUrl,
            isExplicit = best.isExplicit,
        )
    }

    private suspend fun tryRefreshExpiredToken(failedToken: String): String? =
        tokenRefreshMutex.withLock {
            val currentToken = sanitizeToken(configStore.current.vkToken)
            if (currentToken.isNotBlank() && currentToken != failedToken) {
                return@withLock currentToken
            }
            val now = System.currentTimeMillis()
            if (now - lastAutoRefreshAttemptMs < AUTO_REFRESH_COOLDOWN_MS) return@withLock null
            lastAutoRefreshAttemptMs = now

            val hook = onTokenExpiredRefreshHook ?: return@withLock null
            val fresh =
                try {
                    hook()?.let(::sanitizeToken)?.takeIf { it.isNotBlank() }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            if (fresh != null) {
                Log.i(TAG, "VK token auto-refreshed silently via WebView cookies")
                configStore.update { it.copy(vkToken = fresh) }
            }
            fresh
        }

    private suspend fun testSearchProfile(
        token: String,
        profile: VkClientProfile,
    ): Result<Int> =
        try {
            val url = buildSearchUrl(token = token, query = "Кино Группа крови", count = 3, profile = profile)
            val response = CustomHttp.get(url, headers = mapOf("User-Agent" to profile.userAgent), timeoutMs = 8_000)
            if (!response.isSuccess) {
                Result.failure(IllegalStateException("HTTP ${response.code}"))
            } else {
                val root = JSONObject(response.body)
                if (root.has("error")) {
                    val err = root.optJSONObject("error")
                    val code = err?.optInt("error_code", -1) ?: -1
                    val msg = err?.optString("error_msg").orEmpty()
                    Result.failure(IllegalStateException("#$code $msg"))
                } else {
                    val respObj = root.optJSONObject("response")
                    val arr =
                        when (profile.endpoint) {
                            VkSearchEndpoint.CATALOG -> respObj?.optJSONArray("audios") ?: respObj?.optJSONArray("items")
                            VkSearchEndpoint.LEGACY -> respObj?.optJSONArray("items") ?: respObj?.optJSONArray("audios")
                        }
                    val parsed = parseAudioArray(arr)
                    if (respObj != null && parsed.isNotEmpty()) {
                        Result.success(parsed.size)
                    } else if (respObj != null) {
                        Result.failure(IllegalStateException("нет доступных аудио-ссылок (токен без прав audio)"))
                    } else {
                        Result.failure(IllegalStateException("пустой ответ"))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private suspend fun searchAudio(
        token: String,
        query: String,
        allowAutoRefresh: Boolean,
    ): Pair<List<VkAudioCandidate>, String> {
        val profilesToTry = (listOf(activeProfile) + CLIENT_PROFILES).distinct()
        for (profile in profilesToTry) {
            if (System.currentTimeMillis() < floodCooldownUntilMs) return emptyList<VkAudioCandidate>() to profile.userAgent
            val outcome =
                try {
                    val url = buildSearchUrl(token = token, query = query, count = 35, profile = profile)
                    val response =
                        CustomHttp.get(
                            url = url,
                            headers = mapOf("User-Agent" to profile.userAgent),
                            timeoutMs = 6_000,
                        )
                    if (!response.isSuccess) {
                        SearchOutcome.RetryNext
                    } else {
                        val root = JSONObject(response.body)
                        if (root.has("error")) {
                            val code = root.optJSONObject("error")?.optInt("error_code", -1) ?: -1
                            Log.w(TAG, "VK ${profile.label}/${profile.endpoint.label} error #$code")
                            when (code) {
                                9 -> {
                                    floodCooldownUntilMs = System.currentTimeMillis() + FLOOD_COOLDOWN_MS
                                    SearchOutcome.Empty
                                }
                                5 -> SearchOutcome.TokenExpired
                                else -> SearchOutcome.RetryNext
                            }
                        } else {
                            activeProfile = profile
                            val respObj = root.optJSONObject("response")
                            if (respObj == null) {
                                SearchOutcome.Empty
                            } else {
                                val array =
                                    when (profile.endpoint) {
                                        VkSearchEndpoint.CATALOG -> respObj.optJSONArray("audios") ?: respObj.optJSONArray("items")
                                        VkSearchEndpoint.LEGACY -> respObj.optJSONArray("items") ?: respObj.optJSONArray("audios")
                                    }
                                SearchOutcome.Success(parseAudioArray(array), profile.userAgent)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    SearchOutcome.RetryNext
                }

            when (outcome) {
                is SearchOutcome.Success -> return outcome.items to outcome.userAgent
                SearchOutcome.Empty -> return emptyList<VkAudioCandidate>() to profile.userAgent
                SearchOutcome.TokenExpired -> {
                    if (allowAutoRefresh) {
                        val refreshed = tryRefreshExpiredToken(token)
                        if (!refreshed.isNullOrBlank()) {
                            return searchAudio(refreshed, query, allowAutoRefresh = false)
                        }
                    }
                    return emptyList<VkAudioCandidate>() to profile.userAgent
                }
                SearchOutcome.RetryNext -> Unit
            }
        }
        return emptyList<VkAudioCandidate>() to activeProfile.userAgent
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
                if (streamUrl.isBlank() || streamUrl.contains("audio_api_unavailable", ignoreCase = true)) continue
                val ownerId = item.optLong("owner_id", 0L)
                val id = item.optLong("id", 0L)
                val audioKey = if (id != 0L) "${ownerId}_$id" else ""
                val rawTitle = item.optString("title").trim()
                val subtitle = item.optString("subtitle").trim()
                val fullTitle = if (subtitle.isNotBlank()) "$rawTitle ($subtitle)" else rawTitle
                val albumObj = item.optJSONObject("album")
                val coverUrl =
                    albumObj?.optJSONObject("thumb")?.let { thumb ->
                        thumb.optString("photo_1200")
                            .ifBlank { thumb.optString("photo_600") }
                            .ifBlank { thumb.optString("photo_300") }
                    }?.takeIf { it.isNotBlank() }
                val isOfficial =
                    albumObj != null ||
                        item.optBoolean("is_licensed", false) ||
                        (item.optJSONArray("main_artists")?.length() ?: 0) > 0
                add(
                    VkAudioCandidate(
                        audioKey = audioKey,
                        artist = item.optString("artist").trim(),
                        title = fullTitle,
                        rawTitle = rawTitle,
                        subtitle = subtitle,
                        durationSec = item.optLong("duration", 0L),
                        url = streamUrl,
                        isExplicit = item.optBoolean("is_explicit", false),
                        isOfficial = isOfficial,
                        coverUrl = coverUrl,
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
                        configStore.isArtistLearnedCensored(reference.artist) ||
                        (reference.isExplicit && isRuTrack)
                )

        // Эталонная длительность из Deezer/YTM либо медианная по чистым кандидатам
        val effectiveDurationSec =
            reference.durationSec?.takeIf { it > 30L }
                ?: inferConsensusDuration(candidates, reference, targetLower)

        // Жёсткий допуск 3 сек при наличии эталона — отсекает ускоренные/замедленные обходы АП с питчем
        val tolerance =
            when {
                relaxed -> 8L
                reference.fromDeezer && reference.durationSec != null -> 3L
                else -> 4L
            }
        val minScore = if (relaxed) 54 else 64
        val targetHasSpacedLetters = SPACED_OBFUSCATION_REGEX.containsMatchIn(targetLower)

        return candidates
            .mapNotNull { candidate ->
                val fullCandLower = "${candidate.artist} ${candidate.title}".lowercase()

                // 1. Жёстко отсекаем цензурные / clean / radio версии
                if (hasCensoredMarker(fullCandLower, targetLower)) return@mapNotNull null

                // 2. Отсекаем slowed, sped up, pitch, ремиксы, каверы, лайвы, обходы АП
                if (hasUnwantedVersionTag(fullCandLower, targetLower)) return@mapNotNull null
                if (!relaxed && hasAntiApPitchMarker(fullCandLower, targetLower)) return@mapNotNull null

                // Убираем позитивные пометки "(без цензуры)" перед сравнением названий
                val isMarkedUncensored = hasUncensoredMarker(fullCandLower)
                val cleanTitleForMatch = stripUncensoredMarkers(candidate.title)
                if (TrackMatcher.hasForbiddenStopWord(reference.title, cleanTitleForMatch)) {
                    return@mapNotNull null
                }

                // 3. Проверка длительности (отсекает скрытый +5%/-5% питч со сдвигом скорости)
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

                // Бонус/штраф за точное совпадение длительности (главная защита от питча)
                if (effectiveDurationSec != null && candidate.durationSec > 0L) {
                    val diff = abs(effectiveDurationSec - candidate.durationSec)
                    bonus +=
                        when {
                            diff <= 1L -> 22
                            diff <= 2L -> 14
                            diff <= 3L -> 4
                            diff >= 5L -> -24
                            else -> -8
                        }
                }

                // Штраф за обфускацию названия пробелами/символами (типичный признак пиратского питч-обхода АП)
                if (!targetHasSpacedLetters && SPACED_OBFUSCATION_REGEX.containsMatchIn(fullCandLower)) {
                    bonus -= 24
                }
                if (hasAntiApPitchMarker(fullCandLower, targetLower)) {
                    bonus -= 30
                }

                // Штраф за непонятные приписки в скобках
                if (hasUnexpectedBracketJunk(candidate.title, reference.title)) {
                    bonus -= 16
                }

                // Логика выбора: антицензура РФ vs обычный трек
                if (preferUncensoredUpload) {
                    if (isMarkedUncensored) bonus += 35
                    if (!candidate.isOfficial) {
                        bonus += 8
                    } else if (!candidate.isExplicit) {
                        bonus -= 28
                    } else {
                        bonus += 6
                    }
                } else {
                    if (isMarkedUncensored && reference.isExplicit) bonus += 16
                    if (candidate.isOfficial) bonus += 12
                    if (reference.isExplicit && candidate.isExplicit) bonus += 10
                }

                // Небольшой бонус за прямой MP3 вместо HLS
                if (!candidate.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
                    bonus += 3
                }

                candidate to (baseScore + bonus)
            }.sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { it.audioKey.ifBlank { it.url.substringBefore('?') } }
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
                    !hasUnwantedVersionTag(lower, targetLower) &&
                        !hasCensoredMarker(lower, targetLower) &&
                        !hasAntiApPitchMarker(lower, targetLower)
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

    private fun hasAntiApPitchMarker(
        candLower: String,
        targetLower: String,
    ): Boolean {
        if (ANTI_AP_SUBSTRINGS.any { tag -> candLower.contains(tag) && !targetLower.contains(tag) }) {
            return true
        }
        return ANTI_AP_WORD_REGEXES.any { (key, regex) ->
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
                trimmed.substringAfter("access_token=").substringBefore("&").substringBefore("#")
            } else {
                trimmed
            }
        return afterParam.trim()
    }

    private sealed interface SearchOutcome {
        data class Success(
            val items: List<VkAudioCandidate>,
            val userAgent: String,
        ) : SearchOutcome

        data object Empty : SearchOutcome

        data object TokenExpired : SearchOutcome

        data object RetryNext : SearchOutcome
    }

    private sealed interface DirectLookupResult {
        data class Found(
            val candidate: VkAudioCandidate,
            val userAgent: String,
        ) : DirectLookupResult

        data object TokenExpired : DirectLookupResult

        data object Stop : DirectLookupResult

        data object RetryNext : DirectLookupResult
    }

    companion object {
        private const val TAG = "VkProvider"
        private const val FLOOD_COOLDOWN_MS = 3 * 60 * 1_000L
        private const val AUTO_REFRESH_COOLDOWN_MS = 30_000L

        /** Вешается встроенным WebView-менеджером для тихого перевыпуска токена при #5. */
        @Volatile
        var onTokenExpiredRefreshHook: (suspend () -> String?)? = null

        private const val KATE_UA =
            "KateMobileAndroid/103.1 lite-545 (Android 13; SDK 33; arm64-v8a; samsung SM-F956B; ru)"
        private const val VK_ANDROID_UA =
            "VKAndroidApp/8.99-23423 (Android 13; SDK 33; arm64-v8a; samsung SM-S918B; ru; 2340x1080)"

        private val MULTI_SPACE_REGEX = Regex("\\s+")
        private val EMPTY_BRACKETS_REGEX = Regex("[(\\[{]\\s*[)\\]}]")
        private val BRACKET_CONTENT_REGEX = Regex("[(\\[]([^)\\]]+)[)\\]]")
        private val SPACED_OBFUSCATION_REGEX = Regex("(?:(?<![a-zа-яё])[a-zа-яё]\\s+){4,}[a-zа-яё](?![a-zа-яё])")

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

        private val ANTI_AP_SUBSTRINGS =
            listOf(
                "без ап",
                "анти ап",
                "антиап",
                "no ap",
                "anti ap",
                "antiap",
                "обход ап",
                "обход",
                "питч",
                "pitch+",
                "pitch-",
                "pitch ",
                "тональност",
                "повышен",
                "понижен",
            )

        private val ANTI_AP_WORD_REGEXES =
            listOf(
                "слив",
                "fix",
                "фикс",
                "pitched",
                "pitch",
                "перезалив",
            ).map { word ->
                word to Regex("(?<![a-zа-яё0-9])${Regex.escape(word)}(?![a-zа-яё0-9])")
            }

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
