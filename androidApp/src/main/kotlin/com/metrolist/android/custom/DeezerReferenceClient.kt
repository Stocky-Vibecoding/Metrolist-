package com.metrolist.android.custom

import android.util.Log
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
            val conn = (URL(url).openConnection() as HttpURLConnection)
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.outputStream.use { out ->
                    out.write(jsonBody.toByteArray(Charsets.UTF_8))
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
)

private data class DeezerCandidate(
    val id: String,
    val title: String,
    val artist: String,
    val duration: Long,
    val explicit: Boolean,
    val coverXl: String?,
)

class DeezerReferenceClient {
    private val cache = ConcurrentHashMap<String, TrackReference>()

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

        val resolved =
            if (fetched != null) {
                TrackReference(
                    title = fetched.title.ifBlank { cleanTitle },
                    artist = fetched.artist.ifBlank { cleanArtist },
                    durationSec = fetched.duration.takeIf { it > 0L } ?: fallback.durationSec,
                    isExplicit = fetched.explicit || fallbackExplicit,
                    fromDeezer = true,
                    coverXlUrl = fetched.coverXl,
                    deezerId = fetched.id.takeIf { it.isNotBlank() },
                )
            } else {
                fallback
            }

        if (cache.size > 600) cache.clear()
        cache[cacheKey] = resolved
        return resolved
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
        cache[cacheKey] =
            TrackReference(
                title = best.title.ifBlank { cleanTitle },
                artist = best.artist.ifBlank { cleanArtist },
                durationSec = best.duration.takeIf { it > 0L },
                isExplicit = best.explicit,
                fromDeezer = true,
                coverXlUrl = best.coverXl,
                deezerId = best.id,
            )

