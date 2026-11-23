package com.metrolist.android.custom

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class HttpResult(
    val code: Int,
    val body: String,
    val retryAfterSeconds: Long? = null,
) {
    val isSuccess: Boolean
        get() = code in 200..299
}

object CustomHttp {
    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 8_000,
    ): HttpResult =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection)
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = true
                if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    conn.setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
                }
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                readResult(conn)
            } finally {
                conn.disconnect()
            }
        }

    suspend fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 25_000,
    ): HttpResult =
        withContext(Dispatchers.IO) {
            val payload = jsonBody.toByteArray(Charsets.UTF_8)
            val conn = (URL(url).openConnection() as HttpURLConnection)
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(payload.size)
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    conn.setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
                }
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.outputStream.use { out ->
                    out.write(payload)
                }
                readResult(conn)
            } finally {
                conn.disconnect()
            }
        }

    fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun readResult(conn: HttpURLConnection): HttpResult {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text =
            stream?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            }.orEmpty()
        val retryAfter = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
        return HttpResult(code = code, body = text, retryAfterSeconds = retryAfter)
    }
}

data class TrackReference(
    val title: String,
    val artist: String,
    val durationSec: Long?,
    val isExplicit: Boolean,
    val fromDeezer: Boolean,
    val coverXlUrl: String? = null,
    val deezerId: String? = null,
    val releaseYear: Int? = null,
    val releaseDateMs: Long? = null,
)

private data class DeezerCandidate(
    val id: String,
    val title: String,
    val artist: String,
    val duration: Long,
    val explicit: Boolean,
    val coverXl: String?,
    val releaseYear: Int? = null,
    val releaseDateMs: Long? = null,
)

class DeezerReferenceClient {
    private val cache = ConcurrentHashMap<String, TrackReference>()
    private val releaseDateCache = ConcurrentHashMap<String, Long>()

    suspend fun lookup(
        artist: String,
        title: String,
        fallbackDurationSec: Long?,
        fallbackExplicit: Boolean,
    ): TrackReference {
        val cleanArtist = TrackMatcher.cleanSearchTerm(artist)
        val cleanTitle = TrackMatcher.cleanSearchTerm(title)
        val cacheKey = "${cleanArtist.lowercase()}::${cleanTitle.lowercase()}"
        cache[cacheKey]?.let { return it }

        val fallback =
            TrackReference(
                title = cleanTitle,
                artist = cleanArtist,
                durationSec = fallbackDurationSec?.takeIf { it > 0L },
                isExplicit = fallbackExplicit,
                fromDeezer = false,
                coverXlUrl = null,
                deezerId = null,
            )

        if (cleanTitle.isBlank()) return fallback

        val fetched = searchBestCandidate(cleanArtist, cleanTitle, fallbackDurationSec)

        if (fetched != null) {
            val resolved =
                TrackReference(
                    title = fetched.title.ifBlank { cleanTitle },
                    artist = fetched.artist.ifBlank { cleanArtist },
                    durationSec = fetched.duration.takeIf { it > 0L } ?: fallback.durationSec,
                    isExplicit = fetched.explicit || fallbackExplicit,
                    fromDeezer = true,
                    coverXlUrl = fetched.coverXl,
                    deezerId = fetched.id.takeIf { it.isNotBlank() },
                    releaseYear = fetched.releaseYear,
                    releaseDateMs = fetched.releaseDateMs,
                )
            if (cache.size > 600) cache.clear()
            cache[cacheKey] = resolved
            return resolved
        }

        // Не кэшируем сетевые промахи, чтобы при появлении сети трек смог получить эталон и HD-обложку
        return fallback
    }

