package com.metrolist.android.custom

import android.util.Log
import org.json.JSONObject

data class ResolvedCustomStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val bitrate: Int? = null,
    val durationSec: Long? = null,
    val clientName: String,
    val isHls: Boolean = false,
)

private data class YandexTrackCandidate(
    val id: String,
    val title: String,
    val artist: String,
    val durationSec: Long?,
    val isExplicit: Boolean,
    val version: String,
)

class YandexMusicProvider(
    private val configStore: CustomConfigStore,
) {
    suspend fun checkPlusStatus(
        token: String = configStore.current.yandexToken,
    ): YandexPlusStatus {
        val cleanToken = token.trim()
        if (cleanToken.isBlank()) {
            configStore.setYandexPlusStatus(YandexPlusStatus.NO_TOKEN)
            return YandexPlusStatus.NO_TOKEN
        }

        val status =
            runCatching {
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
            }.onFailure { error ->
                Log.w(TAG, "Yandex status check failed: ${error.message}")
            }.getOrDefault(YandexPlusStatus.UNKNOWN)

        configStore.setYandexPlusStatus(status)
        return status
    }

    suspend fun resolve(reference: TrackReference): ResolvedCustomStream? {
        val token = configStore.current.yandexToken.trim()
        if (token.isBlank()) return null

        val plusStatus =
            when (val cached = configStore.yandexPlusStatus.value) {
                YandexPlusStatus.UNKNOWN -> checkPlusStatus(token)
                else -> cached
            }
        if (plusStatus != YandexPlusStatus.ACTIVE) return null

        val query = listOf(reference.artist, reference.title).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return null

        val candidates = searchTracks(token, query)
        val best = pickBestMatch(candidates, reference) ?: return null

        val stream = fetchDirectMp3(token, best) ?: return null
        Log.d(TAG, "Resolved via Yandex: ${best.artist} - ${best.title} (${stream.bitrate ?: 0} kbps)")
        return stream
    }

    private suspend fun searchTracks(
        token: String,
        query: String,
    ): List<YandexTrackCandidate> =
        runCatching {
            val url = "$API_BASE/search?type=track&text=${CustomHttp.urlEncode(query)}&page=0&nocorrect=false"
            val response = CustomHttp.get(url, headers = authHeaders(token), timeoutMs = 6_000)
            if (response.code == 401 || response.code == 403) {
                configStore.setYandexPlusStatus(YandexPlusStatus.INVALID_TOKEN)
                return emptyList()
            }
            if (!response.isSuccess) return emptyList()

            val results =
                JSONObject(response.body)
                    .optJSONObject("result")
                    ?.optJSONObject("tracks")
                    ?.optJSONArray("results")
                    ?: return emptyList()

            buildList {
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    if (!item.optBoolean("available", true)) continue
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val title = item.optString("title")
                    val version = item.optString("version")
                    val fullTitle = if (version.isNotBlank()) "$title ($version)" else title
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
                    val isExplicit = item.optString("contentWarning").equals("explicit", ignoreCase = true)

                    add(
                        YandexTrackCandidate(
                            id = id,
                            title = fullTitle,
                            artist = artistNames,
                            durationSec = durationSec,
                            isExplicit = isExplicit,
                            version = version,
                        ),
                    )
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Yandex search failed: ${error.message}")
        }.getOrDefault(emptyList())

    private fun pickBestMatch(
        candidates: List<YandexTrackCandidate>,
        reference: TrackReference,
    ): YandexTrackCandidate? =
        candidates
            .filter { candidate ->
                !TrackMatcher.hasForbiddenStopWord(reference.title, candidate.title) &&
                    !candidate.version.contains("clean", ignoreCase = true) &&
                    TrackMatcher.isDurationAcceptable(reference.durationSec, candidate.durationSec, toleranceSec = 6L)
            }.mapNotNull { candidate ->
                val score =
                    TrackMatcher.matchScore(
                        targetArtist = reference.artist,
                        targetTitle = reference.title,
                        targetDurationSec = reference.durationSec,
                        candidateArtist = candidate.artist,
                        candidateTitle = candidate.title,
                        candidateDurationSec = candidate.durationSec,
                    )
                if (score >= 65) {
                    val explicitBonus =
                        when {
                            reference.isExplicit && candidate.isExplicit -> 12
                            reference.isExplicit && !candidate.isExplicit -> -8
                            else -> 0
                        }
                    candidate to (score + explicitBonus)
                } else {
                    null
                }
            }.maxByOrNull { it.second }
            ?.first

    private suspend fun fetchDirectMp3(
        token: String,
        track: YandexTrackCandidate,
    ): ResolvedCustomStream? =
        runCatching {
            val infoResp =
                CustomHttp.get(
                    url = "$API_BASE/tracks/${track.id}/download-info",
                    headers = authHeaders(token),
                    timeoutMs = 6_000,
                )
            if (!infoResp.isSuccess) return@runCatching null

            val list = JSONObject(infoResp.body).optJSONArray("result") ?: return@runCatching null
            var bestUrl: String? = null
            var bestBitrate = -1

            for (i in 0 until list.length()) {
                val entry = list.optJSONObject(i) ?: continue
                val codec = entry.optString("codec")
                if (!codec.equals("mp3", ignoreCase = true)) continue
                val bitrate = entry.optInt("bitrateInKbps", 0)
                val downloadInfoUrl = entry.optString("downloadInfoUrl")
                if (downloadInfoUrl.isNotBlank() && bitrate > bestBitrate) {
                    bestBitrate = bitrate
                    bestUrl = downloadInfoUrl
                }
            }

            val targetInfoUrl = bestUrl ?: return@runCatching null
            val separator = if (targetInfoUrl.contains('?')) '&' else '?'
            val metaResp =
                CustomHttp.get(
                    url = "$targetInfoUrl${separator}format=json",
                    headers = authHeaders(token),
                    timeoutMs = 6_000,
                )
            if (!metaResp.isSuccess) return@runCatching null

            val (host, path, ts, s) = parseDownloadMeta(metaResp.body) ?: return@runCatching null
            val sign = CustomHttp.md5Hex(SIGN_SALT + path.removePrefix("/") + s)
            val directUrl = "https://$host/get-mp3/$sign/$ts$path"

            ResolvedCustomStream(
                url = directUrl,
                headers = mapOf("User-Agent" to CLIENT_USER_AGENT),
                mimeType = "audio/mpeg",
                bitrate = if (bestBitrate > 0) bestBitrate * 1000 else null,
                durationSec = track.durationSec,
                clientName = "yandex",
                isHls = false,
            )
        }.onFailure { error ->
            Log.w(TAG, "Yandex direct link failed for ${track.id}: ${error.message}")
        }.getOrNull()

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

    private fun authHeaders(token: String): Map<String, String> =
        mapOf(
            "Authorization" to "OAuth $token",
            "X-Yandex-Music-Client" to "YandexMusicAndroid/24023621",
            "User-Agent" to CLIENT_USER_AGENT,
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
    }
}