        return ExternalCatalogTrack(
            source = "deezer",
            externalId = "dz_${best.id}",
            artist = best.artist.ifBlank { cleanArtist },
            title = best.title.ifBlank { cleanTitle },
            durationSec = best.duration.takeIf { it > 0L },
            coverUrl = best.coverXl,
            isExplicit = best.explicit,
        )
    }

    /** Чарт Deezer для единого блока «Чарты» на Главной. */
    suspend fun fetchChart(limit: Int = 25): List<ExternalCatalogTrack> =
        withContext(Dispatchers.IO) {
            runCatching {
                val safeLimit = limit.coerceIn(1, 50)
                val url = "${apiBaseUrl()}/chart/0/tracks?limit=$safeLimit"
                val response = CustomHttp.get(url, timeoutMs = 8_000)
                if (!response.isSuccess) return@runCatching emptyList<ExternalCatalogTrack>()
                val data = JSONObject(response.body).optJSONArray("data") ?: return@runCatching emptyList<ExternalCatalogTrack>()
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
                        add(
                            ExternalCatalogTrack(
                                source = "deezer",
                                externalId = "dz_$id",
                                artist = artist,
                                title = title,
                                durationSec = item.optLong("duration", 0L).takeIf { it > 0L },
                                coverUrl = cover,
                                isExplicit = item.optBoolean("explicit_lyrics", false),
                            ),
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }

    /** Свежие релизы конкретного артиста для «Радара новинок». */
    suspend fun fetchArtistRecentReleases(
        artistName: String,
        maxAgeDays: Int = 60,
        limit: Int = 2,
    ): List<ExternalCatalogTrack> =
        withContext(Dispatchers.IO) {
            val cleanArtist = TrackMatcher.cleanSearchTerm(artistName)
            if (cleanArtist.length < 2) return@withContext emptyList()
            runCatching {
                val artistResp =
                    CustomHttp.get(
                        url = "${apiBaseUrl()}/search/artist?q=${CustomHttp.urlEncode(cleanArtist)}&limit=3",
                        timeoutMs = 5_000,
                    )
                if (!artistResp.isSuccess) return@runCatching emptyList<ExternalCatalogTrack>()
                val artistData = JSONObject(artistResp.body).optJSONArray("data") ?: return@runCatching emptyList<ExternalCatalogTrack>()
                val normTarget = TrackMatcher.normalize(cleanArtist)
                var artistId: String? = null
                var resolvedName = cleanArtist
                for (i in 0 until artistData.length()) {
                    val obj = artistData.optJSONObject(i) ?: continue
                    val name = obj.optString("name").trim()
                    val normCand = TrackMatcher.normalize(name)
                    if (normCand == normTarget || normCand.contains(normTarget) || normTarget.contains(normCand)) {
                        artistId = obj.optString("id").trim().takeIf { it.isNotBlank() }
                        resolvedName = name.ifBlank { cleanArtist }
                        break
                    }
                }
                val id = artistId ?: return@runCatching emptyList<ExternalCatalogTrack>()

                val albumsResp =
                    CustomHttp.get(
                        url = "${apiBaseUrl()}/artist/$id/albums?limit=8",
                        timeoutMs = 5_000,
                    )
                if (!albumsResp.isSuccess) return@runCatching emptyList<ExternalCatalogTrack>()
                val albums = JSONObject(albumsResp.body).optJSONArray("data") ?: return@runCatching emptyList<ExternalCatalogTrack>()

                val cutoffMs = System.currentTimeMillis() - maxAgeDays.toLong() * 24L * 3600L * 1_000L
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

                val recent = mutableListOf<Pair<Long, ExternalCatalogTrack>>()
                for (i in 0 until albums.length()) {
                    val alb = albums.optJSONObject(i) ?: continue
                    val albId = alb.optString("id").trim()
                    val title = alb.optString("title").trim()
                    val releaseStr = alb.optString("release_date").trim()
                    if (albId.isBlank() || title.isBlank() || releaseStr.length < 10) continue
                    val releaseMs = runCatching { dateFormat.parse(releaseStr.take(10))?.time }.getOrNull() ?: continue
                    if (releaseMs < cutoffMs) continue
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
                            ),
                    )
                }
                recent
                    .sortedByDescending { it.first }
                    .take(limit)
                    .map { it.second }
            }.getOrDefault(emptyList())
        }

    private suspend fun searchBestCandidate(
        cleanArtist: String,
        cleanTitle: String,
        targetDurationSec: Long?,
    ): DeezerCandidate? =
        runCatching {
            val query = listOf(cleanArtist, cleanTitle).filter { it.isNotBlank() }.joinToString(" ")
            val url = "${apiBaseUrl()}/search/track?q=${CustomHttp.urlEncode(query)}&limit=8"
            val response = CustomHttp.get(url, timeoutMs = 4_000)
            if (!response.isSuccess) return@runCatching null
            val data = JSONObject(response.body).optJSONArray("data") ?: return@runCatching null
            val candidates =
                buildList {
                    for (i in 0 until data.length()) {
                        val item = data.optJSONObject(i) ?: continue
                        val albumObj = item.optJSONObject("album")
                        val cover =
                            albumObj?.optString("cover_xl")?.takeIf { it.isNotBlank() }
                                ?: albumObj?.optString("cover_big")?.takeIf { it.isNotBlank() }
                        add(
                            DeezerCandidate(
                                id = item.optString("id").trim(),
                                title = item.optString("title").trim(),
                                artist = item.optJSONObject("artist")?.optString("name").orEmpty().trim(),
                                duration = item.optLong("duration", 0L),
                                explicit = item.optBoolean("explicit_lyrics", false),
                                coverXl = cover,
                            ),
                        )
                    }
                }
            pickBestDeezerMatch(candidates, cleanArtist, cleanTitle, targetDurationSec)
        }.onFailure { error ->
            Log.d(TAG, "Deezer lookup skipped for $cleanArtist - $cleanTitle: ${error.message}")
        }.getOrNull()

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

    companion object {
        private const val TAG = "DeezerRef"

        private fun apiBaseUrl(): String {
            val host = listOf("api", "deezer", "com").joinToString(".")
            return "https://" + host
        }
    }
}

object TrackMatcher {
    private val stopWords =
        listOf(
            "clean",
            "censored",
            "radio edit",
            "sped up",
            "speed up",
            "slowed",
            "reverb",
            "nightcore",
            "8d",
            "karaoke",
            "instrumental",
            "minus",
            "минус",
            "cover",
            "кавер",
            "remix",
            "ремикс",
            "live",
            "acoustic",
            "акустика",
            "mashup",
            "мешап",
            "tiktok",
            "bass boosted",
        )

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
        return stopWords.any { word ->
            candLower.contains(word) && !reqLower.contains(word)
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
                val inTitle = if (cTitle.contains(tArtist)) 50 else 0
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
        if (a.contains(b) || b.contains(a)) return 85
        val tokensA = a.split(' ').filter { it.length > 1 }.toSet()
        val tokensB = b.split(' ').filter { it.length > 1 }.toSet()
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0
        val intersection = tokensA.intersect(tokensB).size
        val union = tokensA.union(tokensB).size
        return (intersection * 100) / union
    }
}
