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
    @Volatile
    private var activeUserAgent: String = KATE_USER_AGENT

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

        val candidatesUa = listOf(KATE_USER_AGENT, VK_ANDROID_USER_AGENT)
        var lastError = "Неизвестная ошибка ВК"

        for (ua in candidatesUa) {
            val attempt =
                runCatching {
                    val url =
                        "$API_BASE/audio.search" +
                            "?q=test&count=1" +
                            "&access_token=${CustomHttp.urlEncode(cleanToken)}" +
                            "&v=$API_VERSION"
                    val response = CustomHttp.get(url, headers = mapOf("User-Agent" to ua), timeoutMs = 8_000)
                    if (!response.isSuccess) {
                        return@runCatching Result.failure<String>(IllegalStateException("VK HTTP ${response.code}"))
                    }
                    val root = JSONObject(response.body)
                    if (root.has("error")) {
                        val err = root.optJSONObject("error")
                        val code = err?.optInt("error_code", -1) ?: -1
                        val msg = err?.optString("error_msg").orEmpty()
                        Result.failure(IllegalStateException("VK #$code:$msg"))
                    } else if (root.has("response")) {
                        activeUserAgent = ua
                        val label = if (ua == KATE_USER_AGENT) "Kate" else "VK Android"
                        Result.success("Токен ВК работает ($label)")
                    } else {
                        Result.failure(IllegalStateException("Пустой ответ от VK API"))
                    }
                }.getOrElse { err ->
                    Result.failure(err)
                }

            if (attempt.isSuccess) return attempt
            lastError = attempt.exceptionOrNull()?.message ?: lastError
        }

        return Result.failure(IllegalStateException(lastError))
    }

    suspend fun resolve(reference: TrackReference): ResolvedCustomStream? {
        val token = sanitizeToken(configStore.current.vkToken)
        if (token.isBlank()) return null

        val query = listOf(reference.artist, reference.title).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return null

        val candidates = searchAudio(token, query)
        val best = pickBestMatch(candidates, reference) ?: return null

        val isHls = best.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        Log.d(TAG, "Resolved via VK (${if (isHls) "HLS" else "MP3"}): ${best.artist} -${best.title}")

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

    private fun vkHeaders(): Map<String, String> =
        mapOf(
            "User-Agent" to activeUserAgent,
        )

    companion object {
        private const val TAG = "VkProvider"
        private const val API_BASE = "https:" + "//api.vk.com/method"
        private const val API_VERSION = "5.131"
        private const val KATE_USER_AGENT =
            "KateMobileAndroid/56 lite-460 (Android 4.4.2; SDK 19; x86; unknown Android SDK built for x86; en)"
        private const val VK_ANDROID_USER_AGENT =
            "VKAndroidApp/8.99-23423 (Android 13; SDK 33; arm64-v8a; samsung SM-S918B; ru; 2340x1080)"
    }
}
