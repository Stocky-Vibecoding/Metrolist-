package com.metrolist.android.custom

import android.util.Log
import org.json.JSONObject

private data class VkAudioCandidate(
    val artist: String,
    val title: String,
    val durationSec: Long,
    val url: String,
    val isExplicit: Boolean,
)

class VkMusicProvider(
    private val configStore: CustomConfigStore,
) {
    suspend fun checkToken(
        token: String = configStore.current.vkToken,
    ): Boolean {
        val cleanToken = token.trim()
        if (cleanToken.isBlank()) return false
        return runCatching {
            val url =
                "$API_BASE/audio.search?q=test&count=1&access_token=${CustomHttp.urlEncode(cleanToken)}&v=$API_VERSION"
            val response = CustomHttp.get(url, headers = vkHeaders(), timeoutMs = 6_000)
            if (!response.isSuccess) return@runCatching false
            val root = JSONObject(response.body)
            !root.has("error") && root.has("response")
        }.getOrDefault(false)
    }

    suspend fun resolve(reference: TrackReference): ResolvedCustomStream? {
        val token = configStore.current.vkToken.trim()
        if (token.isBlank()) return null

        val query = listOf(reference.artist, reference.title).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return null

        val candidates = searchAudio(token, query)
        val best = pickBestMatch(candidates, reference) ?: return null

        val isHls = best.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        Log.d(TAG, "Resolved via VK (${if (isHls) "HLS" else "MP3"}): ${best.artist} - ${best.title}")

        return ResolvedCustomStream(
            url = best.url,
            headers = vkHeaders(),
            mimeType = if (isHls) "application/x-mpegURL" else "audio/mpeg",
            bitrate = null,
            durationSec = best.durationSec.takeIf { it > 0L },
            clientName = "vk",
            isHls = isHls,
        )
    }

    private suspend fun searchAudio(
        token: String,
        query: String,
    ): List<VkAudioCandidate> =
        runCatching {
            val url =
                "$API_BASE/audio.search" +
                    "?q=${CustomHttp.urlEncode(query)}" +
                    "&count=15" +
                    "&sort=2" +
                    "&auto_complete=1" +
                    "&access_token=${CustomHttp.urlEncode(token)}" +
                    "&v=$API_VERSION"
            val response = CustomHttp.get(url, headers = vkHeaders(), timeoutMs = 6_000)
            if (!response.isSuccess) return emptyList()

            val root = JSONObject(response.body)
            if (root.has("error")) {
                val errMsg = root.optJSONObject("error")?.optString("error_msg").orEmpty()
                Log.w(TAG, "VK API error: $errMsg")
                return emptyList()
            }

            val items = root.optJSONObject("response")?.optJSONArray("items") ?: return emptyList()
            buildList {
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val streamUrl = item.optString("url").trim()
                    if (streamUrl.isBlank()) continue
                    val title = item.optString("title")
                    val subtitle = item.optString("subtitle")
                    val fullTitle = if (subtitle.isNotBlank()) "$title ($subtitle)" else title
                    add(
                        VkAudioCandidate(
                            artist = item.optString("artist"),
                            title = fullTitle,
                            durationSec = item.optLong("duration", 0L),
                            url = streamUrl,
                            isExplicit = item.optBoolean("is_explicit", false),
                        ),
                    )
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "VK search failed: ${error.message}")
        }.getOrDefault(emptyList())

    private fun pickBestMatch(
        candidates: List<VkAudioCandidate>,
        reference: TrackReference,
    ): VkAudioCandidate? =
        candidates
            .filter { candidate ->
                !TrackMatcher.hasForbiddenStopWord(reference.title, candidate.title) &&
                    TrackMatcher.isDurationAcceptable(reference.durationSec, candidate.durationSec, toleranceSec = 5L)
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
                    val explicitBonus = if (reference.isExplicit && candidate.isExplicit) 12 else 0
                    val directMp3Bonus = if (!candidate.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) 4 else 0
                    candidate to (score + explicitBonus + directMp3Bonus)
                } else {
                    null
                }
            }.maxByOrNull { it.second }
            ?.first

    private fun vkHeaders(): Map<String, String> =
        mapOf(
            "User-Agent" to KATE_USER_AGENT,
        )

    companion object {
        private const val TAG = "VkProvider"
        private const val API_BASE = "https://api.vk.com/method"
        private const val API_VERSION = "5.131"
        private const val KATE_USER_AGENT =
            "KateMobileAndroid/56 lite-460 (Android 4.4.2; SDK 19; x86; unknown Android SDK built for x86; en)"
    }
}
