package com.metrolist.android.custom

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class ResolvedCustomStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val bitrate: Int? = null,
    val durationSec: Long? = null,
    val clientName: String,
    val isHls: Boolean = false,
    val sourceTrackId: String? = null,
    val coverUrl: String? = null,
)

data class YandexResolveOutcome(
    val stream: ResolvedCustomStream?,
    val trackId: String? = null,
    val coverUrl: String? = null,
    val censorshipDetected: Boolean = false,
    val censorshipReason: String? = null,
)

private data class YandexTrackCandidate(
    val id: String,
    val title: String,
    val artist: String,
    val durationSec: Long?,
    val isExplicit: Boolean,
    val version: String,
    val available: Boolean,
    val coverUrl: String?,
    val hasRestrictionDisclaimer: Boolean,
    val releaseYear: Int? = null,
    val releaseDateMs: Long? = null,
)

class YandexMusicProvider(
    private val configStore: CustomConfigStore,
) {
    private val statusCheckMutex = Mutex()
    private val releaseInfoCache = ConcurrentHashMap<String, Pair<Int?, Long?>>()

    suspend fun checkPlusStatus(
        token: String = configStore.current.yandexToken,
    ): YandexPlusStatus {
        val cleanToken = sanitizeToken(token)
        if (cleanToken.isBlank()) {
            configStore.setYandexPlusStatus(YandexPlusStatus.NO_TOKEN)
            return YandexPlusStatus.NO_TOKEN
        }

        val status =
            try {
                val response =
                    CustomHttp.get(
                        url = "$API_BASE/account/status",
                        headers = authHeaders(cleanToken),
                        timeoutMs = 6_000,
                    )
                when (response.code) {
                    401, 403 -> YandexPlusStatus.INVALID_TOKEN
                    in 200..299 -> {
                        val result = JSONObject(response.body).optJSONObject("result")
                        val hasPlus = result?.optJSONObject("plus")?.optBoolean("hasPlus", false) == true
                        if (hasPlus) YandexPlusStatus.ACTIVE else YandexPlusStatus.NO_PLUS
                    }
                    else -> YandexPlusStatus.UNKNOWN
                }
            } catch (e: CancellationException) {
                throw e
            } catch (error: Exception) {
                Log.w(TAG, "Yandex status check failed: ${error.message}")
                YandexPlusStatus.UNKNOWN
            }

        configStore.setYandexPlusStatus(status)
        return status
    }

    suspend fun resolve(reference: TrackReference): ResolvedCustomStream? =
        resolveDetailed(reference = reference, directTrackId = null, allowSuspectedCensored = false).stream

    suspend fun resolveDirectById(
        trackId: String,
        fallbackDurationSec: Long? = null,
        coverUrl: String? = null,
    ): ResolvedCustomStream? {
        val cleanId = trackId.removePrefix("ym_").trim()
        if (cleanId.isBlank() || cleanId.startsWith("alb_")) return null
        val token = sanitizeToken(configStore.current.yandexToken)
        if (token.isBlank()) return null
        if (!ensurePlusActive(token)) return null
        return fetchDirectMp3(
            token = token,
            trackId = cleanId,
            durationSec = fallbackDurationSec,
            coverUrl = coverUrl,
        )
    }

    suspend fun resolveDetailed(
        reference: TrackReference,
        directTrackId: String? = null,
        allowSuspectedCensored: Boolean = false,
    ): YandexResolveOutcome {
        val token = sanitizeToken(configStore.current.yandexToken)
        if (token.isBlank()) return YandexResolveOutcome(stream = null)
        if (!ensurePlusActive(token)) return YandexResolveOutcome(stream = null)

        val cleanDirectId = directTrackId?.removePrefix("ym_")?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("alb_") }
        val skipHeuristicCensorship = allowSuspectedCensored || !configStore.current.preferVkForExplicit

        if (cleanDirectId != null && skipHeuristicCensorship) {
            val direct = fetchDirectMp3(token, cleanDirectId, reference.durationSec, reference.coverXlUrl)
            if (direct != null) {
                return YandexResolveOutcome(stream = direct, trackId = cleanDirectId, coverUrl = direct.coverUrl)
            }
        }

        val query = listOf(reference.artist, reference.title).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) {
            if (cleanDirectId != null) {
                val direct = fetchDirectMp3(token, cleanDirectId, reference.durationSec, reference.coverXlUrl)
                return YandexResolveOutcome(stream = direct, trackId = cleanDirectId, coverUrl = direct?.coverUrl)
            }
            return YandexResolveOutcome(stream = null)
        }

        val candidates = searchTracks(token, query)
        val best =
            if (cleanDirectId != null) {
                candidates.firstOrNull { it.id == cleanDirectId } ?: pickBestMatch(candidates, reference)
            } else {
                pickBestMatch(candidates, reference)
            }

        if (best == null) {
            if (cleanDirectId != null) {
                val direct = fetchDirectMp3(token, cleanDirectId, reference.durationSec, reference.coverXlUrl)
                return YandexResolveOutcome(stream = direct, trackId = cleanDirectId, coverUrl = direct?.coverUrl)
            }
            return YandexResolveOutcome(stream = null)
        }

        if (!best.available) {
            Log.d(TAG, "Yandex track unavailable (censored/removed): ${best.artist} - ${best.title}")
            return YandexResolveOutcome(
                stream = null,
                trackId = best.id,
                coverUrl = best.coverUrl,
                censorshipDetected = true,
                censorshipReason = "удалён/недоступен в Я.Музыке",
            )
        }

        val censorshipReason = detectCensorshipReason(best, reference)
        if (censorshipReason != null && !skipHeuristicCensorship) {
            Log.d(TAG, "Yandex track flagged censored ($censorshipReason): ${best.artist} - ${best.title}")
            return YandexResolveOutcome(
                stream = null,
                trackId = best.id,
                coverUrl = best.coverUrl,
                censorshipDetected = true,
                censorshipReason = censorshipReason,
            )
        }

        val stream = fetchDirectMp3(token, best.id, best.durationSec ?: reference.durationSec, best.coverUrl)
        if (stream != null) {
            Log.d(TAG, "Resolved via Yandex: ${best.artist} - ${best.title} (${(stream.bitrate ?: 0) / 1000} kbps)")
        }
        return YandexResolveOutcome(
            stream = stream,
            trackId = best.id,
            coverUrl = best.coverUrl,
            censorshipDetected = censorshipReason != null,
            censorshipReason = censorshipReason,
        )
    }

    /** Строгий поиск карточки в Я.Музыке для каскада волны (русские треки без цензуры). */
    suspend fun searchCatalogTrack(
        artist: String,
        title: String,
        targetDurationSec: Long? = null,
        rejectSuspectedCensored: Boolean = true,
    ): ExternalCatalogTrack? {
        val cleanArtist = TrackMatcher.cleanSearchTerm(artist)
        val cleanTitle = TrackMatcher.cleanSearchTerm(title)
        val query = listOf(cleanArtist, cleanTitle).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return null

        val token = sanitizeToken(configStore.current.yandexToken)
        val candidates = searchTracks(token, query)
        val reference =
            TrackReference(
                title = cleanTitle,
                artist = cleanArtist,
                durationSec = targetDurationSec,
                isExplicit = false,
                fromDeezer = false,
            )
        val best = pickBestMatch(candidates, reference) ?: return null
        if (!best.available) return null
        if (rejectSuspectedCensored && configStore.current.preferVkForExplicit && detectCensorshipReason(best, reference) != null) {
            return null
        }

        val cacheKey = "${cleanArtist.lowercase()}::${cleanTitle.lowercase()}"
        if (best.releaseYear != null || best.releaseDateMs != null) {
            if (releaseInfoCache.size > 1_000) releaseInfoCache.clear()
            releaseInfoCache[cacheKey] = best.releaseYear to best.releaseDateMs
        }

        return ExternalCatalogTrack(
            source = "yandex",
            externalId = "ym_${best.id}",
            artist = best.artist.ifBlank { cleanArtist },
            title = best.title.ifBlank { cleanTitle },
            durationSec = best.durationSec,
            coverUrl = best.coverUrl,
            isExplicit = best.isExplicit,
            releaseYear = best.releaseYear,
            releaseDateMs = best.releaseDateMs,
        )
    }

    /**
     * Быстрая кросс-проверка года и даты выхода трека через каталог Я.Музыки
     * (используется для проверки свежести треков из VK/YTM, не требует подписки Плюс и не отбрасывает цензурные треки).
     */
    suspend fun lookupTrackReleaseInfo(
        artist: String,
        title: String,
    ): Pair<Int?, Long?> {
        val cleanArtist = TrackMatcher.cleanSearchTerm(artist)
        val cleanTitle = TrackMatcher.cleanSearchTerm(title)
        if (cleanTitle.isBlank()) return null to null

        val cacheKey = "${cleanArtist.lowercase()}::${cleanTitle.lowercase()}"
        releaseInfoCache[cacheKey]?.let { return it }

        val query = listOf(cleanArtist, cleanTitle).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return null to null

        val token = sanitizeToken(configStore.current.yandexToken)
        val candidates = searchTracks(token, query, timeoutMs = 2_500)
        if (candidates.isEmpty()) return null to null

        val best =
            candidates
                .filter { !TrackMatcher.hasForbiddenStopWord(cleanTitle, it.title) }
                .mapNotNull { cand ->
                    val score =
                        TrackMatcher.matchScore(
                            targetArtist = cleanArtist,
                            targetTitle = cleanTitle,
                            targetDurationSec = null,
                            candidateArtist = cand.artist,
                            candidateTitle = cand.title,
                            candidateDurationSec = cand.durationSec,
                        )
                    if (score >= 58) cand to score else null
                }.maxByOrNull { it.second }
                ?.first

        val result = (best?.releaseYear to best?.releaseDateMs)
        if (releaseInfoCache.size > 1_000) releaseInfoCache.clear()
        releaseInfoCache[cacheKey] = result
        return result
    }

    private suspend fun ensurePlusActive(token: String): Boolean {
        val cached = configStore.yandexPlusStatus.value
        if (cached != YandexPlusStatus.UNKNOWN) {
            return cached == YandexPlusStatus.ACTIVE
        }
        return statusCheckMutex.withLock {
            val rechecked = configStore.yandexPlusStatus.value
            val status = if (rechecked == YandexPlusStatus.UNKNOWN) checkPlusStatus(token) else rechecked
            status == YandexPlusStatus.ACTIVE
        }
    }

    private suspend fun searchTracks(
        token: String,
        query: String,
        timeoutMs: Int = 4_500,
    ): List<YandexTrackCandidate> =
        try {
            val url = "$API_BASE/search?type=track&text=${CustomHttp.urlEncode(query)}&page=0&nocorrect=false"
            val headers =
                if (token.isNotBlank()) {
                    authHeaders(token)
                } else {
                    mapOf("User-Agent" to CLIENT_USER_AGENT, "Accept-Language" to "ru")
                }
            val response = CustomHttp.get(url, headers = headers, timeoutMs = timeoutMs)
            if (token.isNotBlank() && (response.code == 401 || response.code == 403)) {
                configStore.setYandexPlusStatus(YandexPlusStatus.INVALID_TOKEN)
                emptyList()
            } else if (!response.isSuccess) {
                emptyList()
            } else {
                val results =
                    JSONObject(response.body)
                        .optJSONObject("result")
                        ?.optJSONObject("tracks")
                        ?.optJSONArray("results")

                if (results == null) {
                    emptyList()
                } else {
                    buildList {
                        for (i in 0 until results.length()) {
                            val item = results.optJSONObject(i) ?: continue
                            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                            val title = item.optString("title").trim()
                            if (title.isBlank()) continue
                            val version = item.optString("version").trim()
                            val fullTitle =
                                if (version.isNotBlank() && !title.contains(version, ignoreCase = true)) {
                                    "$title ($version)"
                                } else {
                                    title
                                }
                            val artistsArray = item.optJSONArray("artists")
                            val artistNames =
                                buildList {
                                    if (artistsArray != null) {
                                        for (j in 0 until artistsArray.length()) {
                                            artistsArray.optJSONObject(j)?.optString("name")?.takeIf { it.isNotBlank() }?.let(::add)
                                        }
                                    }
                                }.joinToString(", ")
                            val durationMs = item.optLong("durationMs", 0L)
                            val durationSec = if (durationMs > 0L) durationMs / 1000L else null
                            val contentWarning = item.optString("contentWarning")
                            val disclaimers = item.optJSONArray("disclaimers")
                            val isExplicit =
                                contentWarning.equals("explicit", ignoreCase = true) ||
                                    hasDisclaimerToken(disclaimers, "explicit")
                            val hasRestriction = hasRestrictionDisclaimer(disclaimers)
                            val available = item.optBoolean("available", true)
                            val coverUrl = formatHdCover(item.optString("coverUri"))
                            val (relYear, relDateMs) = parseAlbumRelease(item.optJSONArray("albums"))

                            add(
                                YandexTrackCandidate(
                                    id = id,
                                    title = fullTitle,
                                    artist = artistNames,
                                    durationSec = durationSec,
                                    isExplicit = isExplicit,
                                    version = version,
                                    available = available,
                                    coverUrl = coverUrl,
                                    hasRestrictionDisclaimer = hasRestriction,
                                    releaseYear = relYear,
                                    releaseDateMs = relDateMs,
                                ),
                            )
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            Log.w(TAG, "Yandex search failed: ${error.message}")
            emptyList()
        }

    /**
     * Выбирает самую раннюю дату выхода трека среди альбомов (игнорируя сборники `compilation`, если есть оригинальный релиз).
     */
    private fun parseAlbumRelease(albums: JSONArray?): Pair<Int?, Long?> {
        if (albums == null || albums.length() == 0) return null to null
        val nonCompilations = mutableListOf<Pair<Int?, Long?>>()
        val allParsed = mutableListOf<Pair<Int?, Long?>>()
        for (i in 0 until albums.length()) {
            val alb = albums.optJSONObject(i) ?: continue
            val rawDate = alb.optString("releaseDate").trim()
            val yearFromField = alb.optInt("year", 0).takeIf { it in 1900..2100 }
            val yearFromDate = rawDate.takeIf { it.length >= 4 }?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }
            val year = yearFromField ?: yearFromDate
            val ms =
                if (rawDate.length >= 10) {
                    runCatching {
                        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(rawDate.take(10))?.time
                    }.getOrNull()
                } else {
                    null
                }
            if (year == null && ms == null) continue
            val pair = year to ms
            allParsed.add(pair)
            val type = alb.optString("type").trim()
            if (!type.equals("compilation", ignoreCase = true)) {
                nonCompilations.add(pair)
            }
        }
        val pool = nonCompilations.ifEmpty { allParsed }
        if (pool.isEmpty()) return null to null
        return pool.minWithOrNull(
            compareBy<Pair<Int?, Long?>>(
                { it.first ?: Int.MAX_VALUE },
                { it.second ?: Long.MAX_VALUE },
            ),
        ) ?: pool.first()
    }

    private fun pickBestMatch(
        candidates: List<YandexTrackCandidate>,
        reference: TrackReference,
    ): YandexTrackCandidate? {
        val ranked =
            candidates
                .filter { candidate ->
                    !TrackMatcher.hasForbiddenStopWord(reference.title, candidate.title) &&
                        candidate.durationSec.let { it == null || it > 35L }
                }.mapNotNull { candidate ->
                    // Допускаем до 15 сек на этапе отбора, чтобы ниже явно поймать обрезанный куплет (>=4 сек)
                    val durForMatch =
                        if (reference.durationSec != null && candidate.durationSec != null &&
                            abs(reference.durationSec - candidate.durationSec) > 15L
                        ) {
                            return@mapNotNull null
                        } else {
                            candidate.durationSec
                        }
                    val score =
                        TrackMatcher.matchScore(
                            targetArtist = reference.artist,
                            targetTitle = reference.title,
                            targetDurationSec = reference.durationSec,
                            candidateArtist = candidate.artist,
                            candidateTitle = candidate.title,
                            candidateDurationSec = durForMatch,
                        )
                    if (score >= 64) {
                        var bonus = 0
                        if (candidate.available) bonus += 8
                        if (!isCleanVersionLabel(candidate.version, reference.title)) bonus += 10 else bonus -= 25
                        if (reference.isExplicit && candidate.isExplicit) bonus += 12
                        if (reference.isExplicit && !candidate.isExplicit) bonus -= 8
                        candidate to (score + bonus)
                    } else {
                        null
                    }
                }.sortedByDescending { it.second }

        return ranked.firstOrNull()?.first
    }

    private fun detectCensorshipReason(
        candidate: YandexTrackCandidate,
        reference: TrackReference,
    ): String? {
        if (!candidate.available) return "удалён/недоступен в Я.Музыке"
        if (isCleanVersionLabel(candidate.version, reference.title) || isCleanVersionLabel(candidate.title, reference.title)) {
            return "clean/censored версия в Я.Музыке"
        }
        if (candidate.hasRestrictionDisclaimer) {
            return "дисклеймер/ограничение в Я.Музыке"
        }

        // Уровень 2: сравнение длительности (вырезанный куплет / укороченная версия на 4+ сек)
        if (reference.fromDeezer && reference.durationSec != null && candidate.durationSec != null) {
            val cutSeconds = reference.durationSec - candidate.durationSec
            if (cutSeconds >= CENSORED_DURATION_CUT_SEC) {
                return "Я.Музыка короче оригинала на ${cutSeconds}с"
            }
        }

        // Уровень 1 (перекрёстный Explicit): на Deezer русский трек Explicit, а на Яндексе плашка пропала
        val isRuTrack =
            LocalCensorshipAdvisor.hasCyrillic(reference.artist) ||
                LocalCensorshipAdvisor.hasCyrillic(reference.title) ||
                LocalCensorshipAdvisor.hasCyrillic(candidate.artist)
        if (isRuTrack && reference.fromDeezer && reference.isExplicit && !candidate.isExplicit) {
            return "потерян Explicit в Я.Музыке"
        }

        return null
    }

    private fun isCleanVersionLabel(
        text: String,
        targetTitle: String,
    ): Boolean {
        val lower = text.lowercase()
        val targetLower = targetTitle.lowercase()
        if (CLEAN_WORD_REGEX.containsMatchIn(lower) && !CLEAN_WORD_REGEX.containsMatchIn(targetLower)) {
            return true
        }
        return CENSORED_VERSION_SUBSTRINGS.any { tag ->
            lower.contains(tag) && !targetLower.contains(tag)
        }
    }

    private fun hasDisclaimerToken(
        disclaimers: JSONArray?,
        token: String,
    ): Boolean {
        if (disclaimers == null) return false
        for (i in 0 until disclaimers.length()) {
            if (disclaimers.optString(i).equals(token, ignoreCase = true)) return true
        }
        return false
    }

    private fun hasRestrictionDisclaimer(disclaimers: JSONArray?): Boolean {
        if (disclaimers == null || disclaimers.length() == 0) return false
        for (i in 0 until disclaimers.length()) {
            val d = disclaimers.optString(i).trim().lowercase()
            if (d.isBlank() || d == "explicit") continue
            if (d.contains("foreign") || d.contains("modal") || d.contains("censor") || d.contains("restrict") || d.contains("drug")) {
                return true
            }
        }
        return false
    }

    private fun formatHdCover(rawUri: String): String? {
        val clean = rawUri.trim()
        if (clean.isBlank()) return null
        val normalized = if (clean.startsWith("http")) clean else "https://$clean"
        return normalized.replace("%%", "1000x1000")
    }

    private suspend fun fetchDirectMp3(
        token: String,
        trackId: String,
        durationSec: Long?,
        coverUrl: String?,
    ): ResolvedCustomStream? =
        try {
            val infoResp =
                CustomHttp.get(
                    url = "$API_BASE/tracks/$trackId/download-info",
                    headers = authHeaders(token),
                    timeoutMs = 5_500,
                )
            if (infoResp.code == 401 || infoResp.code == 403) {
                configStore.setYandexPlusStatus(YandexPlusStatus.INVALID_TOKEN)
                null
            } else if (!infoResp.isSuccess) {
                null
            } else {
                val list = JSONObject(infoResp.body).optJSONArray("result")
                if (list == null) {
                    null
                } else {
                    var bestUrl: String? = null
                    var bestBitrate = -1

                    for (i in 0 until list.length()) {
                        val entry = list.optJSONObject(i) ?: continue
                        if (entry.optBoolean("preview", false)) continue
                        val codec = entry.optString("codec")
                        if (!codec.equals("mp3", ignoreCase = true)) continue
                        val bitrate = entry.optInt("bitrateInKbps", 0)
                        val downloadInfoUrl = entry.optString("downloadInfoUrl")
                        if (downloadInfoUrl.isNotBlank() && bitrate > bestBitrate) {
                            bestBitrate = bitrate
                            bestUrl = downloadInfoUrl
                        }
                    }

                    val targetInfoUrl = bestUrl
                    if (targetInfoUrl == null) {
                        null
                    } else {
                        val separator = if (targetInfoUrl.contains('?')) '&' else '?'
                        val metaResp =
                            CustomHttp.get(
                                url = "$targetInfoUrl${separator}format=json",
                                headers = authHeaders(token),
                                timeoutMs = 5_500,
                            )
                        val meta = if (metaResp.isSuccess) parseDownloadMeta(metaResp.body) else null
                        if (meta == null) {
                            null
                        } else {
                            val normalizedPath = if (meta.path.startsWith("/")) meta.path else "/${meta.path}"
                            val sign = CustomHttp.md5Hex(SIGN_SALT + normalizedPath.removePrefix("/") + meta.s)
                            val directUrl = "https://${meta.host}/get-mp3/$sign/${meta.ts}$normalizedPath"

                            ResolvedCustomStream(
                                url = directUrl,
                                headers = mapOf("User-Agent" to CLIENT_USER_AGENT),
                                mimeType = "audio/mpeg",
                                bitrate = if (bestBitrate > 0) bestBitrate * 1000 else null,
                                durationSec = durationSec,
                                clientName = "yandex",
                                isHls = false,
                                sourceTrackId = trackId,
                                coverUrl = coverUrl,
                            )
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            Log.w(TAG, "Yandex direct link failed for $trackId: ${error.message}")
            null
        }

    private fun parseDownloadMeta(body: String): DownloadMeta? {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
            val host = obj.optString("host")
            val path = obj.optString("path")
            val ts = obj.optString("ts")
            val s = obj.optString("s")
            if (host.isNotBlank() && path.isNotBlank() && ts.isNotBlank() && s.isNotBlank()) {
                return DownloadMeta(host, path, ts, s)
            }
        }
        val host = extractXmlTag(trimmed, "host") ?: return null
        val path = extractXmlTag(trimmed, "path") ?: return null
        val ts = extractXmlTag(trimmed, "ts") ?: return null
        val s = extractXmlTag(trimmed, "s") ?: return null
        return DownloadMeta(host, path, ts, s)
    }

    private fun extractXmlTag(
        xml: String,
        tag: String,
    ): String? =
        Regex("<$tag>([^<]+)</$tag>")
            .find(xml)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun sanitizeToken(raw: String): String =
        raw.trim().removePrefix("OAuth ").removePrefix("Bearer ").trim()

    private fun authHeaders(token: String): Map<String, String> =
        mapOf(
            "Authorization" to "OAuth $token",
            "X-Yandex-Music-Client" to "YandexMusicAndroid/24023621",
            "User-Agent" to CLIENT_USER_AGENT,
            "Accept-Language" to "ru",
        )

    private data class DownloadMeta(
        val host: String,
        val path: String,
        val ts: String,
        val s: String,
    )

    companion object {
        private const val TAG = "YandexProvider"
        private const val API_BASE = "https://api.music.yandex.net"
        private const val SIGN_SALT = "XGRlBW9FXlekgbPrRHuSiA"
        private const val CLIENT_USER_AGENT = "Yandex-Music-API"
        private const val CENSORED_DURATION_CUT_SEC = 4L

        private val CLEAN_WORD_REGEX = Regex("(?<![a-zа-яё0-9])clean(?![a-zа-яё0-9])")
        private val CENSORED_VERSION_SUBSTRINGS =
            listOf("censored", "radio edit", "без мата", "цензур")
    }
}
