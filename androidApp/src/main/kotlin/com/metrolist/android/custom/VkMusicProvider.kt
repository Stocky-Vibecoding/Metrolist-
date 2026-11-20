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

private data class VkClientProfile(
    val label: String,
    val userAgent: String,
    val apiVersion: String,
)

class VkMusicProvider(
    private val configStore: CustomConfigStore,
) {
    @Volatile
    private var activeProfile: VkClientProfile = CLIENT_PROFILES.first()

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

        // Шаг 1: проверяем, что сам токен живой
        var accountName: String? = null
        runCatching {
            val userUrl =
                "${apiBaseUrl()}/users.get" +
                    "?access_token=${CustomHttp.urlEncode(cleanToken)}" +
                    "&v=5.131"
            val userResp = CustomHttp.get(userUrl, headers = mapOf("User-Agent" to activeProfile.userAgent), timeoutMs = 8_000)
            if (userResp.isSuccess) {
                val root = JSONObject(userResp.body)
                if (root.has("error")) {
                    val err = root.optJSONObject("error")
                    val code = err?.optInt("error_code", -1) ?: -1
                    val msg = err?.optString("error_msg").orEmpty()
                    return Result.failure(IllegalStateException("Токен невалиден — VK #$code: $msg"))
                }
                val first = root.optJSONArray("response")?.optJSONObject(0)
                if (first != null) {
                    accountName = listOf(first.optString("first_name"), first.optString("last_name"))
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                }
            }
        }

        // Шаг 2: подбираем рабочую пару User-Agent + версия API для audio.search
        var lastError = "Нет ответа от VK API"
        for (profile in CLIENT_PROFILES) {
            val attempt =
                runCatching {
                    val url =
                        "${apiBaseUrl()}/audio.search" +
                            "?q=test&count=1&lang=ru" +
                            "&access_token=${CustomHttp.urlEncode(cleanToken)}" +
                            "&v=${profile.apiVersion}"
                    val response =
                        CustomHttp.get(
                            url = url,
                            headers = mapOf("User-Agent" to profile.userAgent),
                            timeoutMs = 8_000,
                        )
                    if (!response.isSuccess) {
                        return@runCatching Result.failure<String>(IllegalStateException("VK HTTP ${response.code}"))
                    }
                    val root = JSONObject(response.body)
                    if (root.has("error")) {
                        val err = root.optJSONObject("error")
                        val code = err?.optInt("error_code", -1) ?: -1
                        val msg = err?.optString("error_msg").orEmpty()
                        Result.failure<String>(IllegalStateException("VK #$code: $msg"))
                    } else if (root.has("response")) {
                        activeProfile = profile
                        val who = accountName?.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
                        Result.success("ВК аудио работает (${profile.label}$who)")
                    } else {
                        Result.failure<String>(IllegalStateException("Пустой ответ от VK"))
                    }
                }.getOrElse { err ->
                    Result.failure(err)
                }

            if (attempt.isSuccess) return attempt
            lastError = attempt.exceptionOrNull()?.message ?: lastError
        }

        val prefix = accountName?.takeIf { it.isNotBlank() }?.let { "Аккаунт $it найден, но аудио закрыто: " }.orEmpty()
        return Result.failure(IllegalStateException("$prefix$lastError"))
    }

    suspend fun resolve(reference: TrackReference): ResolvedCustomStream? {
        val token = sanitizeToken(configStore.current.vkToken)
        if (token.isBlank()) return null

        val query = listOf(reference.artist, reference.title).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return null

        val candidates = searchAudio(token, query)
        val best = pickBestMatch(candidates, reference) ?: return null

        val isHls = best.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        Log.d(TAG, "Resolved via VK (${if (isHls) "HLS" else "MP3"}): ${best.artist} - ${best.title}")

        return ResolvedCustomStream(
            url = best.url,
            headers = mapOf("User-Agent" to activeProfile.userAgent),
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
    ): List<VkAudioCandidate> {
        val profilesToTry = (listOf(activeProfile) + CLIENT_PROFILES).distinct()
        for (profile in profilesToTry) {
            val result: List<VkAudioCandidate>? =
                runCatching<List<VkAudioCandidate>?> {
                    val url =
                        "${apiBaseUrl()}/audio.search" +
                            "?q=${CustomHttp.urlEncode(query)}" +
                            "&count=15" +
                            "&sort=2" +
                            "&auto_complete=1" +
                            "&lang=ru" +
                            "&access_token=${CustomHttp.urlEncode(token)}" +
                            "&v=${profile.apiVersion}"
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
                        Log.w(TAG, "VK ${profile.label} error #$code")
                        return@runCatching null
                    }
                    activeProfile = profile
                    val items = root.optJSONObject("response")?.optJSONArray("items")
                        ?: return@runCatching emptyList<VkAudioCandidate>()
                    buildList<VkAudioCandidate> {
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
                }.getOrNull()

            if (result != null) return result
        }
        return emptyList()
    }

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

    companion object {
        private const val TAG = "VkProvider"

        private fun apiBaseUrl(): String {
            val host = listOf("api", "vk", "com").joinToString(".")
            return "https://" + host + "/method"
        }

        private val CLIENT_PROFILES =
            listOf(
                VkClientProfile(
                    label = "Kate 103",
                    userAgent = "KateMobileAndroid/103.1 lite-545 (Android 13; SDK 33; arm64-v8a; samsung SM-F956B; ru)",
                    apiVersion = "5.131",
                ),
                VkClientProfile(
                    label = "Kate 56",
                    userAgent = "KateMobileAndroid/56 lite-460 (Android 4.4.2; SDK 19; x86; unknown Android SDK built for x86; en)",
                    apiVersion = "5.95",
                ),
                VkClientProfile(
                    label = "Kate 95",
                    userAgent = "KateMobileAndroid/95.1 lite-536 (Android 12; SDK 31; arm64-v8a; Pixel 6; ru)",
                    apiVersion = "5.131",
                ),
                VkClientProfile(
                    label = "VK Android 8",
                    userAgent = "VKAndroidApp/8.99-23423 (Android 13; SDK 33; arm64-v8a; samsung SM-S918B; ru; 2340x1080)",
                    apiVersion = "5.131",
                ),
                VkClientProfile(
                    label = "VK Android 7",
                    userAgent = "VKAndroidApp/7.26-13561 (Android 11; SDK 30; arm64-v8a; Xiaomi MI 9; ru; 2340x1080)",
                    apiVersion = "5.119",
                ),
            )
    }
}
