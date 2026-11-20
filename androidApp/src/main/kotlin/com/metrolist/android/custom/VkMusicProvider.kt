package com.metrolist.android.custom

import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private data class VkAudioCandidate(
    val artist: String,
    val title: String,
    val durationSec: Long,
    val url: String,
    val isExplicit: Boolean,
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
        val ranked = rankMatches(candidates, reference, relaxed = variantIndex > 0)
        if (ranked.isEmpty()) return null

        val idx = variantIndex.coerceAtLeast(0) % ranked.size
        val best = ranked[idx]
        val variantTag = if (ranked.size > 1 && variantIndex > 0) "#${idx + 1}/${ranked.size}" else null

        val isHls = best.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        Log.d(TAG, "Resolved via VK (${if (isHls) "HLS" else "MP3"} ${variantTag ?: "#1"}): ${best.artist} - ${best.title}")

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
                    val url = buildSearchUrl(token = token, query = query, count = 20, profile = profile)
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
        return buildList<VkAudioCandidate> {
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
    }

    private fun rankMatches(
        candidates: List<VkAudioCandidate>,
        reference: TrackReference,
        relaxed: Boolean,
    ): List<VkAudioCandidate> {
        val tolerance = if (relaxed) 12L else 5L
        val minScore = if (relaxed) 55 else 65
        return candidates
            .filter { candidate ->
                !TrackMatcher.hasForbiddenStopWord(reference.title, candidate.title) &&
                    TrackMatcher.isDurationAcceptable(reference.durationSec, candidate.durationSec, toleranceSec = tolerance)
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
                if (score >= minScore) {
                    val explicitBonus = if (reference.isExplicit && candidate.isExplicit) 12 else 0
                    val directMp3Bonus = if (!candidate.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) 4 else 0
                    candidate to (score + explicitBonus + directMp3Bonus)
                } else {
                    null
                }
            }.sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { it.url.substringBefore('?') }
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