    /** Строгий поиск карточки в Deezer для каскада волны — без случайного фоллбэка. */
    suspend fun searchCatalogTrack(
        artist: String,
        title: String,
        targetDurationSec: Long? = null,
    ): ExternalCatalogTrack? {
        val cleanArtist = TrackMatcher.cleanSearchTerm(artist)
        val cleanTitle = TrackMatcher.cleanSearchTerm(title)
        if (cleanTitle.isBlank()) return null

        val best = searchBestCandidate(cleanArtist, cleanTitle, targetDurationSec) ?: return null
        if (best.id.isBlank()) return null

        val cacheKey = "${cleanArtist.lowercase()}::${cleanTitle.lowercase()}"
        if (cache.size > 600) cache.clear()
        cache[cacheKey] =
            TrackReference(
                title = best.title.ifBlank { cleanTitle },
                artist = best.artist.ifBlank { cleanArtist },
                durationSec = best.duration.takeIf { it > 0L },
                isExplicit = best.explicit,
                fromDeezer = true,
                coverXlUrl = best.coverXl,
                deezerId = best.id,
                releaseYear = best.releaseYear,
                releaseDateMs = best.releaseDateMs,
            )

        return ExternalCatalogTrack(
            source = "deezer",
            externalId = "dz_${best.id}",
            artist = best.artist.ifBlank { cleanArtist },
            title = best.title.ifBlank { cleanTitle },
            durationSec = best.duration.takeIf { it > 0L },
            coverUrl = best.coverXl,
            isExplicit = best.explicit,
            releaseYear = best.releaseYear,
            releaseDateMs = best.releaseDateMs,
        )
    }

