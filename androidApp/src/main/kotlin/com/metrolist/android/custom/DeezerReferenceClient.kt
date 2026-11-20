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
)

private data class DeezerCandidate(
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
            )

        if (cleanTitle.isBlank()) return fallback

        val fetched =
            runCatching {
                val query = listOf(cleanArtist, cleanTitle).filter { it.isNotBlank() }.joinToString(" ")
                val url = "${apiBaseUrl()}/search/track?q=${CustomHttp.urlEncode(query)}&limit=6"
                val response = CustomHttp.get(url, timeoutMs = 3_000)
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
                                    title = item.optString("title"),
                                    artist = item.optJSONObject("artist")?.optString("name").orEmpty(),
                                    duration = item.optLong("duration", 0L),
                                    explicit = item.optBoolean("explicit_lyrics", false),
                                    coverXl = cover,
                                ),
                            )
                        }
                    }
                pickBestDeezerMatch(candidates, cleanArtist, cleanTitle, fallbackDurationSec)
            }.onFailure { error ->
                Log.d(TAG, "Deezer lookup skipped for $cleanArtist - $cleanTitle: ${error.message}")
            }.getOrNull()

        val resolved =
            if (fetched != null) {
                TrackReference(
                    title = fetched.title.ifBlank { cleanTitle },
                    artist = fetched.artist.ifBlank { cleanArtist },
                    durationSec = fetched.duration.takeIf { it > 0L } ?: fallback.durationSec,
                    isExplicit = fetched.explicit || fallbackExplicit,
                    fromDeezer = true,
                    coverXlUrl = fetched.coverXl,
                )
            } else {
                fallback
            }

        if (cache.size > 500) cache.clear()
        cache[cacheKey] = resolved
        return resolved
    }

    private fun pickBestDeezerMatch(
        candidates: List<DeezerCandidate>,
        targetArtist: String,
        targetTitle: String,
        fallbackDurationSec: Long?,
    ): DeezerCandidate? =
        candidates
            .filter { candidate ->
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
                if (score >= 60) candidate to score else null
            }.maxByOrNull { (candidate, score) ->
                score + if (candidate.explicit) 5 else 0
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