    /** Точная дата релиза трека или альбома Deezer (кэшируется в памяти, вызывается только для проверки свежести). */
    suspend fun fetchTrackReleaseDateMs(deezerExternalId: String): Long? =
        withContext(Dispatchers.IO) {
            val clean = deezerExternalId.removePrefix("dz_").trim()
            if (clean.isBlank()) return@withContext null
            releaseDateCache[clean]?.let { cached ->
                return@withContext cached.takeIf { it > 0L }
            }
            val url =
                if (clean.startsWith("alb_")) {
                    "${apiBaseUrl()}/album/${clean.removePrefix("alb_")}"
                } else {
                    "${apiBaseUrl()}/track/$clean"
                }
            val parsedMs =
                try {
                    val resp = CustomHttp.get(url, timeoutMs = 2_500)
                    if (!resp.isSuccess) {
                        null
                    } else {
                        val root = JSONObject(resp.body)
                        val rawDate =
                            root
                                .optString("release_date")
                                .trim()
                                .ifBlank { root.optJSONObject("album")?.optString("release_date")?.trim().orEmpty() }
                        parseIsoReleaseDate(rawDate).second
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            if (releaseDateCache.size > 1_000) releaseDateCache.clear()
            releaseDateCache[clean] = parsedMs ?: -1L
            parsedMs
        }

    /** Чарт Deezer для единого блока «Чарты» на Главной. */
    suspend fun fetchChart(limit: Int = 25): List<ExternalCatalogTrack> =
        withContext(Dispatchers.IO) {
            try {
                val safeLimit = limit.coerceIn(1, 50)
                val url = "${apiBaseUrl()}/chart/0/tracks?limit=$safeLimit"
                val response = CustomHttp.get(url, timeoutMs = 8_000)
                if (!response.isSuccess) return@withContext emptyList()
                val data = JSONObject(response.body).optJSONArray("data") ?: return@withContext emptyList()
                buildList {
                    for (i in 0 until data.length()) {
                        val item = data.optJSONObject(i) ?: continue
                        val id = item.optString("id").trim()
                        val title = item.optString("title").trim()
                        val artist = item.optJSONObject("artist")?.optString("name").orEmpty().trim()
                        if (id.isBlank() || title.isBlank()) continue
                        val albumObj = item.optJSONObject("album")
                        val cover =
                            albumObj?.optString("cover_xl")?.takeIf { it.isNotBlank() }
                                ?: albumObj?.optString("cover_big")?.takeIf { it.isNotBlank() }
                        val rawDate =
                            item
                                .optString("release_date")
                                .trim()
                                .ifBlank { albumObj?.optString("release_date")?.trim().orEmpty() }
                        val (relYear, relMs) = parseIsoReleaseDate(rawDate)
                        add(
                            ExternalCatalogTrack(
                                source = "deezer",
                                externalId = "dz_$id",
                                artist = artist,
                                title = title,
                                durationSec = item.optLong("duration", 0L).takeIf { it > 0L },
                                coverUrl = cover,
                                isExplicit = item.optBoolean("explicit_lyrics", false),
                                releaseYear = relYear,
                                releaseDateMs = relMs,
                            ),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }

    /** Свежие релизы конкретного артиста для «Радара новинок» и свежей квоты волны. */
    suspend fun fetchArtistRecentReleases(
        artistName: String,
        maxAgeDays: Int = 60,
        limit: Int = 2,
    ): List<ExternalCatalogTrack> =
        withContext(Dispatchers.IO) {
            val cleanArtist = TrackMatcher.cleanSearchTerm(artistName)
            if (cleanArtist.length < 2) return@withContext emptyList()
            try {
                val artistResp =
                    CustomHttp.get(
                        url = "${apiBaseUrl()}/search/artist?q=${CustomHttp.urlEncode(cleanArtist)}&limit=3",
                        timeoutMs = 5_000,
                    )
                if (!artistResp.isSuccess) return@withContext emptyList()
                val artistData = JSONObject(artistResp.body).optJSONArray("data") ?: return@withContext emptyList()
                val normTarget = TrackMatcher.normalize(cleanArtist)
                var artistId: String? = null
                var resolvedName = cleanArtist
                for (i in 0 until artistData.length()) {
                    val obj = artistData.optJSONObject(i) ?: continue
                    val name = obj.optString("name").trim()
                    val normCand = TrackMatcher.normalize(name)
                    if (normCand == normTarget ||
                        (normTarget.length >= 4 && (normCand.contains(normTarget) || normTarget.contains(normCand)))
                    ) {
                        artistId = obj.optString("id").trim().takeIf { it.isNotBlank() }
                        resolvedName = name.ifBlank { cleanArtist }
                        break
                    }
                }
                val id = artistId ?: return@withContext emptyList()

                val albumsResp =
                    CustomHttp.get(
                        url = "${apiBaseUrl()}/artist/$id/albums?limit=12",
                        timeoutMs = 5_000,
                    )
                if (!albumsResp.isSuccess) return@withContext emptyList()
                val albums = JSONObject(albumsResp.body).optJSONArray("data") ?: return@withContext emptyList()

                val cutoffMs = System.currentTimeMillis() - maxAgeDays.toLong() * 24L * 3600L * 1_000L

                val recent = mutableListOf<Pair<Long, ExternalCatalogTrack>>()
                for (i in 0 until albums.length()) {
                    val alb = albums.optJSONObject(i) ?: continue
                    val albId = alb.optString("id").trim()
                    val title = alb.optString("title").trim()
                    val releaseStr = alb.optString("release_date").trim()
                    if (albId.isBlank() || title.isBlank() || releaseStr.length < 10) continue
                    val (relYear, releaseMs) = parseIsoReleaseDate(releaseStr)
                    if (releaseMs == null || releaseMs < cutoffMs) continue
                    val cover =
                        alb.optString("cover_xl").takeIf { it.isNotBlank() }
                            ?: alb.optString("cover_big").takeIf { it.isNotBlank() }
                    recent.add(
                        releaseMs to
                            ExternalCatalogTrack(
                                source = "deezer",
                                externalId = "dz_alb_$albId",
                                artist = resolvedName,
                                title = title,
                                coverUrl = cover,
                                isExplicit = alb.optBoolean("explicit_lyrics", false),
                                releaseYear = relYear,
                                releaseDateMs = releaseMs,
                            ),
                    )
                }
                recent
                    .sortedByDescending { it.first }
                    .take(limit)
                    .map { it.second }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }

    private suspend fun searchBestCandidate(
        cleanArtist: String,
        cleanTitle: String,
        targetDurationSec: Long?,
    ): DeezerCandidate? =
        try {
            val query = listOf(cleanArtist, cleanTitle).filter { it.isNotBlank() }.joinToString(" ")
            val url = "${apiBaseUrl()}/search/track?q=${CustomHttp.urlEncode(query)}&limit=8"
            val response = CustomHttp.get(url, timeoutMs = 3_500)
            if (!response.isSuccess) {
                null
            } else {
                val data = JSONObject(response.body).optJSONArray("data")
                if (data == null) {
                    null
                } else {
                    val candidates =
                        buildList {
                            for (i in 0 until data.length()) {
                                val item = data.optJSONObject(i) ?: continue
                                val albumObj = item.optJSONObject("album")
                                val cover =
                                    albumObj?.optString("cover_xl")?.takeIf { it.isNotBlank() }
                                        ?: albumObj?.optString("cover_big")?.takeIf { it.isNotBlank() }
                                val rawDate =
                                    item
                                        .optString("release_date")
                                        .trim()
                                        .ifBlank { albumObj?.optString("release_date")?.trim().orEmpty() }
                                val (relYear, relMs) = parseIsoReleaseDate(rawDate)
                                add(
                                    DeezerCandidate(
                                        id = item.optString("id").trim(),
                                        title = item.optString("title").trim(),
                                        artist = item.optJSONObject("artist")?.optString("name").orEmpty().trim(),
                                        duration = item.optLong("duration", 0L),
                                        explicit = item.optBoolean("explicit_lyrics", false),
                                        coverXl = cover,
                                        releaseYear = relYear,
                                        releaseDateMs = relMs,
                                    ),
                                )
                            }
                        }
                    pickBestDeezerMatch(candidates, cleanArtist, cleanTitle, targetDurationSec)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            Log.d(TAG, "Deezer lookup skipped for $cleanArtist - $cleanTitle: ${error.message}")
            null
        }

    private fun pickBestDeezerMatch(
        candidates: List<DeezerCandidate>,
        targetArtist: String,
        targetTitle: String,
        fallbackDurationSec: Long?,
    ): DeezerCandidate? =
        candidates
            .filter { candidate ->
                candidate.duration > 35L &&
                    !TrackMatcher.hasForbiddenStopWord(
                        requestedTitle = targetTitle,
                        candidateTitle = candidate.title,
                    )
            }.mapNotNull { candidate ->
                val score =
                    TrackMatcher.matchScore(
                        targetArtist = targetArtist,
                        targetTitle = targetTitle,
                        targetDurationSec = fallbackDurationSec,
                        candidateArtist = candidate.artist,
                        candidateTitle = candidate.title,
                        candidateDurationSec = candidate.duration.takeIf { it > 0L },
                    )
                if (score >= 62) candidate to score else null
            }.maxByOrNull { (candidate, score) ->
                score + if (candidate.explicit) 6 else 0
            }?.first

    private fun parseIsoReleaseDate(raw: String?): Pair<Int?, Long?> {
        val clean = raw?.trim().orEmpty()
        if (clean.length < 4) return null to null
        val year = clean.take(4).toIntOrNull()?.takeIf { it in 1900..2100 }
        val ms =
            if (clean.length >= 10) {
                runCatching {
                    SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(clean.take(10))?.time
                }.getOrNull()
            } else {
                null
            }
        return year to ms
    }

    companion object {
        private const val TAG = "DeezerRef"

        private fun apiBaseUrl(): String {
            val host = listOf("api", "deezer", "com").joinToString(".")
            return "https://" + host
        }
    }
}

object TrackMatcher {
    private val stopSubstrings =
        listOf(
            "censored",
            "radio edit",
            "sped up",
            "speed up",
            "slowed",
            "reverb",
            "nightcore",
            "karaoke",
            "instrumental",
            "acoustic",
            "акустика",
            "mashup",
            "мешап",
            "tiktok",
            "bass boosted",
        )

    private val stopWordRegexes =
        listOf(
            "clean",
            "8d",
            "minus",
            "минус",
            "cover",
            "кавер",
            "remix",
            "ремикс",
            "live",
        ).map { word ->
            word to Regex("(?<![a-zа-яё0-9])${Regex.escape(word)}(?![a-zа-яё0-9])")
        }

    private val noiseKeywords =
        listOf("official", "video", "audio", "lyrics", "lyric", "visualizer", "hd", "4k", "hq", "mv", "prod.", "feat.", "ft.")
    private val bracketRegex = Regex("""\s*[\[(]([^)\]]*)[\])]""")
    private val featRegex = Regex("""\s+(?:feat\.|ft\.|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val nonWordRegex = Regex("""[^\p{L}\p{N}\s]""")
    private val multiSpaceRegex = Regex("""\s+""")

    fun cleanSearchTerm(raw: String): String {
        val withoutNoiseBrackets =
            bracketRegex.replace(raw) { match ->
                val inner = match.groupValues.getOrElse(1) { "" }.lowercase()
                if (noiseKeywords.any { inner.contains(it) }) " " else match.value
            }
        return withoutNoiseBrackets
            .replace(featRegex, "")
            .replace(multiSpaceRegex, " ")
            .trim()
    }

    fun normalize(raw: String): String =
        cleanSearchTerm(raw)
            .lowercase()
            .replace('ё', 'е')
            .replace(nonWordRegex, " ")
            .replace(multiSpaceRegex, " ")
            .trim()

    fun hasForbiddenStopWord(
        requestedTitle: String,
        candidateTitle: String,
    ): Boolean {
        val reqLower = requestedTitle.lowercase()
        val candLower = candidateTitle.lowercase()
        if (stopSubstrings.any { word -> candLower.contains(word) && !reqLower.contains(word) }) {
            return true
        }
        return stopWordRegexes.any { (_, regex) ->
            regex.containsMatchIn(candLower) && !regex.containsMatchIn(reqLower)
        }
    }

    fun isDurationAcceptable(
        targetDurationSec: Long?,
        candidateDurationSec: Long?,
        toleranceSec: Long = 5L,
    ): Boolean {
        if (targetDurationSec == null || targetDurationSec <= 0L) return true
        if (candidateDurationSec == null || candidateDurationSec <= 0L) return false
        return abs(targetDurationSec - candidateDurationSec) <= toleranceSec
    }

    fun matchScore(
        targetArtist: String,
        targetTitle: String,
        targetDurationSec: Long?,
        candidateArtist: String,
        candidateTitle: String,
        candidateDurationSec: Long?,
    ): Int {
        val tTitle = normalize(targetTitle)
        val cTitle = normalize(candidateTitle)
        if (tTitle.isBlank() || cTitle.isBlank()) return 0

        val titleScore = tokenOverlapScore(tTitle, cTitle)
        if (titleScore < 45) return 0

        val tArtist = normalize(targetArtist)
        val cArtist = normalize(candidateArtist)
        val artistScore =
            if (tArtist.isBlank()) {
                30
            } else {
                val direct = tokenOverlapScore(tArtist, cArtist)
                val inTitle = if (tArtist.length >= 4 && cTitle.contains(tArtist)) 50 else 0
                maxOf(direct, inTitle)
            }
        if (tArtist.isNotBlank() && artistScore < 30) return 0

        val durationBonus =
            when {
                targetDurationSec == null || targetDurationSec <= 0L || candidateDurationSec == null || candidateDurationSec <= 0L -> 0
                abs(targetDurationSec - candidateDurationSec) <= 2L -> 25
                abs(targetDurationSec - candidateDurationSec) <= 5L -> 15
                abs(targetDurationSec - candidateDurationSec) <= 9L -> 0
                else -> -45
            }

        return ((titleScore * 0.55) + (artistScore * 0.45)).toInt() + durationBonus
    }

    private fun tokenOverlapScore(
        a: String,
        b: String,
    ): Int {
        if (a == b) return 100
        val minLen = minOf(a.length, b.length)
        if (minLen >= 4 && (a.contains(b) || b.contains(a))) return 85
        val rawA = a.split(' ').filter { it.isNotBlank() }
        val rawB = b.split(' ').filter { it.isNotBlank() }
        val tokensA = rawA.filter { it.length > 1 }.ifEmpty { rawA }.toSet()
        val tokensB = rawB.filter { it.length > 1 }.ifEmpty { rawB }.toSet()
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0
        if (tokensA.size == 1 && tokensA.single() in tokensB) return 85
        if (tokensB.size == 1 && tokensB.single() in tokensA) return 85
        val intersection = tokensA.intersect(tokensB).size
        val union = tokensA.union(tokensB).size
        return (intersection * 100) / union
    }
}
